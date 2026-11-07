package dev.avijit.orbithealth.server

import dev.avijit.orbithealth.data.NetworkLogEntity
import dev.avijit.orbithealth.sync.AccessLogEntry
import dev.avijit.orbithealth.sync.AskRequest
import dev.avijit.orbithealth.sync.AskResponse
import dev.avijit.orbithealth.sync.IngestBatch
import dev.avijit.orbithealth.sync.IngestResult
import dev.avijit.orbithealth.sync.PairRequest
import dev.avijit.orbithealth.sync.PairResponse
import dev.avijit.orbithealth.sync.PairingUri
import dev.avijit.orbithealth.sync.ScopesUpdate
import dev.avijit.orbithealth.sync.StateResponse
import dev.avijit.orbithealth.sync.TrustMode
import java.io.IOException
import java.time.Clock
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.EventListener
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/** What the phone keeps about its paired server. Stored only encrypted ([ServerStore]); [toString] hides the token. */
@Serializable
data class PairedServer(
    val url: String,
    /** The pinned leaf's SHA-256 in [ServerTrustMode.PIN]; null (not stored) in [ServerTrustMode.PUBLIC_CA]. */
    val fingerprint: String?,
    val deviceToken: String,
    val deviceId: String,
    val serverName: String,
    /** Scope wire names the server grants this phone ("heart", "sleep", ...), as it last reported them. */
    val scopes: Set<String>,
    /**
     * Scopes the user chose on this phone. The phone never sends anything outside them, whatever the server echoes:
     * the effective set is [effectiveScopes].
     */
    val wantedScopes: Set<String> = scopes,
    /** Absent in records stored before public-CA pairing existed: those are pin-mode. */
    val trustMode: ServerTrustMode = ServerTrustMode.PIN,
) {
    init {
        require(trustMode != ServerTrustMode.PIN || fingerprint != null) { "a pinned pairing needs a fingerprint" }
        require(trustMode != ServerTrustMode.PUBLIC_CA || fingerprint == null) { "a public-CA pairing keeps no fingerprint" }
    }

    /** What the phone may send and show: granted by the server AND chosen by the user. */
    val effectiveScopes: Set<String> get() = scopes intersect wantedScopes

    override fun toString() =
        "PairedServer(url=$url, trustMode=$trustMode, fingerprint=$fingerprint, deviceId=$deviceId, serverName=$serverName, scopes=$scopes, wanted=$wantedScopes)"
}

/** Records one row per server request (Data & privacy: "Sent to the internet"). Must not throw. */
fun interface NetworkRecorder {
    suspend fun record(entry: NetworkLogEntity)
}

/**
 * HTTP client for the paired Orbit Server. [http] must match [server]'s trust mode: pinned to its fingerprint
 * ([PinnedTls.client]) or, for [ServerTrustMode.PUBLIC_CA], bound to its host over the system trust store
 * ([PublicCaTls.client]).
 * Every request, successful or not, is written to [netLog] (host, purpose, bytes up/down, ok). Byte counts are the
 * HTTP headers and bodies as this client sees them; TLS and TCP overhead are not included. A request that never got
 * as far as writing its headers (refused connection, failed or mismatched TLS) counts 0 bytes up.
 */
class ServerClient(
    private val server: PairedServer,
    http: OkHttpClient,
    netLog: NetworkRecorder,
    clock: Clock = Clock.systemUTC(),
) {
    private val api = Http(server.url, server.fingerprint, http, netLog, clock)
    private val token = server.deviceToken

    suspend fun state(): StateResponse = api.call("state", "GET", "v1/state", token, null, StateResponse.serializer())

    suspend fun ingest(batch: IngestBatch): IngestResult =
        api.call("ingest", "POST", "v1/ingest", token, json.encodeToString(IngestBatch.serializer(), batch), IngestResult.serializer())

    /** The agent can take a while (several model calls): a longer timeout than other requests. */
    suspend fun ask(req: AskRequest): AskResponse =
        api.call("ask", "POST", "v1/agent/ask", token, json.encodeToString(AskRequest.serializer(), req), AskResponse.serializer(), ASK_TIMEOUT_S)

    suspend fun accessLog(limit: Int = 100): List<AccessLogEntry> =
        api.call("access_log", "GET", "v1/access-log?limit=${limit.coerceIn(1, 500)}", token, null, ListSerializer(AccessLogEntry.serializer()))

    /** Replaces this phone's scopes on the server; returns the scopes the server now grants. */
    suspend fun setScopes(scopes: Set<String>): Set<String> =
        api.call("scopes", "PATCH", "v1/devices/me/scopes", token, json.encodeToString(ScopesUpdate.serializer(), ScopesUpdate(scopes)), ScopesUpdate.serializer()).scopes

    /** Revokes this phone's device token on the server (`DELETE /v1/devices/me`). */
    suspend fun revoke() {
        api.call<Unit>("revoke", "DELETE", "v1/devices/me", token, null, null)
    }

    companion object {
        internal val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }
        private const val ASK_TIMEOUT_S = 180L

        /**
         * Redeems [link] for a device token over the link's trust mode. For a pin-mode link [http] must be pinned to
         * `link.fingerprint`; for a ca-mode link it must be [PublicCaTls.client] for `link.url` (any `fp` in a ca
         * link is ignored and not kept). Either way the token is only ever sent to the server the QR code named.
         */
        suspend fun pair(link: PairingUri, deviceName: String, http: OkHttpClient, netLog: NetworkRecorder, clock: Clock = Clock.systemUTC()): PairedServer {
            val (mode, pin) = when (link.mode) {
                TrustMode.PIN -> ServerTrustMode.PIN to requireNotNull(link.fingerprint) { "a pin-mode link needs a fingerprint" }
                TrustMode.CA -> ServerTrustMode.PUBLIC_CA to null
            }
            val api = Http(link.url, pin, http, netLog, clock)
            val body = json.encodeToString(PairRequest.serializer(), PairRequest(link.token, deviceName))
            val r = api.call("pair", "POST", "v1/pair", null, body, PairResponse.serializer())
            return PairedServer(link.url, pin, r.deviceToken, r.deviceId, r.serverName, r.scopes, trustMode = mode)
        }
    }
}

