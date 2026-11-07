package dev.avijit.orbithealth.server

import dev.avijit.orbithealth.data.NetworkLogEntity
import dev.avijit.orbithealth.sync.SampleDto
import dev.avijit.orbithealth.sync.SleepDto
import dev.avijit.orbithealth.ring.Metric
import java.io.File
import java.net.URLEncoder
import java.time.Clock
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The stored trust mode (with migration of older records) and pairing a `m=ca` link through [ServerRepository]. */
class ServerTrustModeTest {
    @get:Rule val tmp = TemporaryFolder()

    private val box = object : SecretBox {
        override fun seal(plain: ByteArray) = plain.reversedArray()
        override fun open(sealed: ByteArray) = sealed.reversedArray()
    }

    private val root = HeldCertificate.Builder().certificateAuthority(0).commonName("Test Root CA").build()
    private val leaf = HeldCertificate.Builder().signedBy(root).addSubjectAlternativeName("localhost").build()
    private val testRoots = HandshakeCertificates.Builder().addTrustedCertificate(root.certificate).build().trustManager
    private val web = MockWebServer()
    private val paths = ConcurrentLinkedQueue<String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val logged = ConcurrentLinkedQueue<NetworkLogEntity>()
    private val recorder = NetworkRecorder { logged += it }
    private val pinnedUsed = ConcurrentLinkedQueue<String>()
    private val caUsed = ConcurrentLinkedQueue<String>()

    private lateinit var store: ServerStore
    private lateinit var repo: ServerRepository

    private val emptySource = object : ServerSyncSource {
        override suspend fun samplesAfter(metric: Metric, afterMs: Long, limit: Int) = emptyList<SampleDto>()
        override suspend fun sleepFrom(fromMs: Long, limit: Int) = emptyList<SleepDto>()
    }

    private fun pinned(fp: String): OkHttpClient { pinnedUsed += fp; return PinnedTls.client(fp) }
    private fun ca(url: String): OkHttpClient { caUsed += url; return PublicCaTls.clientTrusting(url, testRoots) }
    private fun http(s: PairedServer) = when (s.trustMode) {
        ServerTrustMode.PIN -> pinned(s.fingerprint!!)
        ServerTrustMode.PUBLIC_CA -> ca(s.url)
    }

    @Before fun setUp() {
        web.useHttps(HandshakeCertificates.Builder().heldCertificate(leaf, root.certificate).build().sslSocketFactory(), false)
        web.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                paths += "${request.method} ${request.path}"
                return when (request.path) {
                    "/v1/pair" -> json("""{"deviceToken":"ca-device-token","deviceId":"d1","serverName":"VPS","scopes":["heart","sleep"]}""")
                    "/v1/state" -> json("""{"lastEpochMsByMetric":{}}""")
                    "/v1/access-log?limit=100" -> json("[]")
                    else -> json("""{"error":"not_found"}""", 404)
                }
            }
        }
        web.start()
        store = ServerStore(File(tmp.root, "server.bin"), box)
        val sync = ServerSync(store, { ServerClient(it, http(it), recorder) }, emptySource, { null }, scope, Clock.systemUTC())
        repo = ServerRepository(store, sync, ::pinned, recorder, null, { "Pixel 9" }, caHttp = ::ca)
    }

    @After fun tearDown() {
        scope.cancel()
        web.shutdown()
    }

    private fun json(body: String, code: Int = 200) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    private val url get() = web.url("/").toString().trimEnd('/')
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    @Test fun storeRoundTripsAPublicCaPairing() = runBlocking {
        val s = PairedServer("https://orbit.example.com", null, "tok", "d", "VPS", setOf("heart"), trustMode = ServerTrustMode.PUBLIC_CA)
        store.save(s)
        val back = ServerStore(File(tmp.root, "server.bin"), box).current()
        assertEquals(s, back)
        assertEquals(ServerTrustMode.PUBLIC_CA, back!!.trustMode)
        assertNull(back.fingerprint)
        val raw = box.open(File(tmp.root, "server.bin").readBytes()).toString(Charsets.UTF_8)
        assertTrue(raw, raw.contains("\"trustMode\":\"public_ca\"") && !raw.contains("fingerprint"))
    }

    @Test fun anOlderRecordWithoutAModeLoadsAsPin() = runBlocking {
        val fp = "ab".repeat(32)
        val old = """{"url":"https://orbit.home.lan:8443","fingerprint":"$fp","deviceToken":"tok","deviceId":"d","serverName":"Home Mac","scopes":["heart"],"wantedScopes":["heart"]}"""
        File(tmp.root, "server.bin").writeBytes(box.seal(old.toByteArray(Charsets.UTF_8)))
        val s = ServerStore(File(tmp.root, "server.bin"), box).current()
        assertNotNull(s)
        assertEquals(ServerTrustMode.PIN, s!!.trustMode)
        assertEquals(fp, s.fingerprint)
        assertEquals("tok", s.deviceToken)
    }

    @Test fun pairingACaLinkStoresPublicCa() = runBlocking {
        val info = repo.pair("orbit://pair?u=${enc(url)}&t=one-time&n=VPS&m=ca")
        assertEquals(ServerTrustMode.PUBLIC_CA, info.trustMode)
        assertNull(info.fingerprint)
        val stored = ServerStore(File(tmp.root, "server.bin"), box).current()!!
        assertEquals(ServerTrustMode.PUBLIC_CA, stored.trustMode)
        assertNull(stored.fingerprint)
        assertEquals("ca-device-token", stored.deviceToken)
        assertEquals(url, stored.url)
        assertTrue(pinnedUsed.isEmpty())
        assertEquals(url, caUsed.first())
        assertTrue(paths.toString(), "POST /v1/pair" in paths)
        // Later calls use the same (public-CA) trust.
        repo.accessLog()
        assertTrue(pinnedUsed.isEmpty())
        assertTrue(logged.any { it.purpose == "pair" && it.ok })
    }

    @Test fun aCaLinkWithAFingerprintStillStoresNoFingerprint() = runBlocking {
        val info = repo.pair("orbit://pair?u=${enc(url)}&fp=${"cd".repeat(32)}&t=one-time&n=VPS&m=ca")
        assertEquals(ServerTrustMode.PUBLIC_CA, info.trustMode)
        assertNull(store.current()!!.fingerprint)
        assertTrue(pinnedUsed.isEmpty())
    }

    @Test fun aPinLinkWithoutAFingerprintIsRefused() = runBlocking {
        for (link in listOf("orbit://pair?u=${enc(url)}&t=one-time&n=VPS", "orbit://pair?u=${enc(url)}&t=one-time&n=VPS&m=pin")) {
            try {
                repo.pair(link)
                fail("$link must be refused")
            } catch (e: InvalidPairingLink) {
                // expected
            }
        }
        assertEquals(0, web.requestCount)
        assertNull(store.current())
        assertTrue(pinnedUsed.isEmpty() && caUsed.isEmpty())
    }

    @Test fun aCaLinkMustBeHttps() = runBlocking {
        try {
            repo.pair("orbit://pair?u=${enc("http://localhost:${web.port}")}&t=one-time&n=VPS&m=ca")
            fail("http must be refused")
        } catch (e: InvalidPairingLink) {
            // expected
        }
        assertEquals(0, web.requestCount)
    }
}
