package dev.avijit.orbithealth.server

import dev.avijit.orbithealth.data.NetworkLogDao
import dev.avijit.orbithealth.data.NetworkLogEntity
import dev.avijit.orbithealth.sync.AccessLogEntry
import dev.avijit.orbithealth.sync.AskRequest
import dev.avijit.orbithealth.sync.AskResponse
import dev.avijit.orbithealth.sync.ChatTurn
import dev.avijit.orbithealth.sync.PairingUri
import dev.avijit.orbithealth.sync.TrustMode
import dev.avijit.orbithealth.sync.Scope
import java.time.Clock
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import okhttp3.OkHttpClient

/** The paired server as the UI may see it (no token). [fingerprint] is null for a public-CA pairing. */
data class ServerInfo(
    val url: String,
    val name: String,
    val fingerprint: String?,
    val deviceId: String,
    val scopes: Set<Scope>,
    val trustMode: ServerTrustMode = ServerTrustMode.PIN,
)

private fun PairedServer.info() =
    ServerInfo(url, serverName, fingerprint, deviceId, effectiveScopes.mapNotNull(Scope::fromWire).toSet(), trustMode)

/** [NetworkRecorder] into Room; prunes rows older than [RETENTION_MS] at most once a day. Never throws. */
class RoomNetworkRecorder(private val dao: NetworkLogDao, private val clock: Clock) : NetworkRecorder {
    @Volatile private var lastPruneMs = 0L

