package dev.avijit.orbithealth.server

import dev.avijit.orbithealth.data.NetworkLogEntity
import dev.avijit.orbithealth.sync.AskRequest
import dev.avijit.orbithealth.sync.IngestBatch
import dev.avijit.orbithealth.sync.SampleDto
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
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
import org.junit.Before
import org.junit.Test

/** Pinned TLS and the HTTP client against a local HTTPS server with self-signed certificates (plain JVM). */
class ServerClientTest {
    // Self-signed, and named for a host that is not the one we connect to (localhost), like a home server on a LAN IP.
    private val serverCert = HeldCertificate.Builder().commonName("Orbit Server").addSubjectAlternativeName("orbit.invalid").build()
    private val otherCert = HeldCertificate.Builder().commonName("Impostor").addSubjectAlternativeName("localhost").build()
    private val web = MockWebServer()
    private val logged = mutableListOf<NetworkLogEntity>()
    private val recorder = NetworkRecorder { logged += it }

    @Before fun setUp() {
        web.useHttps(HandshakeCertificates.Builder().heldCertificate(serverCert).build().sslSocketFactory(), false)
        web.start()
    }

    @After fun tearDown() = web.shutdown()

    private val pin get() = PinnedTls.fingerprint(serverCert.certificate)
    private fun paired(fp: String = pin, url: String = web.url("/").toString().trimEnd('/')) =
        PairedServer(url, fp, "device-token-123", "dev-1", "Test Box", setOf("heart", "sleep"))
    private fun client(fp: String = pin, server: PairedServer = paired(fp)) =
        ServerClient(server, PinnedTls.client(fp), recorder)

    private fun json(body: String, code: Int = 200) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    @Test fun pinnedClientAcceptsThePinnedSelfSignedCertWhateverItsName() = runBlocking {
        web.enqueue(json("""{"lastEpochMsByMetric":{"HEART_RATE":5},"lastSleepStartMs":null}"""))
        val s = client().state()
        assertEquals(5L, s.lastEpochMsByMetric["HEART_RATE"])
        assertNull(s.lastSleepStartMs)
        val req = web.takeRequest()
        assertEquals("/v1/state", req.path)
        assertEquals("Bearer device-token-123", req.getHeader("Authorization"))
    }

    @Test fun pinnedClientRejectsDifferentCert() = runBlocking {
        val wrongPin = PinnedTls.fingerprint(otherCert.certificate)
        web.enqueue(json("{}"))
        try {
            client(wrongPin).state()
            fail("a certificate other than the pinned one must be refused")
        } catch (e: ServerIdentityChanged) {
            assertEquals(wrongPin, e.expected)
            assertEquals(pin, e.actual)
        }
        // Nothing reached the server: the handshake failed before any request (or token) was sent.
        assertEquals(0, web.requestCount)
        assertFalse(logged.single().ok)
    }

    @Test fun aServerPresentingAnotherCertIsRefusedEvenIfItsNameMatches() = runBlocking {
        // The impostor's certificate names "localhost" (what we connect to), but it is not the pinned one.
        val impostor = MockWebServer()
        impostor.useHttps(HandshakeCertificates.Builder().heldCertificate(otherCert).build().sslSocketFactory(), false)
        impostor.start()
        try {
            impostor.enqueue(json("{}"))
            val server = paired(url = impostor.url("/").toString().trimEnd('/'))
            try {
                ServerClient(server, PinnedTls.client(pin), recorder).state()
                fail("expected ServerIdentityChanged")
            } catch (e: ServerIdentityChanged) {
                assertEquals(PinnedTls.fingerprint(otherCert.certificate), e.actual)
            }
            assertEquals(0, impostor.requestCount)
        } finally {
            impostor.shutdown()
        }
    }

    @Test fun hostnameVerifierTrustsOnlyThePinnedLeafWhateverTheName() {
        fun session(vararg certs: java.security.cert.Certificate?) = java.lang.reflect.Proxy.newProxyInstance(
            javaClass.classLoader, arrayOf(javax.net.ssl.SSLSession::class.java),
        ) { _, m, _ -> if (m.name == "getPeerCertificates") certs.filterNotNull().toTypedArray() else null } as javax.net.ssl.SSLSession
        val v = PinnedTls.hostnameVerifier(pin)
        assertTrue(v.verify("10.0.2.2", session(serverCert.certificate)))
        assertFalse(v.verify("localhost", session(otherCert.certificate)))
        assertFalse(v.verify("orbit.invalid", session()))
        assertFalse(v.verify("orbit.invalid", null))
    }

    @Test fun trustManagerAcceptsOnlyThePinnedLeaf() {
        val tm = PinnedTls.trustManager(pin)
        tm.checkServerTrusted(arrayOf(serverCert.certificate), "ECDHE_ECDSA")
        expectBlocking<PinnedTls.PinMismatch> { tm.checkServerTrusted(arrayOf(otherCert.certificate, serverCert.certificate), "ECDHE_ECDSA") }
        expectBlocking<PinnedTls.PinMismatch> { tm.checkServerTrusted(emptyArray(), "ECDHE_ECDSA") }
        assertEquals(0, tm.acceptedIssuers.size)
    }

