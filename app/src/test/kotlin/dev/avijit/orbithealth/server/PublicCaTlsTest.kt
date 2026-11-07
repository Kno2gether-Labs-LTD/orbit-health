package dev.avijit.orbithealth.server

import dev.avijit.orbithealth.data.NetworkLogEntity
import dev.avijit.orbithealth.sync.PairingUri
import dev.avijit.orbithealth.sync.TrustMode
import java.io.IOException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Public-CA trust mode (a server behind Caddy with a real certificate): a chain from a trusted root, for exactly the
 * paired host, and nothing else. The trust manager is injected with a test root (production uses the system store).
 */
class PublicCaTlsTest {
    private val root = HeldCertificate.Builder().certificateAuthority(0).commonName("Test Root CA").build()
    private val localhostCert = HeldCertificate.Builder().signedBy(root).commonName("localhost").addSubjectAlternativeName("localhost").build()
    private val otherHostCert = HeldCertificate.Builder().signedBy(root).commonName("orbit.example.com")
        .addSubjectAlternativeName("orbit.example.com").build()
    private val selfSigned = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
    private val testRoots = HandshakeCertificates.Builder().addTrustedCertificate(root.certificate).build().trustManager

    private val web = MockWebServer()
    private val logged = mutableListOf<NetworkLogEntity>()
    private val recorder = NetworkRecorder { logged += it }

    @After fun tearDown() = web.shutdown()

    private fun serve(cert: HeldCertificate, vararg intermediates: HeldCertificate) {
        val hc = HandshakeCertificates.Builder().heldCertificate(cert, *intermediates.map { it.certificate }.toTypedArray()).build()
        web.useHttps(hc.sslSocketFactory(), false)
        web.start()
    }

    private val url get() = web.url("/").toString().trimEnd('/')
    private fun caServer(u: String = url) =
        PairedServer(u, null, "device-token-ca", "dev-ca", "VPS", setOf("heart"), trustMode = ServerTrustMode.PUBLIC_CA)

    private fun json(body: String, code: Int = 200) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    private fun state() = json("""{"lastEpochMsByMetric":{},"lastSleepStartMs":null}""")

    @Test fun acceptsAChainFromATrustedRootForTheExactHost() = runBlocking {
        serve(localhostCert, root)
        assertEquals("localhost", web.hostName)
        web.enqueue(state())
        ServerClient(caServer(), PublicCaTls.clientTrusting(url, testRoots), recorder).state()
        assertEquals("Bearer device-token-ca", web.takeRequest().getHeader("Authorization"))
        assertTrue(logged.single().ok)
        assertEquals("localhost", logged.single().host)
    }

    @Test fun pairOverPublicCaStoresNoFingerprint() = runBlocking {
        serve(localhostCert, root)
        web.enqueue(json("""{"deviceToken":"tok-ca","deviceId":"d-ca","serverName":"VPS","scopes":["heart","sleep"]}"""))
        val fp = PinnedTls.fingerprint(selfSigned.certificate) // a ca link may carry an fp; it is not used or kept
        val link = PairingUri(url, fp, "one-time", "VPS", TrustMode.CA)
        val p = ServerClient.pair(link, "Pixel 9", PublicCaTls.clientTrusting(url, testRoots), recorder)
        assertEquals(ServerTrustMode.PUBLIC_CA, p.trustMode)
        assertNull(p.fingerprint)
        assertEquals("tok-ca", p.deviceToken)
        assertEquals("/v1/pair", web.takeRequest().path)
        assertEquals("pair", logged.single().purpose)
        assertTrue(logged.single().ok)
    }

    @Test fun rejectsATrustedCertificateForADifferentHost() = runBlocking {
        serve(otherHostCert, root)
        web.enqueue(state())
        val e = expect<ServerUnreachable> { ServerClient(caServer(), PublicCaTls.clientTrusting(url, testRoots), recorder).state() }
        assertTrue("cause: ${e.cause}", e.cause is SSLPeerUnverifiedException)
        assertEquals(0, web.requestCount)
        assertEquals(0L, logged.single().bytesUp)
        assertFalse(logged.single().ok)
    }

