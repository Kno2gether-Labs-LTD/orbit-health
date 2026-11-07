package dev.avijit.orbithealth.server

import android.annotation.SuppressLint
import androidx.annotation.VisibleForTesting
import java.io.IOException
import java.net.Proxy
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.Certificate
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSession
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import okhttp3.ConnectionSpec
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.internal.tls.OkHostnameVerifier

/**
 * TLS to the user's own Orbit Server, trusting exactly one certificate: the leaf whose SHA-256 (over its DER encoding,
 * lowercase hex, no colons) equals the fingerprint pinned at pairing. There is no fallback to the system CAs, so a
 * public CA (or a user-installed one) can never vouch for a different server.
 *
 * Home servers use self-signed certificates whose names may not match the URL (a LAN IP, `10.0.2.2` from the
 * emulator), so the host name check passes only when the presented leaf is the pinned one; anything else fails.
 * A mismatch surfaces as [ServerIdentityChanged] (UI: "Server identity changed — re-pair").
 */
object PinnedTls {
    /** SHA-256 of [cert]'s DER encoding as 64 lowercase hex digits (the pairing link's `fp`). */
    fun fingerprint(cert: Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString("") { "%02x".format(it) }

    /** Thrown by the trust manager when the leaf is not the pinned certificate. */
    class PinMismatch(val actual: String?) : CertificateException("certificate does not match the pinned fingerprint")

    /** Accepts a chain only when its leaf's fingerprint is [pin]; issuers and the rest of the chain are irrelevant. */
    // Deliberately custom: it pins one self-signed leaf and rejects everything else (it never trusts all).
    @SuppressLint("CustomX509TrustManager")
    fun trustManager(pin: String): X509TrustManager = object : X509TrustManager {
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            val leaf = chain?.firstOrNull() ?: throw PinMismatch(null)
            val actual = fingerprint(leaf)
            if (!MessageDigest.isEqual(actual.toByteArray(), pin.toByteArray())) throw PinMismatch(actual)
        }

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
            throw CertificateException("client certificates are not accepted")

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    /** True only when the session's leaf certificate is the pinned one (the trust manager already checked it). */
    fun hostnameVerifier(pin: String): HostnameVerifier = HostnameVerifier { _: String?, session: SSLSession? ->
        val leaf = try {
            session?.peerCertificates?.firstOrNull()
        } catch (e: Exception) {
            null
        }
        leaf != null && fingerprint(leaf) == pin
    }

    /**
     * Shared settings for every server client: modern TLS only, no redirects (the client talks only to the paired
     * URL), no proxy (a system proxy would learn the server's address and see the traffic's timing), no cache,
     * no cookies (OkHttp's default jar keeps none).
     */
    fun baseClient(): OkHttpClient = OkHttpClient.Builder()
        .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS))
        .followRedirects(false)
        .followSslRedirects(false)
        .proxy(Proxy.NO_PROXY)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .build()

    /** A client (sharing [base]'s pool and settings) that trusts only the certificate with [fingerprint]. */
    fun client(fingerprint: String, base: OkHttpClient = baseClient()): OkHttpClient {
        require(fingerprint.matches(FP)) { "fingerprint must be 64 lowercase hex digits" }
        val tm = trustManager(fingerprint)
        val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), null) }
        return base.newBuilder()
            .sslSocketFactory(ctx.socketFactory, tm)
            .hostnameVerifier(hostnameVerifier(fingerprint))
            .build()
    }

    /** The [ServerIdentityChanged] behind a failed call, if a pin mismatch caused it. */
    fun identityError(e: Throwable, pin: String): ServerIdentityChanged? {
        var t: Throwable? = e
        var depth = 0
        while (t != null && depth++ < 16) {
            if (t is ServerIdentityChanged) return t
            if (t is PinMismatch) return ServerIdentityChanged(pin, t.actual)
            t = t.cause
        }
        return null
    }

    private val FP = Regex("[0-9a-f]{64}")
}

/**
 * TLS to an Orbit Server behind a reverse proxy (Caddy) with a public certificate: the platform's system trust store
 * plus OkHttp's standard host name check (exact SAN match), for exactly the paired host. There is no fallback to the
 * fingerprint pin, nor from the pin to this, and never to plain HTTP.
 *
 * - Trust: [TrustManagerFactory]'s default, initialised with a null KeyStore. On Android that is the platform store as
 *   filtered by `network_security_config.xml`, whose base config lists only `system` anchors, so certificate
 *   authorities the user added are not trusted.
 * - Host: the client refuses every request (and every TLS session) whose host is not the paired URL's host
 *   (case-insensitive) or whose port differs ([WrongHost]); redirects are never followed ([PinnedTls.baseClient]).
 */
object PublicCaTls {
    /** A request (or follow-up) to a host or port other than the paired server's; nothing is sent. */
    class WrongHost : IOException("request to a host other than the paired server refused")

    /** The platform's default trust manager over the system store. */
    fun systemTrustManager(): X509TrustManager {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(null as KeyStore?)
        return factory.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    /** A client (sharing [base]'s pool and settings) for the server at [url] (`https://host[:port]...`) only. */
    fun client(url: String, base: OkHttpClient = PinnedTls.baseClient()): OkHttpClient =
        clientTrusting(url, systemTrustManager(), base)

    /** [client] with another trust manager. Tests only: production always uses [systemTrustManager]. */
    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    internal fun clientTrusting(url: String, trustManager: X509TrustManager, base: OkHttpClient = PinnedTls.baseClient()): OkHttpClient {
        val paired = url.toHttpUrlOrNull()?.takeIf { it.isHttps }
        requireNotNull(paired) { "public-CA mode needs an https url" }
        val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), null) }
        val onlyPaired = Interceptor { chain ->
            if (!isPaired(chain.request().url, paired)) throw WrongHost()
            chain.proceed(chain.request())
        }
        return base.newBuilder()
            .sslSocketFactory(ctx.socketFactory, trustManager)
            .hostnameVerifier { host, session -> host.equals(paired.host, ignoreCase = true) && OkHostnameVerifier.verify(host, session) }
            .addInterceptor(onlyPaired)
            .addNetworkInterceptor(onlyPaired)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    private fun isPaired(u: HttpUrl, paired: HttpUrl) = u.isHttps && u.host.equals(paired.host, ignoreCase = true) && u.port == paired.port
}

/**
 * The single place that maps a trust mode to its HTTP client: [pinned] (fingerprint → [PinnedTls.client]) for
 * [ServerTrustMode.PIN], [ca] (url → [PublicCaTls.client]) for [ServerTrustMode.PUBLIC_CA]. One mode never stands in
 * for the other. Throws [IllegalArgumentException] for a pin-mode request without a fingerprint, a public-CA one with
 * a fingerprint, or (from [ca]) a URL that is not https.
 */
fun serverHttpFor(
    mode: ServerTrustMode,
    fingerprint: String?,
    url: String,
    pinned: (fingerprint: String) -> OkHttpClient,
    ca: (url: String) -> OkHttpClient,
): OkHttpClient = when (mode) {
    ServerTrustMode.PIN -> pinned(requireNotNull(fingerprint) { "pin mode needs a fingerprint" })
    ServerTrustMode.PUBLIC_CA -> {
        require(fingerprint == null) { "public-CA mode uses no fingerprint" }
        ca(url)
    }
}

/** [serverHttpFor] for a stored pairing. */
fun serverHttpFor(server: PairedServer, pinned: (fingerprint: String) -> OkHttpClient, ca: (url: String) -> OkHttpClient): OkHttpClient =
    serverHttpFor(server.trustMode, server.fingerprint, server.url, pinned, ca)