    private inline fun <reified T : Throwable> expectBlocking(block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            if (t is T) return
            throw AssertionError("expected ${T::class.simpleName}, got $t", t)
        }
        throw AssertionError("expected ${T::class.simpleName}, nothing thrown")
    }

    @Test fun cleartextUrlsAreRefusedBeforeAnyConnection() = runBlocking {
        val http = paired(url = "http://localhost:${web.port}")
        try {
            ServerClient(http, PinnedTls.client(pin), recorder).state()
            fail("http:// must be refused")
        } catch (e: ServerException) {
            // expected
        }
        assertEquals(0, web.requestCount)
    }

    @Test fun pairRedeemsTheLinkOverThePinnedConnection() = runBlocking {
        web.enqueue(json("""{"deviceToken":"tok-xyz","deviceId":"d-9","serverName":"Home Mac","scopes":["heart","sleep","activity"]}"""))
        val link = dev.avijit.orbithealth.sync.PairingUri(web.url("/").toString().trimEnd('/'), pin, "one-time", "Home Mac")
        val p = ServerClient.pair(link, "Pixel 9", PinnedTls.client(pin), recorder)
        assertEquals("tok-xyz", p.deviceToken)
        assertEquals(pin, p.fingerprint)
        assertEquals(setOf("heart", "sleep", "activity"), p.scopes)
        assertFalse("toString must hide the token", p.toString().contains("tok-xyz"))
        val req = web.takeRequest()
        assertEquals("/v1/pair", req.path)
        assertNull(req.getHeader("Authorization"))
        val body = req.body.readUtf8()
        assertTrue(body, body.contains("\"token\":\"one-time\"") && body.contains("\"deviceName\":\"Pixel 9\""))
    }

    @Test fun serverErrorsAreTyped() = runBlocking {
        val c = client()
        web.enqueue(json("""{"error":"no_model"}""", 409))
        expect<NoModel> { c.ask(AskRequest("q", zone = "Europe/London")) }
        web.enqueue(json("""{"error":"model_error"}""", 502))
        expect<ModelError> { c.ask(AskRequest("q")) }
        web.enqueue(json("""{"error":"rate_limited"}""", 429))
        expect<RateLimited> { c.ask(AskRequest("q")) }
        web.enqueue(json("""{"error":"payload_too_large"}""", 413))
        expect<AskTooLarge> { c.ask(AskRequest("q")) }
        web.enqueue(json("", 401))
        expect<ServerUnauthorized> { c.state() }
        web.enqueue(json("""{"error":"payload_too_large"}""", 413))
        val e = expect<ServerHttpError> { c.ingest(IngestBatch(emptyList(), emptyList())) }
        assertEquals(413, e.status)
        assertEquals("payload_too_large", e.code)
        web.enqueue(json("""{"error":"invalid_pairing_token"}""", 401))
        val link = dev.avijit.orbithealth.sync.PairingUri(web.url("/").toString().trimEnd('/'), pin, "used", "n")
        expect<PairingRejected> { ServerClient.pair(link, "Pixel", PinnedTls.client(pin), recorder) }
        val askBody = web.takeRequest().body.readUtf8()
        assertTrue(askBody, askBody.contains("\"zone\":\"Europe/London\""))
    }

    @Test fun unreachableServerIsTyped() = runBlocking {
        val url = web.url("/").toString().trimEnd('/')
        web.shutdown()
        expect<ServerUnreachable> { ServerClient(paired(url = url), PinnedTls.client(pin), recorder).state() }
        assertFalse(logged.single().ok)
    }

    @Test fun networkLogRecordsBytes() = runBlocking {
        val response = """{"samplesNew":2,"samplesDup":0,"sleepNew":0,"sleepDup":0}"""
        web.enqueue(json(response))
        val batch = IngestBatch(listOf(SampleDto("HEART_RATE", 1L, 60.0, "HISTORY"), SampleDto("HEART_RATE", 2L, 61.0, "HISTORY")), emptyList())
        val r = client().ingest(batch)
        assertEquals(2, r.samplesNew)
        val sent = web.takeRequest(1, TimeUnit.SECONDS)!!.bodySize
        val e = logged.single()
        assertEquals("localhost", e.host)
        assertEquals("ingest", e.purpose)
        assertTrue(e.ok)
        assertTrue("up ${e.bytesUp} must cover the $sent-byte body plus headers", e.bytesUp > sent)
        assertTrue("down ${e.bytesDown} must cover the response body", e.bytesDown > response.length)

        web.enqueue(json("""{"error":"internal error"}""", 500))
        expect<ServerHttpError> { client().state() }
        val failed = logged.last()
        assertEquals("state", failed.purpose)
        assertFalse(failed.ok)
        assertTrue(failed.bytesDown > 0)
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