    override suspend fun record(entry: NetworkLogEntity) {
        try {
            dao.insert(entry)
            val now = clock.millis()
            if (now - lastPruneMs > DAY_MS) {
                lastPruneMs = now
                dao.deleteBefore(now - RETENTION_MS)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Best effort: a full disk must not break the request being logged.
        }
    }

    companion object {
        private const val DAY_MS = 24 * 3_600_000L
        const val RETENTION_MS = 400 * DAY_MS
    }
}

/**
 * The app's single door to the user's Orbit Server (for Agent & sync and Ask). Every call goes over TLS in the paired
 * trust mode (pinned to the paired fingerprint via [pinnedHttp], or public CA + exact host via [caHttp]; never one
 * for the other) and is written to the network log. Failures are [ServerException]s:
 * [ServerIdentityChanged] → "Server identity changed — re-pair"; [ServerUnauthorized] → the phone was removed on the
 * server; [NoModel] → "Choose a model in your Orbit Server dashboard"; [ServerUnreachable], [RateLimited], ...
 */
class ServerRepository(
    private val store: ServerStore,
    private val sync: ServerSync,
    private val pinnedHttp: (fingerprint: String) -> OkHttpClient,
    private val netLog: NetworkRecorder,
    private val networkLogDao: NetworkLogDao?,
    private val deviceName: () -> String,
    private val clock: Clock = Clock.systemUTC(),
    private val zone: () -> String = { ZoneId.systemDefault().id },
    /** Where the best-effort revoke of a replaced pairing runs; null runs it before [pair] returns (tests). */
    private val background: CoroutineScope? = null,
    /** The public-CA client for a server URL ([PublicCaTls.client]); used only for [ServerTrustMode.PUBLIC_CA]. */
    private val caHttp: (url: String) -> OkHttpClient = { PublicCaTls.client(it) },
) {
    /** The paired server, or null. (Loaded from storage on first use of the repository.) */
    val pairedServer: Flow<ServerInfo?> = store.paired.map { it?.info() }.distinctUntilChanged()

    /** The latest push's outcome since the process started; null before the first. */
    val lastSyncResult: StateFlow<ServerSyncResult?> = sync.lastResult

    /** Loads the stored pairing (so [pairedServer] reflects it) and returns it. */
    suspend fun current(): ServerInfo? = store.current()?.info()

    /**
     * Pairs with the server in [link] (`orbit://pair?...`), replacing any earlier pairing, then pushes data in the
     * background. With [scopes], the new device is narrowed to them on the server *before* anything is stored, so the
     * phone never holds an all-scopes pairing the user did not choose. Throws [InvalidPairingLink], [PairingRejected],
     * [ServerIdentityChanged] or [ServerUnreachable]; if narrowing fails, the new device token is revoked (best effort),
     * nothing is stored and that failure is thrown. The same happens if the caller is cancelled after the link was
     * redeemed (the revoke still goes out, then the cancellation is rethrown). Once the new pairing is stored, the one
     * it replaces is revoked on its server, best effort.
     */
    suspend fun pair(link: String, scopes: Set<Scope>? = null): ServerInfo {
        // A pin link without `fp`, a non-https URL or an unknown `m` does not parse.
        val uri = PairingUri.parse(link.trim()) ?: throw InvalidPairingLink()
        // A ca link's optional `fp` is not used: public-CA pairings keep no fingerprint.
        val mode = if (uri.mode == TrustMode.CA) ServerTrustMode.PUBLIC_CA else ServerTrustMode.PIN
        val fp = if (mode == ServerTrustMode.PIN) uri.fingerprint else null
        val http = try {
            serverHttpFor(mode, fp, uri.url, pinnedHttp, caHttp)
        } catch (e: IllegalArgumentException) {
            throw InvalidPairingLink()
        }
        val previous = store.current()
        val redeemed = ServerClient.pair(uri, deviceName().ifBlank { "Phone" }.take(64), http, netLog, clock)
        val wanted = scopes?.map { it.wire }?.toSet() ?: redeemed.scopes
        var server = redeemed.copy(wantedScopes = wanted)
        try {
            if (wanted != redeemed.scopes) server = server.copy(scopes = client(server).setScopes(wanted))
            store.save(server)
        } catch (e: Throwable) {
            // Includes cancellation: the redeemed token was never stored, so this phone cannot use it; ask the server
            // to drop it too, even while being cancelled.
            withContext(NonCancellable) { revokeQuietly(server) }
            throw e
        }
        sync.reset()
        sync.afterRingSync()
        if (previous != null && previous.deviceToken != server.deviceToken) {
            // The replaced pairing should not stay valid on its server.
            background?.launch { revokeQuietly(previous) } ?: withContext(NonCancellable) { revokeQuietly(previous) }
        }
        return server.info()
    }

    /** Best-effort `DELETE /v1/devices/me` for [server], bounded in time; never throws. */
    private suspend fun revokeQuietly(server: PairedServer) {
        try {
            withTimeout(REVOKE_TIMEOUT_MS) { client(server).revoke() }
        } catch (_: Exception) {
            // Unreachable or already revoked: nothing more this phone can do.
        }
    }

    /**
     * Forgets the server. The phone first asks the server to revoke its token (best effort: an unreachable or
     * already-revoked server still gets forgotten). Returns true if the server confirmed the revocation.
     */
    suspend fun unpair(): Boolean {
        val server = store.current() ?: return false
        val revoked = try {
            client(server).revoke()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e is ServerUnauthorized // already revoked counts as done
        }
        store.clear()
        sync.reset()
        return revoked
    }

    /** Asks the server's agent; [history] is earlier turns of this conversation. Sends the phone's time zone. */
    suspend fun ask(question: String, history: List<ChatTurn> = emptyList()): AskResponse =
        client(requirePaired()).ask(AskRequest(question, history, zone()))

    /** Who read what on the server (newest first). */
    suspend fun accessLog(limit: Int = 100): List<AccessLogEntry> = client(requirePaired()).accessLog(limit)

    /**
     * Sets which data groups the server may receive and agents may read. Narrowing takes effect on this phone at
     * once, even if the server cannot be reached (fail closed); widening only once the server confirms it.
     */
    suspend fun setScopes(scopes: Set<Scope>): Set<Scope> {
        val wanted = scopes.map { it.wire }.toSet()
        var server = requirePaired()
        val narrowed = server.wantedScopes intersect wanted
        if (narrowed != server.wantedScopes) {
            server = server.copy(wantedScopes = narrowed)
            store.save(server)
        }
        val granted = client(server).setScopes(wanted)
        server = server.copy(scopes = granted, wantedScopes = wanted)
        store.save(server)
        return server.effectiveScopes.mapNotNull(Scope::fromWire).toSet()
    }

    /** Pushes now (e.g. a "Sync now" button). Null when not paired or a push is already running. */
    suspend fun syncNow(): ServerSyncResult? = sync.syncNow()

    /** Newest network-log rows. */
    fun networkLog(limit: Int = 200): Flow<List<NetworkLogEntity>> =
        networkLogDao?.recent(limit) ?: kotlinx.coroutines.flow.flowOf(emptyList())

    /** Bytes sent and received since [fromMs]. */
    fun bytesSince(fromMs: Long): Flow<Long> = networkLogDao?.bytesSince(fromMs) ?: kotlinx.coroutines.flow.flowOf(0L)

    /** Bytes this phone sent since [fromMs] ("Sent to your server this month"). */
    fun bytesSentSince(fromMs: Long): Flow<Long> = networkLogDao?.bytesUpSince(fromMs) ?: kotlinx.coroutines.flow.flowOf(0L)

    private suspend fun requirePaired(): PairedServer = store.current() ?: throw NotPaired()

    private fun client(server: PairedServer) = ServerClient(server, http(server), netLog, clock)

    private fun http(server: PairedServer): OkHttpClient = serverHttpFor(server, pinnedHttp, caHttp)

    private companion object {
        const val REVOKE_TIMEOUT_MS = 15_000L
    }
}