@Serializable private class ErrorBody(val error: String? = null)

/** One server base URL: builds requests, runs them, maps failures to [ServerException]s and logs every call. */
private class Http(
    baseUrl: String,
    /** The pinned fingerprint, or null in public-CA mode (where a TLS failure is just [ServerUnreachable]). */
    private val pin: String?,
    private val http: OkHttpClient,
    private val netLog: NetworkRecorder,
    private val clock: Clock,
) {
    private val base: HttpUrl = baseUrl.toHttpUrlOrNull()?.takeIf { it.isHttps } ?: throw InvalidPairingLink()

    suspend fun <T> call(
        purpose: String, method: String, path: String, token: String?, body: String?, out: KSerializer<T>?, timeoutS: Long? = null,
    ): T {
        val url = base.newBuilder().addEncodedPathSegments(path.substringBefore('?')).apply {
            path.substringAfter('?', "").takeIf { it.isNotEmpty() }?.let { encodedQuery(it) }
        }.build()
        val bodyBytes = body?.toByteArray(Charsets.UTF_8)
        val request = Request.Builder().url(url)
            .header("Accept", "application/json")
            .apply { token?.let { header("Authorization", "Bearer $it") } }
            .method(method, bodyBytes?.toRequestBody(JSON))
            .build()
        // Notes whether any of the request was written: a connect or TLS failure (wrong pin) sends nothing.
        val sent = SentListener()
        val client = http.newBuilder().eventListener(sent)
            .apply { timeoutS?.let { readTimeout(it, TimeUnit.SECONDS).callTimeout(it, TimeUnit.SECONDS) } }
            .build()
        val up = request.headers.byteCount() + (bodyBytes?.size ?: 0)
        var down = 0L
        var ok = false
        try {
            val (status, text, headerBytes) = try {
                client.newCall(request).await().use { r -> Triple(r.code, r.readCapped(), r.headers.byteCount()) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ServerException) {
                throw e
            } catch (e: IOException) {
                throw pin?.let { PinnedTls.identityError(e, it) } ?: ServerUnreachable(e)
            }
            down = headerBytes + text.toByteArray(Charsets.UTF_8).size
            if (status !in 200..299) throw errorFor(purpose, status, text)
            @Suppress("UNCHECKED_CAST")
            val result = if (out == null) Unit as T else try {
                ServerClient.json.decodeFromString(out, text)
            } catch (e: Exception) {
                throw ServerHttpError(status, "bad_response")
            }
            ok = true
            return result
        } finally {
            val entry = NetworkLogEntity(
                atMs = clock.millis(), host = base.host, purpose = purpose, bytesUp = if (sent.started) up else 0L, bytesDown = down, ok = ok,
            )
            try {
                withContext(kotlinx.coroutines.NonCancellable) { netLog.record(entry) }
            } catch (_: Exception) {
                // The log is best effort; it never turns a server answer into a failure.
            }
        }
    }

    private fun errorFor(purpose: String, status: Int, text: String): ServerException {
        val code = try {
            ServerClient.json.decodeFromString(ErrorBody.serializer(), text).error
        } catch (e: Exception) {
            null
        }
        return when {
            status == 401 && purpose == "pair" -> PairingRejected()
            status == 401 -> ServerUnauthorized()
            status == 409 && code == "no_model" -> NoModel()
            status == 502 && code == "model_error" -> ModelError()
            status == 413 && purpose == "ask" -> AskTooLarge()
            status == 429 -> RateLimited()
            else -> ServerHttpError(status, code)
        }
    }

    /** Reads the body as UTF-8, refusing anything over [MAX_RESPONSE_BYTES] (no server answer is that large). */
    private suspend fun Response.readCapped(): String = withContext(Dispatchers.IO) {
        val source = body?.source() ?: return@withContext ""
        if (source.request(MAX_RESPONSE_BYTES + 1)) throw ServerHttpError(code, "response_too_large")
        source.buffer.readUtf8()
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private const val MAX_RESPONSE_BYTES = 4L * 1024 * 1024
    }
}

/** Records whether the request's headers started going out on the connection. */
private class SentListener : EventListener() {
    @Volatile var started = false
        private set

    override fun requestHeadersStart(call: Call) {
        started = true
    }
}

/** Runs the call on OkHttp's dispatcher; cancelling the coroutine cancels the call. */
private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) = cont.resume(response) { response.close() }
        override fun onFailure(call: Call, e: IOException) = cont.resumeWithException(e)
    })
}
