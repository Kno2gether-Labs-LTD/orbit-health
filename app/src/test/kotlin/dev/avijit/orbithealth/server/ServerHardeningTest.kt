package dev.avijit.orbithealth.server

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.avijit.orbithealth.data.NetworkLogEntity
import dev.avijit.orbithealth.data.OrbitDatabase
import dev.avijit.orbithealth.data.SampleRepository
import dev.avijit.orbithealth.sync.Scope
import java.io.File
import java.io.IOException
import java.net.ServerSocket
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pairing and push edge cases carried from the S7 review: stale pushes across a re-pair, revoking the replaced pairing,
 * cancellation while pairing, honest byte counts for requests that never left, a transient store read failure, and
 * the last push's outcome surviving a restart.
 */
@RunWith(RobolectricTestRunner::class)
class ServerHardeningTest {
    private val cert = HeldCertificate.Builder().commonName("Orbit Server").addSubjectAlternativeName("orbit.invalid").build()
    private val pin = PinnedTls.fingerprint(cert.certificate)
    private val web = MockWebServer()
    private val requests = ConcurrentLinkedQueue<String>() // "METHOD path token"
    private val pairs = AtomicInteger()

    /** When set, the first `/v1/state` waits for it and then answers 401 (this phone was removed). */
    @Volatile private var holdFirstState: CountDownLatch? = null
    private val stateCalls = AtomicInteger()
    @Volatile private var scopesDelayMs = 0L
    @Volatile private var pairStatus = 200

