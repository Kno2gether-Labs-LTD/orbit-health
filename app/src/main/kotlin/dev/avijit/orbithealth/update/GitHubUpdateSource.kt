package dev.avijit.orbithealth.update

import dev.avijit.orbithealth.data.NetworkLogEntity
import dev.avijit.orbithealth.server.NetworkRecorder
import java.io.File
import java.io.IOException
import java.net.Proxy
import java.security.MessageDigest
import java.time.Clock
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.ConnectionSpec
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * GitHub Releases over HTTPS with the system trust store: the latest release's JSON, its `.sha256` and its APK. No
 * account, token, cookie or device identifier is sent: only `User-Agent: OrbitHealth/<version>` and the Accept header
 * GitHub asks for. Every request (and every redirect hop) must be HTTPS to a GitHub host in [allowedHosts]; a redirect
 * from HTTPS to HTTP is never followed. Each request is written to [netLog] like every other network call the app
 * makes ("update_check", "update_checksum", "update_download").
 */
class GitHubUpdateSource(
    http: OkHttpClient,
    private val netLog: NetworkRecorder,
    private val userAgent: String,
    private val downloadDir: File,
    private val latestUrl: String = LATEST_URL,
    private val allowedHosts: (String) -> Boolean = ::isGitHubHost,
    private val clock: Clock = Clock.systemUTC(),
) : ReleaseSource, ApkDownloader {
    private val http = http.newBuilder()
        .followRedirects(true)
        .followSslRedirects(false)
        .addNetworkInterceptor(GuardHosts(allowedHosts))
        .build()

    override suspend fun latest(): ReleaseInfo? {
        val text = get("update_check", latestUrl, MAX_JSON_BYTES, accept = "application/vnd.github+json") { r ->
            if (r.code == 404) null // no published release yet
            else String(r.readCapped(MAX_JSON_BYTES), Charsets.UTF_8)
        }
        return text?.let(ReleaseParser::parse)
    }

    override suspend fun download(release: ReleaseInfo, onProgress: (Float?) -> Unit): File {
        val expected = get("update_checksum", release.sha256Url, MAX_SHA_BYTES) { r ->
            Sha256.parseExpected(String(r.readCapped(MAX_SHA_BYTES), Charsets.UTF_8))
        } ?: throw UpdateFailure(BAD_CHECKSUM_FILE)
        downloadDir.mkdirs()
        downloadDir.listFiles()?.forEach { it.delete() } // only ever the one APK being installed
        val dest = File(downloadDir, "OrbitHealth-${release.version}.apk")
        val actual = try {
            get("update_download", release.apkUrl, MAX_APK_BYTES, accept = "application/octet-stream") { r ->
                writeTo(r, dest, release.apkSize, onProgress)
            }
        } catch (e: Throwable) {
            dest.delete()
            throw e
        }
        if (actual != expected) {
            dest.delete()
            throw UpdateFailure(CHECKSUM_MISMATCH)
        }
        return dest
    }

    /** Streams the body into [dest] (capped), reporting progress; returns the SHA-256 of what was written. */
    private suspend fun writeTo(r: Response, dest: File, expectedSize: Long?, onProgress: (Float?) -> Unit): String {
        val body = r.body ?: throw IOException("empty body")
        val total = body.contentLength().takeIf { it > 0 } ?: expectedSize
        if (total != null && total > MAX_APK_BYTES) throw UpdateFailure(TOO_LARGE)
        val md = MessageDigest.getInstance("SHA-256")
        var done = 0L
        var lastReported = -1
        body.byteStream().use { input ->
            dest.outputStream().buffered().use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    kotlin.coroutines.coroutineContext.ensureActive()
                    val n = input.read(buf)
                    if (n < 0) break
                    done += n
                    if (done > MAX_APK_BYTES) throw UpdateFailure(TOO_LARGE)
                    md.update(buf, 0, n)
                    out.write(buf, 0, n)
                    val pct = total?.let { (done * 100 / it).toInt().coerceIn(0, 100) } ?: -1
                    if (pct != lastReported) {
                        lastReported = pct
                        onProgress(total?.let { pct / 100f })
                    }
                }
            }
        }
        if (expectedSize != null && done != expectedSize) throw IOException("size mismatch")
        return Sha256.hex(md.digest())
    }

    /** One logged GET; [read] turns a 2xx (or a 404, for [latest]) into a value. */
    private suspend fun <T> get(purpose: String, url: String, cap: Long, accept: String? = null, read: suspend (Response) -> T): T {
        val parsed = url.toHttpUrlOrNull()?.takeIf { it.isHttps && allowedHosts(it.host) } ?: throw UpdateFailure(BAD_URL)
        val request = Request.Builder().url(parsed)
            .header("User-Agent", userAgent)
            .apply { accept?.let { header("Accept", it) } }
            .apply { if (purpose == "update_check") header("X-GitHub-Api-Version", "2022-11-28") }
            .build()
        var up = 0L
        var down = 0L
        var ok = false
        try {
            return withContext(Dispatchers.IO) {
                http.newCall(request).execute().use { r ->
                    up = request.headers.byteCount()
                    down = r.headers.byteCount()
                    if (!r.isSuccessful && !(purpose == "update_check" && r.code == 404)) throw IOException("HTTP ${r.code}")
                    val value = read(r)
                    down += (r.body?.contentLength()?.takeIf { it >= 0 } ?: 0L).coerceAtMost(cap)
                    ok = true
                    value
                }
            }
        } catch (e: CancellationException) {
            throw e
        } finally {
            val entry = NetworkLogEntity(atMs = clock.millis(), host = parsed.host, purpose = purpose, bytesUp = up, bytesDown = down, ok = ok)
            try {
                withContext(NonCancellable) { netLog.record(entry) }
            } catch (_: Exception) {
                // Best effort, as for server calls.
            }
        }
    }

    private fun Response.readCapped(cap: Long): ByteArray {
        val source = body?.source() ?: return ByteArray(0)
        if (source.request(cap + 1)) throw IOException("response too large")
        return source.buffer.readByteArray()
    }

    /** Refuses any hop (including redirects) that isn't HTTPS to an allowed host. */
    private class GuardHosts(private val allowed: (String) -> Boolean) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val url = chain.request().url
            if (!url.isHttps || !allowed(url.host)) throw IOException("refusing ${url.scheme}://${url.host}")
            return chain.proceed(chain.request())
        }
    }

    companion object {
        const val REPO = "Kno2gether-Labs-LTD/orbit-health"
        const val LATEST_URL = "https://api.github.com/repos/$REPO/releases/latest"
        const val RELEASES_PAGE = "https://github.com/$REPO/releases"
        private const val MAX_JSON_BYTES = 1L * 1024 * 1024
        private const val MAX_SHA_BYTES = 4L * 1024
        const val MAX_APK_BYTES = 200L * 1024 * 1024

        const val CHECKSUM_MISMATCH = "The download didn't match its published checksum, so it wasn't installed."
        const val BAD_CHECKSUM_FILE = "The release's checksum file couldn't be read, so nothing was installed."
        const val TOO_LARGE = "The download is far larger than Orbit Health should be, so it was stopped."
        const val BAD_URL = "The release points somewhere other than GitHub, so nothing was downloaded."

        /** GitHub's API, site and the hosts it redirects release downloads to. */
        fun isGitHubHost(host: String): Boolean {
            val h = host.lowercase()
            return h == "api.github.com" || h == "github.com" || h.endsWith(".githubusercontent.com")
        }

        /** HTTPS with modern TLS and the system trust store; no proxy, so nothing in between sees the request. */
        fun client(): OkHttpClient = OkHttpClient.Builder()
            .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS))
            .proxy(Proxy.NO_PROXY)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
