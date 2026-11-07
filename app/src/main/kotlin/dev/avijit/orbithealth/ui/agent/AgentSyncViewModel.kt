package dev.avijit.orbithealth.ui.agent

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.avijit.orbithealth.server.InvalidPairingLink
import dev.avijit.orbithealth.server.ModelError
import dev.avijit.orbithealth.server.NoModel
import dev.avijit.orbithealth.server.NotPaired
import dev.avijit.orbithealth.server.PairingRejected
import dev.avijit.orbithealth.server.RateLimited
import dev.avijit.orbithealth.server.ServerIdentityChanged
import dev.avijit.orbithealth.server.ServerInfo
import dev.avijit.orbithealth.server.ServerSyncResult
import dev.avijit.orbithealth.server.ServerTrustMode
import dev.avijit.orbithealth.server.ServerUnauthorized
import dev.avijit.orbithealth.server.ServerUnreachable
import dev.avijit.orbithealth.sync.AccessLogEntry
import dev.avijit.orbithealth.sync.PairingUri
import dev.avijit.orbithealth.sync.TrustMode
import dev.avijit.orbithealth.sync.Scope
import dev.avijit.orbithealth.ui.me.MeMath
import dev.avijit.orbithealth.ui.today.minuteTicks
import java.text.NumberFormat
import java.time.Clock
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What Agent & sync can do; backed by [dev.avijit.orbithealth.server.ServerRepository] in the app, faked in tests. */
data class AgentSyncDeps(
    val paired: Flow<ServerInfo?>,
    /** The latest push's outcome (kept across restarts). */
    val lastSync: Flow<ServerSyncResult?>,
    /** Loads the stored pairing so [paired] reflects it. */
    val load: suspend () -> ServerInfo?,
    val pair: suspend (link: String, scopes: Set<Scope>) -> ServerInfo,
    /** True when the server confirmed the revocation. */
    val unpair: suspend () -> Boolean,
    val setScopes: suspend (Set<Scope>) -> Set<Scope>,
    val accessLog: suspend () -> List<AccessLogEntry>,
    val syncNow: suspend () -> ServerSyncResult?,
    val clock: Clock,
    val now: Flow<Instant> = minuteTicks(clock),
)

/** The words Agent & sync (and Ask) use for server failures. */
object AgentSyncCopy {
    const val IDENTITY = "Server identity changed — re-pair"
    /** Privacy copy: what goes with every push and question besides the shared groups. */
    const val ZONE_NOTE = "Your phone's time zone (for example Europe/Berlin) goes with each push and each question, " +
        "so \"today\" on your server matches yours."
    const val UNAUTHORIZED = "This phone was removed from your server — pair again"
    const val INVALID_LINK = "That isn't an Orbit Server pairing code. Open Pair a phone in your server's dashboard and scan the code there."
    const val REJECTED = "That pairing code has been used or has expired. Make a new one on your server and scan it again."
    const val UNREACHABLE = "Can't reach your Orbit Server. Check that it's running and that this phone is on the same network."
    const val RATE_LIMITED = "Your server asked us to slow down. Try again in a minute."
    const val NO_MODEL = "Choose a model in your Orbit Server dashboard"
    const val MODEL_ERROR = "Your server's model couldn't answer. Try again in a moment."
    const val NOT_PAIRED = "No Orbit Server is paired with this phone."
    const val OTHER = "Something went wrong talking to your server. Try again."
    const val REMOVED_LOCALLY = "Removed from this phone. Your server couldn't confirm it, so remove this phone in its dashboard too."
    const val NOTHING_SHARED = "Nothing would be shared. Switch on at least one kind of data to pair your server."
    const val CAMERA_DENIED = "Orbit Health needs the camera only to scan your server's pairing code. You can allow it in Settings."

    const val SECURED_PINNED = "Paired · pinned certificate"
    const val SECURED_PUBLIC_CA = "Secured · public certificate"

    /** The "Secured" status row: a pending repair, else how this phone trusts the server's certificate. */
    fun secured(repair: String?, trust: ServerTrustMode): String = when (repair) {
        IDENTITY -> "Certificate changed · not trusted"
        UNAUTHORIZED -> "Removed on the server"
        else -> when (trust) {
            ServerTrustMode.PIN -> SECURED_PINNED
            ServerTrustMode.PUBLIC_CA -> SECURED_PUBLIC_CA
        }
    }

    /** [UNREACHABLE] for a public-certificate server: it is on the internet, so check it is up and its certificate. */
    fun unreachableCa(host: String) =
        "Can't reach your Orbit Server. Check that it's online and its certificate is valid for $host."