    private val clock = MutableClock(Instant.parse("2026-11-06T09:00:00Z"))
    private lateinit var db: OrbitDatabase
    private lateinit var dir: File
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val box = object : SecretBox {
        override fun seal(plain: ByteArray) = plain.reversedArray()
        override fun open(sealed: ByteArray) = sealed.reversedArray()
    }
    private val logged = ConcurrentLinkedQueue<NetworkLogEntity>()
    private val recorder = NetworkRecorder { logged += it }
    private val results = MemoryResultStore()
    private lateinit var store: ServerStore
    private lateinit var sync: ServerSync
    private lateinit var server: ServerRepository

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = OrbitDatabase.inMemory(ctx)
        dir = File(ctx.noBackupFilesDir, "hardening-test").apply { deleteRecursively(); mkdirs() }
        web.useHttps(HandshakeCertificates.Builder().heldCertificate(cert).build().sslSocketFactory(), false)
        web.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val token = request.getHeader("Authorization")?.removePrefix("Bearer ") ?: "-"
                requests += "${request.method} ${request.path} $token"
                return when (request.path) {
                    "/v1/pair" -> {
                        if (pairStatus != 200) return json("""{"error":"invalid_pairing_token"}""", pairStatus)
                        val n = pairs.incrementAndGet()
                        json("""{"deviceToken":"tok-$n","deviceId":"d$n","serverName":"Home Mac","scopes":["heart","sleep","activity","stress","spo2","profile"]}""")
                    }
                    "/v1/state" -> {
                        val latch = holdFirstState
                        if (latch != null && stateCalls.incrementAndGet() == 1) {
                            latch.await(10, TimeUnit.SECONDS)
                            json("""{"error":"unauthorized"}""", 401)
                        } else json("""{"lastEpochMsByMetric":{}}""")
                    }
                    "/v1/ingest" -> json("""{"samplesNew":0,"samplesDup":0,"sleepNew":0,"sleepDup":0}""")
                    "/v1/devices/me/scopes" -> {
                        if (scopesDelayMs > 0) Thread.sleep(scopesDelayMs)
                        json(request.body.readUtf8())
                    }
                    "/v1/devices/me" -> MockResponse().setResponseCode(204)
                    else -> json("""{"error":"not_found"}""", 404)
                }
            }
        }
        web.start()
        store = ServerStore(File(dir, "server.bin"), box)
        sync = newSync()
        server = ServerRepository(store, sync, { PinnedTls.client(it) }, recorder, db.networkLogDao(), { "Pixel 9" }, clock, { "Asia/Kolkata" })
    }

    private fun newSync() = ServerSync(
        store, { ServerClient(it, PinnedTls.client(it.fingerprint!!), recorder, clock) }, RepositorySyncSource(SampleRepository(db)),
        { null }, scope, clock, zone = { "Asia/Kolkata" }, results = results,
    )

    @After fun tearDown() {
        holdFirstState?.countDown()
        scope.cancel()
        web.shutdown()
        db.close()
        dir.deleteRecursively()
    }

    private fun json(body: String, code: Int = 200) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    private fun link(token: String = "one-time-token") =
        "orbit://pair?u=${java.net.URLEncoder.encode(web.url("/").toString().trimEnd('/'), "UTF-8")}&fp=$pin&t=$token&n=Home+Mac"

    private suspend fun awaitResult(p: (ServerSyncResult) -> Boolean) =
        withTimeout(5_000) { while (sync.lastResult.value?.let(p) != true) delay(10) }

    @Test fun staleInFlightPushCannotBlockOrBackOffANewPairing() = runBlocking {
        val hold = CountDownLatch(1)
        holdFirstState = hold
        server.pair(link("first")) // its background push now waits on /v1/state and will come back 401
        withTimeout(5_000) { while (stateCalls.get() < 1) delay(10) }

        server.pair(link("second")) // a new pairing while the old push is still in flight
        hold.countDown()

        // The stale 401 must not mark the new pairing as needing a re-pair, nor start a backoff; the new pairing's
        // first push still happens once the stale one has finished.
        awaitResult { it.ok }
        val r = sync.lastResult.value!!
        assertFalse(r.needsRepair)
        assertTrue("the new pairing pushed with its own token", requests.any { it.startsWith("GET /v1/state tok-2") })
        val before = requests.size
        sync.afterRingSync() // not blocked, not backing off
        withTimeout(5_000) { while (requests.drop(before).none { it.startsWith("GET /v1/state tok-2") }) delay(10) }
    }

    @Test fun rePairingRevokesThePreviousPairingBestEffort() = runBlocking {
        server.pair(link("first"))
        awaitResult { it.ok }
        server.pair(link("second"))
        assertEquals("tok-2", store.current()!!.deviceToken)
        withTimeout(5_000) { while (requests.none { it == "DELETE /v1/devices/me tok-1" }) delay(10) }
        assertTrue("the new pairing is never revoked", requests.none { it == "DELETE /v1/devices/me tok-2" })

        // A pairing that fails keeps the current one and revokes nothing.
        pairStatus = 401
        val failed = runCatching { server.pair(link("third")) }.exceptionOrNull()
        assertTrue(failed is PairingRejected)
        assertEquals("tok-2", store.current()!!.deviceToken)
        assertTrue(requests.none { it == "DELETE /v1/devices/me tok-2" })
    }

    @Test fun cancellingPairingWhileNarrowingRevokesTheNewTokenAndStoresNothing() = runBlocking {
        scopesDelayMs = 2_000
        val job = scope.async { server.pair(link(), setOf(Scope.HEART)) }
        withTimeout(5_000) { while (requests.none { it.startsWith("PATCH /v1/devices/me/scopes") }) delay(10) }
        job.cancel()
        val thrown = runCatching { job.await() }.exceptionOrNull()
        assertTrue("cancellation is rethrown, not swallowed: $thrown", thrown is CancellationException)
        withTimeout(5_000) { while (requests.none { it == "DELETE /v1/devices/me tok-1" }) delay(10) }
        assertNull(store.current())
        assertFalse(File(dir, "server.bin").exists())
    }

    @Test fun networkLogCountsNoBytesUpWhenTheRequestNeverReachedTheServer() = runBlocking {
        // Wrong pin: the TLS handshake fails before any HTTP is written.
        val wrong = PairedServer(web.url("/").toString().trimEnd('/'), "0".repeat(64), "tok", "d", "n", Scope.ALL_WIRE)
        val e1 = runCatching { ServerClient(wrong, PinnedTls.client(wrong.fingerprint!!), recorder, clock).state() }.exceptionOrNull()
        assertTrue(e1 is ServerIdentityChanged)
        // Nothing listening: the connection is refused.
        val port = ServerSocket(0).use { it.localPort }
        val dead = wrong.copy(url = "https://127.0.0.1:$port", fingerprint = pin)
        val e2 = runCatching { ServerClient(dead, PinnedTls.client(pin), recorder, clock).state() }.exceptionOrNull()
        assertTrue(e2 is ServerUnreachable)
        assertEquals(listOf(0L, 0L), logged.map { it.bytesUp })
        assertTrue(logged.none { it.ok })

        // A request that was sent still counts its bytes.
        val ok = wrong.copy(fingerprint = pin)
        ServerClient(ok, PinnedTls.client(pin), recorder, clock).state()
        assertTrue(logged.last().bytesUp > 0)
    }

    @Test fun aFailedStoreReadIsNotCached() = runBlocking {
        server.pair(link())
        val flaky = object : SecretBox {
            var failures = 1
            override fun seal(plain: ByteArray) = box.seal(plain)
            override fun open(sealed: ByteArray): ByteArray {
                if (failures-- > 0) throw IOException("keystore busy")
                return box.open(sealed)
            }
        }
        val fresh = ServerStore(File(dir, "server.bin"), flaky)
        assertNull("unreadable counts as not paired for now", fresh.current())
        assertNotNull("and is read again next time", fresh.current())
        assertTrue(File(dir, "server.bin").exists())
    }

    @Test fun aStoredOutcomeReadWhileANewPairingStartsIsNotShown() = runBlocking {
        // The stored result says the old pairing needs repair; a new pairing starts while it is still being read.
        val stale = ServerSyncResult(1L, ok = false, needsRepair = true, kind = ServerSyncResult.UNAUTHORIZED)
        val gated = GatedResultStore(stale)
        val s = ServerSync(store, { ServerClient(it, PinnedTls.client(it.fingerprint!!), recorder, clock) },
            RepositorySyncSource(SampleRepository(db)), { null }, scope, clock, results = gated)
        val reset = scope.async { s.reset() }
        delay(50)
        gated.loadGate.complete(Unit)
        reset.await()
        delay(50)
        assertNull("no stale re-pair banner on the new pairing", s.lastResult.value)
        assertNull(gated.value)
    }

    @Test fun aSaveFromTheOldPairingNeverOverwritesTheNewPairingsClear() = runBlocking {
        val gated = GatedResultStore(null).apply { loadGate.complete(Unit) }
        val s = ServerSync(store, { ServerClient(it, PinnedTls.client(it.fingerprint!!), recorder, clock) },
            RepositorySyncSource(SampleRepository(db)), { null }, scope, clock, results = gated)
        store.save(PairedServer(web.url("/").toString().trimEnd('/'), pin, "tok-old", "d0", "Home Mac", Scope.ALL_WIRE))
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        gated.saveGate = release
        val push = scope.async { s.syncNow() }
        withTimeout(5_000) { gated.saveStarted.await() } // the old pairing's outcome is being written
        val gate = release
        val reset = scope.async { s.reset() }             // a new pairing starts meanwhile
        delay(50)
        gate.complete(Unit)
        push.await(); reset.await()
        assertNull("the clear lands last", gated.value)
        assertNull(s.lastResult.value)
    }

    @Test fun aRestoredNeedsRepairOutcomeKeepsAutomaticPushesStopped() = runBlocking {
        store.save(PairedServer(web.url("/").toString().trimEnd('/'), pin, "tok-old", "d0", "Home Mac", Scope.ALL_WIRE))
        results.value = ServerSyncResult(1L, ok = false, needsRepair = true, kind = ServerSyncResult.UNAUTHORIZED)
        val restarted = newSync()
        withTimeout(5_000) { while (restarted.lastResult.value == null) delay(10) }
        delay(50)
        val before = requests.size
        restarted.afterRingSync()
        delay(300)
        assertEquals("no automatic push after a restart while re-pair is needed", before, requests.size)

        // A new pairing (a new generation) lifts the block.
        restarted.reset()
        restarted.afterRingSync()
        withTimeout(5_000) { while (requests.size == before) delay(10) }
    }

    @Test fun lastSyncResultSurvivesARestartAndIsClearedByANewPairing() = runBlocking {
        server.pair(link())
        awaitResult { it.ok }
        val saved = results.value
        assertNotNull(saved)
        assertTrue(saved!!.ok)

        // A new process: the screen shows the last push before any new one runs.
        val restarted = newSync()
        withTimeout(5_000) { while (restarted.lastResult.value == null) delay(10) }
        assertEquals(saved, restarted.lastResult.value)

        server.unpair()
        assertNull(results.value)
    }
}

/** A [SyncResultStore] whose reads and writes wait for the test. */
class GatedResultStore(var value: ServerSyncResult?) : SyncResultStore {
    val loadGate = kotlinx.coroutines.CompletableDeferred<Unit>()
    @Volatile var saveGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    val saveStarted = kotlinx.coroutines.CompletableDeferred<Unit>()
    override suspend fun load(): ServerSyncResult? { loadGate.await(); return value }
    override suspend fun save(result: ServerSyncResult?) {
        saveStarted.complete(Unit)
        val gate = saveGate
        saveGate = null // only the first save waits
        gate?.await()
        value = result
    }
}

/** In-memory [SyncResultStore]. */
class MemoryResultStore : SyncResultStore {
    @Volatile var value: ServerSyncResult? = null
    override suspend fun load() = value
    override suspend fun save(result: ServerSyncResult?) { value = result }
}