    @Test fun rejectsASelfSignedCertificateNotInTheTrustStore() = runBlocking {
        serve(selfSigned)
        web.enqueue(state())
        val e = expect<ServerUnreachable> { ServerClient(caServer(), PublicCaTls.clientTrusting(url, testRoots), recorder).state() }
        assertTrue("cause: ${e.cause}", e.cause is SSLHandshakeException)
        assertEquals(0, web.requestCount)
        assertEquals(0L, logged.single().bytesUp)
    }

    @Test fun productionTrustDoesNotKnowTheTestRoot() = runBlocking {
        serve(localhostCert, root)
        web.enqueue(state())
        val e = expect<ServerUnreachable> { ServerClient(caServer(), PublicCaTls.client(url), recorder).state() }
        assertTrue("cause: ${e.cause}", e.cause is SSLHandshakeException)
        assertEquals(0, web.requestCount)
    }

    @Test fun refusesRequestsToAnyHostOrPortOtherThanThePairedOne() = runBlocking {
        serve(localhostCert, root)
        web.enqueue(state())
        val http = PublicCaTls.clientTrusting(url, testRoots)
        for (other in listOf("https://127.0.0.1:${web.port}/v1/state", "https://localhost:${web.port + 1}/v1/state", "https://example.com/v1/state")) {
            try {
                http.newCall(Request.Builder().url(other).build()).execute().close()
                fail("$other must be refused")
            } catch (e: IOException) {
                assertTrue("$other: $e", e is PublicCaTls.WrongHost)
            }
        }
        // Through ServerClient: a server record naming another host cannot use this paired-host client.
        expect<ServerUnreachable> { ServerClient(caServer("https://127.0.0.1:${web.port}"), http, recorder).state() }
        assertEquals(0, web.requestCount)
        // The paired host itself, case-insensitively.
        http.newCall(Request.Builder().url("https://LOCALHOST:${web.port}/v1/state").build()).execute().use { assertEquals(200, it.code) }
    }

    @Test fun doesNotFollowARedirectToAnotherHost() = runBlocking {
        serve(localhostCert, root)
        web.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://evil.example.com/v1/state"))
        val e = expect<ServerHttpError> { ServerClient(caServer(), PublicCaTls.clientTrusting(url, testRoots), recorder).state() }
        assertEquals(302, e.status)
        assertEquals(1, web.requestCount)
        val http = PublicCaTls.clientTrusting(url, testRoots)
        assertFalse(http.followRedirects)
        assertFalse(http.followSslRedirects)
    }

    @Test fun onlyHttpsUrlsGetAClient() {
        for (bad in listOf("http://localhost:8080", "not a url", "ftp://localhost")) {
            try {
                PublicCaTls.client(bad)
                fail("$bad must be refused")
            } catch (e: IllegalArgumentException) {
                // expected
            }
        }
    }

    @Test fun pairedServerKeepsModeAndFingerprintConsistent() {
        try {
            PairedServer("https://x", null, "t", "d", "n", emptySet())
            fail("a pin-mode pairing needs a fingerprint")
        } catch (e: IllegalArgumentException) {
            // expected
        }
        try {
            PairedServer("https://x", "ab".repeat(32), "t", "d", "n", emptySet(), trustMode = ServerTrustMode.PUBLIC_CA)
            fail("a public-CA pairing stores no fingerprint")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    private inline fun <reified T : Throwable> expect(block: () -> Unit): T {
        try {
            block()
        } catch (t: Throwable) {
            if (t is T) return t
            throw AssertionError("expected ${T::class.simpleName}, got $t", t)
        }
        throw AssertionError("expected ${T::class.simpleName}, nothing thrown")
    }
}