    /** [caHost] is the paired (or being-paired) server's host when it uses a public certificate, else null. */
    fun of(e: Throwable, caHost: String? = null): String = when (e) {
        is ServerIdentityChanged -> IDENTITY
        is ServerUnauthorized -> UNAUTHORIZED
        is InvalidPairingLink -> INVALID_LINK
        is PairingRejected -> REJECTED
        is ServerUnreachable -> caHost?.let(::unreachableCa) ?: UNREACHABLE
        is RateLimited -> RATE_LIMITED
        is NoModel -> NO_MODEL
        is ModelError -> MODEL_ERROR
        is NotPaired -> NOT_PAIRED
        else -> OTHER
    }

    /** For a stored push result's [ServerSyncResult.kind]. */
    fun ofKind(kind: String?, caHost: String? = null): String = when (kind) {
        ServerSyncResult.IDENTITY -> IDENTITY
        ServerSyncResult.UNAUTHORIZED -> UNAUTHORIZED
        ServerSyncResult.UNREACHABLE -> caHost?.let(::unreachableCa) ?: UNREACHABLE
        ServerSyncResult.RATE_LIMITED -> RATE_LIMITED
        else -> OTHER
    }
}

/** This server's host when it is a public-certificate pairing (for failure copy), else null. */
internal fun ServerInfo.caHost(): String? = if (trustMode == ServerTrustMode.PUBLIC_CA) hostOf(url) else null

enum class AgentBusy { PAIRING, REMOVING, SYNCING }

/** One server-reported access: who, what (action · detail · rows), when. */
data class AccessRow(val who: String, val what: String, val time: String)

sealed interface AccessLogUi {
    data object Hidden : AccessLogUi
    data object Loading : AccessLogUi
    data class Loaded(val rows: List<AccessRow>) : AccessLogUi
    data class Failed(val message: String) : AccessLogUi
}

data class AgentSyncUiState(
    val loaded: Boolean = false,
    val server: ServerInfo? = null,
    /** The switches: the user's picks before pairing, then what this phone actually shares (or a change on its way). */
    val scopes: Set<Scope> = Scope.entries.toSet(),
    val busy: AgentBusy? = null,
    /** The last action's failure, in plain words. */
    val notice: String? = null,
    /** Set when the latest push found the server's identity changed or this phone removed: pair again. */
    val repair: String? = null,
    val lastSync: String = "Not yet",
    val lastSyncCaption: String? = null,
    val accessLog: AccessLogUi = AccessLogUi.Hidden,
) {
    /** Pairing needs at least one data group to share. */
    val canPair: Boolean get() = server == null && scopes.isNotEmpty()
}

/**
 * Agent & sync: pair an Orbit Server (QR or, in debug builds, a pasted link) with the data groups to share, then its
 * status, last push, scopes (applied on the server at once), Remove, and the server-reported access log.
 */
class AgentSyncViewModel(private val deps: AgentSyncDeps, scope: CoroutineScope? = null) : ViewModel() {
    private val scope: CoroutineScope = scope ?: viewModelScope

    private data class Local(
        val loaded: Boolean = false,
        val draft: Set<Scope> = Scope.entries.toSet(),
        val pending: Set<Scope>? = null,
        val busy: AgentBusy? = null,
        val notice: String? = null,
        val accessLog: AccessLogUi = AccessLogUi.Hidden,
    )

    private val local = MutableStateFlow(Local())
    @Volatile private var latestServer: ServerInfo? = null
    private val scopeLock = Mutex()
    private val nf = NumberFormat.getIntegerInstance(java.util.Locale.UK)

    val state: StateFlow<AgentSyncUiState> =
        combine(deps.paired, deps.lastSync, deps.now, local) { server, last, now, l -> render(server, last, now, l) }
            .stateIn(this.scope, SharingStarted.Eagerly, AgentSyncUiState())

    init {
        this.scope.launch {
            val server = try {
                deps.load()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            local.update { it.copy(loaded = true) }
            if (server != null) loadAccessLog(server)
        }
        this.scope.launch { deps.paired.collect { latestServer = it } }
        // A new pairing (or a removal) refreshes the log.
        this.scope.launch {
            var first = true
            deps.paired.distinctUntilChangedBy { it?.deviceId }.collect { paired ->
                if (first) { first = false; return@collect }
                if (paired != null) loadAccessLog(paired) else local.update { it.copy(accessLog = AccessLogUi.Hidden) }
            }
        }
    }

    private fun render(server: ServerInfo?, last: ServerSyncResult?, now: Instant, l: Local): AgentSyncUiState {
        val (lastSync, caption) = when {
            last == null -> "Not yet" to null
            last.ok -> {
                val sent = last.samplesSent + last.sleepSent
                MeMath.ago(Instant.ofEpochMilli(last.atMs), now).replaceFirstChar { it.uppercase() } to
                    (if (sent > 0) "${nf.format(sent)} ${if (sent == 1) "reading" else "readings"} sent" else "Up to date")
            }
            else -> "Failed ${MeMath.ago(Instant.ofEpochMilli(last.atMs), now)}" to AgentSyncCopy.ofKind(last.kind, server?.caHost())
        }
        return AgentSyncUiState(
            loaded = l.loaded,
            server = server,
            scopes = if (server == null) l.draft else l.pending ?: server.scopes,
            busy = l.busy,
            notice = l.notice,
            repair = if (server != null && last?.needsRepair == true) AgentSyncCopy.ofKind(last.kind, server.caHost()) else null,
            lastSync = if (server == null) "Not yet" else lastSync,
            lastSyncCaption = if (server == null) null else caption,
            accessLog = if (server == null) AccessLogUi.Hidden else l.accessLog,
        )
    }

    /** A scanned (or pasted) code; null or blank means the scan was cancelled. */
    fun onScanned(raw: String?) {
        if (raw.isNullOrBlank()) return
        pair(raw)
    }

    fun pair(link: String) {
        if (local.value.busy != null) return
        if (local.value.draft.isEmpty()) {
            local.update { it.copy(notice = AgentSyncCopy.NOTHING_SHARED) }
            return
        }
        local.update { it.copy(busy = AgentBusy.PAIRING, notice = null) }
        scope.launch {
            val error = attempt(caHostOfLink(link)) { deps.pair(link.trim(), local.value.draft) }
            local.update { it.copy(busy = null, notice = error) }
        }
    }

    /** Before pairing this edits the choice; once paired it is applied on the server (narrowing first on this phone). */
    fun toggleScope(scope: Scope, on: Boolean) {
        val server = latestServer
        if (server == null) {
            local.update { it.copy(draft = if (on) it.draft + scope else it.draft - scope) }
            return
        }
        // From the latest choice (a change still on its way counts), so quick taps build on each other.
        var wanted: Set<Scope> = emptySet()
        local.update { l ->
            val base = l.pending ?: server.scopes
            wanted = if (on) base + scope else base - scope
            l.copy(pending = wanted, notice = null)
        }
        this.scope.launch {
            // One update at a time, in tap order, so the server ends with the last choice.
            val error = scopeLock.withLock { attempt { deps.setScopes(wanted) } }
            // Whatever happened, show what the phone now holds (the paired flow), not the request.
            local.update { if (it.pending == wanted) it.copy(pending = null, notice = error) else it.copy(notice = error) }
        }
    }

    fun syncNow() {
        if (local.value.busy != null) return
        local.update { it.copy(busy = AgentBusy.SYNCING, notice = null) }
        scope.launch {
            val error = attempt { deps.syncNow() }
            local.update { it.copy(busy = null, notice = error) }
        }
    }

    fun remove() {
        if (local.value.busy != null) return
        local.update { it.copy(busy = AgentBusy.REMOVING, notice = null) }
        scope.launch {
            var confirmed = true
            val error = attempt { confirmed = deps.unpair() }
            local.update {
                it.copy(
                    busy = null, pending = null, accessLog = AccessLogUi.Hidden,
                    notice = error ?: if (confirmed) null else AgentSyncCopy.REMOVED_LOCALLY,
                )
            }
        }
    }

    fun refreshAccessLog() {
        scope.launch { loadAccessLog() }
    }

    fun showNotice(message: String) = local.update { it.copy(notice = message) }

    fun dismissNotice() = local.update { it.copy(notice = null) }

    /** [server] is the paired server the log belongs to (for its failure copy). */
    private suspend fun loadAccessLog(server: ServerInfo? = latestServer) {
        local.update { it.copy(accessLog = AccessLogUi.Loading) }
        val next = try {
            val now = deps.clock.instant()
            AccessLogUi.Loaded(deps.accessLog().take(MAX_LOG_ROWS).map { row(it, now) })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AccessLogUi.Failed(AgentSyncCopy.of(e, server?.caHost()))
        }
        local.update { it.copy(accessLog = next) }
    }

    private fun row(e: AccessLogEntry, now: Instant): AccessRow {
        val rows = "${nf.format(e.rows)} ${if (e.rows == 1) "row" else "rows"}"
        return AccessRow(e.actor, AccessLogCopy.what(e.action, e.detail, rows, deps.clock.zone), MeMath.ago(Instant.ofEpochMilli(e.atMs), now).replaceFirstChar { it.uppercase() })
    }

    /** Runs [block]; returns the failure's message (null on success). Cancellation propagates. */
    private suspend fun attempt(caHost: String? = latestServer?.caHost(), block: suspend () -> Unit): String? = try {
        block()
        null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        AgentSyncCopy.of(e, caHost)
    }

    /** The host of a public-certificate (`m=ca`) pairing link, for its failure copy; null for any other link. */
    private fun caHostOfLink(link: String): String? =
        PairingUri.parse(link.trim())?.takeIf { it.mode == TrustMode.CA }?.let { hostOf(it.url) }

    companion object {
        const val MAX_LOG_ROWS = 30
    }
}
