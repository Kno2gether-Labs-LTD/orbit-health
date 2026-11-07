package dev.avijit.orbithealth.server

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.avijit.orbithealth.data.NetworkLogEntity
import dev.avijit.orbithealth.data.OrbitDatabase
import dev.avijit.orbithealth.data.SampleRepository
import dev.avijit.orbithealth.ring.HistoryBatch
import dev.avijit.orbithealth.ring.Metric
import dev.avijit.orbithealth.ring.Sample
import dev.avijit.orbithealth.ring.SampleSource
import dev.avijit.orbithealth.ring.SleepSegment
import dev.avijit.orbithealth.ring.SleepSession
import dev.avijit.orbithealth.ring.SleepStage
import dev.avijit.orbithealth.sync.DeviceInfoDto
import dev.avijit.orbithealth.sync.IngestBatch
import dev.avijit.orbithealth.sync.Scope
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
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

/** Pairing, the post-ring-sync push and the network log, end to end over pinned HTTPS against MockWebServer. */
@RunWith(RobolectricTestRunner::class)
class ServerSyncTest {
    private val cert = HeldCertificate.Builder().commonName("Orbit Server").addSubjectAlternativeName("orbit.invalid").build()
    private val pin = PinnedTls.fingerprint(cert.certificate)
    private val web = MockWebServer()
    private val ingests = ConcurrentLinkedQueue<IngestBatch>()
    @Volatile private var stateBody = """{"lastEpochMsByMetric":{}}"""
    @Volatile private var failAll = false
    @Volatile private var failScopes = false
    /** A misbehaving server that ignores the PATCH and keeps granting everything. */
    @Volatile private var scopesEchoAll = false
    private val paths = ConcurrentLinkedQueue<String>()

    private val t0 = Instant.parse("2026-11-01T00:00:00Z")
    private val clock = MutableClock(t0.plusSeconds(10 * 86_400))
    private lateinit var db: OrbitDatabase
    private lateinit var repo: SampleRepository
    private lateinit var dir: File
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var device: DeviceInfoDto? = null

    /** Test box: not secure, just proves the token never hits the disk as plaintext. */
    private val box = object : SecretBox {
        override fun seal(plain: ByteArray) = plain.map { (it.toInt() xor 0x5a).toByte() }.toByteArray().reversedArray()
        override fun open(sealed: ByteArray) = sealed.reversedArray().map { (it.toInt() xor 0x5a).toByte() }.toByteArray()
    }

    private lateinit var store: ServerStore
    private lateinit var sync: ServerSync
    private lateinit var server: ServerRepository
    private val logged = ConcurrentLinkedQueue<NetworkLogEntity>()

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = OrbitDatabase.inMemory(ctx)
        repo = SampleRepository(db)
        dir = File(ctx.noBackupFilesDir, "server-test").apply { deleteRecursively(); mkdirs() }
        web.useHttps(HandshakeCertificates.Builder().heldCertificate(cert).build().sslSocketFactory(), false)
        web.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                paths += "${request.method} ${request.path}"
                if (failAll) return json("""{"error":"internal error"}""", 500)
                return when (request.path) {
                    "/v1/pair" -> json("""{"deviceToken":"secret-device-token","deviceId":"d1","serverName":"Home Mac","scopes":["heart","sleep","activity","stress","spo2","profile"]}""")
                    "/v1/state" -> json(stateBody)
                    "/v1/ingest" -> {
                        val b = ServerClient.json.decodeFromString(IngestBatch.serializer(), request.body.readUtf8())
                        ingests += b
                        json("""{"samplesNew":${b.samples.size},"samplesDup":0,"sleepNew":${b.sleep.size},"sleepDup":0}""")
                    }
                    "/v1/devices/me/scopes" -> when {
                        failScopes -> json("""{"error":"internal error"}""", 500)
                        scopesEchoAll -> json("""{"scopes":["heart","sleep","activity","stress","spo2","profile"]}""")
                        else -> json(request.body.readUtf8())
                    }
                    "/v1/devices/me" -> MockResponse().setResponseCode(204)
                    else -> json("""{"error":"not_found"}""", 404)
                }
            }
        }
        web.start()
        val recorder = NetworkRecorder { logged += it; db.networkLogDao().insert(it) }
        store = ServerStore(File(dir, "server.bin"), box)
        sync = ServerSync(store, { ServerClient(it, PinnedTls.client(it.fingerprint!!), recorder, clock) }, RepositorySyncSource(repo),
            { device }, scope, clock, zone = { "Asia/Kolkata" })
        server = ServerRepository(store, sync, { PinnedTls.client(it) }, recorder, db.networkLogDao(), { "Pixel 9" }, clock, { "Asia/Kolkata" })
    }

    @After fun tearDown() {
        scope.cancel()
        web.shutdown()
        db.close()
        dir.deleteRecursively()
    }

    private fun json(body: String, code: Int = 200) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    private fun link() = "orbit://pair?u=${java.net.URLEncoder.encode(web.url("/").toString().trimEnd('/'), "UTF-8")}&fp=$pin&t=one-time-token&n=Home+Mac"

    private fun hr(epochMs: Long, v: Double = 60.0) = Sample(Metric.HEART_RATE, Instant.ofEpochMilli(epochMs), v, SampleSource.HISTORY)
    private fun night(startMs: Long) = SleepSession(
        Instant.ofEpochMilli(startMs), Instant.ofEpochMilli(startMs + 3_600_000),
        listOf(SleepSegment(SleepStage.DEEP, Instant.ofEpochMilli(startMs), 3_600)),
    )

    /** Pairs, then waits for the background push pair() starts and forgets what it sent. */
    private suspend fun pairQuietly(scopes: Set<Scope>? = null) {
        server.pair(link(), scopes)
        withTimeout(5_000) { while (sync.lastResult.value == null) delay(10) }
        ingests.clear()
        logged.clear()
    }

    @Test fun pairStoresTokenAndFingerprint() = runBlocking {
        val info = server.pair(link())
        assertEquals("Home Mac", info.name)
        assertEquals(pin, info.fingerprint)
        assertEquals(Scope.entries.toSet(), info.scopes)

        // Stored sealed: the token is not on disk in plaintext, and a fresh store (a new process) reads it back.
        val raw = File(dir, "server.bin").readBytes().toString(Charsets.ISO_8859_1)
        assertFalse(raw.contains("secret-device-token"))
        val reloaded = ServerStore(File(dir, "server.bin"), box).current()
        assertNotNull(reloaded)
        assertEquals("secret-device-token", reloaded!!.deviceToken)
        assertEquals(pin, reloaded.fingerprint)
        assertEquals(web.url("/").toString().trimEnd('/'), reloaded.url)
        assertEquals("Home Mac", reloaded.serverName)
        assertEquals("Home Mac", server.pairedServer.first()?.name)

        val pairReq = web.takeRequest()
        assertEquals("/v1/pair", pairReq.path)
        assertTrue(pairReq.body.readUtf8().contains("\"deviceName\":\"Pixel 9\""))
    }

    @Test fun pairWithNarrowerScopesPatchesThemAndBadLinksStoreNothing() = runBlocking {
        try {
            server.pair("orbit://pair?u=http%3A%2F%2Fh&fp=$pin&t=t&n=n")
            throw AssertionError("expected InvalidPairingLink")
        } catch (e: InvalidPairingLink) {
            assertNull(store.current())
        }
        val info = server.pair(link(), setOf(Scope.HEART, Scope.SLEEP))
        assertEquals(setOf(Scope.HEART, Scope.SLEEP), info.scopes)
        assertEquals(setOf("heart", "sleep"), store.current()!!.scopes)
    }

    @Test fun syncSendsOnlyNewerThanWatermark() = runBlocking {
        repo.store(HistoryBatch(
            listOf(hr(1_000), hr(2_000), hr(3_000), hr(4_000), Sample(Metric.SPO2, Instant.ofEpochMilli(3_500), 97.0, SampleSource.MEASURED)),
            listOf(night(4_000_000), night(5_000_000), night(6_000_000)), emptyList(),
        ))
        device = DeviceInfoDto(42, "1.0.7", 80)
        pairQuietly(setOf(Scope.HEART, Scope.SLEEP))
        stateBody = """{"lastEpochMsByMetric":{"HEART_RATE":2000},"lastSleepStartMs":5000000}"""

        val r = sync.syncNow()!!
        assertTrue(r.error, r.ok)
        val sent = ingests.toList()
        assertEquals(listOf(3_000L, 4_000L), sent.flatMap { it.samples }.map { it.epochMs })
        assertTrue("SpO2 is outside the scopes", sent.flatMap { it.samples }.none { it.metric == "SPO2" })
        // The server's newest night is re-sent (it may have grown), older ones are not.
        assertEquals(listOf(5_000_000L, 6_000_000L), sent.flatMap { it.sleep }.map { it.startMs })
        assertTrue("no profile scope: no device info", sent.all { it.device == null })
        assertTrue(sent.all { it.zone == "Asia/Kolkata" })
        assertEquals(2, r.samplesSent)
        assertEquals(2, r.sleepSent)
    }

    @Test fun historyStoredAfterAMeasuredReadingIsStillPushed() = runBlocking {
        // 17:37 a Measure sheet reading is stored at "now"; a push runs and the server's watermark jumps to 17:37.
        val measuredAt = 1_000_000_000L
        repo.store(HistoryBatch(listOf(hr(measuredAt - 7_200_000)), emptyList(), emptyList())) // an older synced reading
        repo.store(HistoryBatch(listOf(Sample(Metric.HEART_RATE, Instant.ofEpochMilli(measuredAt), 91.0, SampleSource.MEASURED)), emptyList(), emptyList()))
        pairQuietly(setOf(Scope.HEART))
        stateBody = """{"lastEpochMsByMetric":{"HEART_RATE":$measuredAt}}"""
        assertTrue(sync.syncNow()!!.ok)
        assertTrue("nothing new yet", ingests.flatMap { it.samples }.isEmpty())

        // Then the next ring sync stores the ring's history from 17:00–17:37, older than the watermark.
        val history = listOf(measuredAt - 1_800_000, measuredAt - 1_200_000, measuredAt - 600_000)
        repo.store(HistoryBatch(history.map { hr(it, 70.0) }, emptyList(), emptyList()))
        ingests.clear()
        assertTrue(sync.syncNow()!!.ok)
        assertEquals("the late history is sent", history, ingests.flatMap { it.samples }.map { it.epochMs })

        // Sent once: the next push has nothing left (the cursor moved on).
        ingests.clear()
        sync.syncNow()
        assertTrue(ingests.flatMap { it.samples }.isEmpty())

        // The cursor survives a restart (a new store reads it back) and belongs to this pairing only.
        val reloaded = ServerStore(File(dir, "server.bin"), box)
        assertNotNull(reloaded.pushCursor("d1")?.get("HEART_RATE"))
        assertNull(reloaded.pushCursor("another-pairing"))
        store.clear()
        assertNull(ServerStore(File(dir, "server.bin"), box).pushCursor("d1"))
    }

    @Test fun aMeasuredReadingIsPushedOnceWhenTheRingAlsoLogsIt() = runBlocking {
        val measuredAt = 1_000_000_000L
        pairQuietly(setOf(Scope.HEART))
        repo.store(HistoryBatch(listOf(Sample(Metric.HEART_RATE, Instant.ofEpochMilli(measuredAt), 89.0, SampleSource.MEASURED)), emptyList(), emptyList()))
        assertTrue(sync.syncNow()!!.ok)
        assertEquals(listOf(measuredAt), ingests.flatMap { it.samples }.map { it.epochMs })

        // The next ring sync reads the ring's own copy (20 s later) beside an automatic reading.
        stateBody = """{"lastEpochMsByMetric":{"HEART_RATE":$measuredAt}}"""
        repo.store(HistoryBatch(listOf(hr(measuredAt - 600_000, 78.0), hr(measuredAt + 20_000, 89.0)), emptyList(), emptyList()))
        ingests.clear()
        assertTrue(sync.syncNow()!!.ok)
        assertEquals("only the automatic reading", listOf(measuredAt - 600_000), ingests.flatMap { it.samples }.map { it.epochMs })
    }

    @Test fun activityBucketsAreResentWithinTheLookbackAndDeviceInfoGoesOnce() = runBlocking {
        val day = 86_400_000L
        val steps = listOf(10 * day - 2 * day, 10 * day - 3_600_000, 10 * day).map {
            Sample(Metric.STEPS, Instant.ofEpochMilli(it), 100.0, SampleSource.HISTORY)
        }
        repo.store(HistoryBatch(steps, emptyList(), emptyList()))
        device = DeviceInfoDto(42, "1.0.7", 80)
        pairQuietly()
        stateBody = """{"lastEpochMsByMetric":{"STEPS":${10 * day}}}"""
        sync.syncNow()
        val sent = ingests.toList()
        assertEquals(listOf(10 * day - 3_600_000, 10 * day), sent.flatMap { it.samples }.map { it.epochMs })
        assertEquals(listOf(DeviceInfoDto(42, "1.0.7", 80)), sent.mapNotNull { it.device })

        // Nothing new at all: still one small batch with the device info (battery may have changed).
        ingests.clear()
        stateBody = """{"lastEpochMsByMetric":{"STEPS":${10 * day + 2 * day}}}"""
        sync.syncNow()
        assertEquals(1, ingests.size)
        assertTrue(ingests.single().samples.isEmpty())
        assertNotNull(ingests.single().device)
    }

    @Test fun syncChunksLargeBatches() = runBlocking {
        repo.store(HistoryBatch((1..4_500).map { hr(it * 60_000L, 50.0 + it % 40) }, (0 until 60).map { night(1_000_000_000L + it * day()) }, emptyList()))
        pairQuietly()
        val r = sync.syncNow()!!
        assertTrue(r.error, r.ok)
        val sent = ingests.toList()
        val sampleChunks = sent.map { it.samples.size }.filter { it > 0 }
        assertEquals(listOf(2_000, 2_000, 500), sampleChunks)
        assertTrue(sent.all { it.samples.size <= ServerSync.CHUNK_SAMPLES && it.sleep.size <= ServerSync.CHUNK_SLEEP })
        assertEquals(4_500, sent.sumOf { it.samples.size })
        assertEquals((1..4_500).map { it * 60_000L }, sent.flatMap { it.samples }.map { it.epochMs })
        assertEquals(60, sent.sumOf { it.sleep.size })
        assertEquals(4_500, r.samplesSent)
    }

    @Test fun networkLogRecordsBytes() = runBlocking {
        repo.store(HistoryBatch(listOf(hr(1_000), hr(2_000)), emptyList(), emptyList()))
        server.pair(link())
        withTimeout(5_000) { while (sync.lastResult.value == null) delay(10) }
        val rows = server.networkLog().first()
        assertEquals(listOf("pair", "state", "ingest"), rows.reversed().map { it.purpose })
        assertTrue(rows.all { it.host == "localhost" && it.ok && it.bytesUp > 0 && it.bytesDown > 0 })
        val ingest = rows.first { it.purpose == "ingest" }
        assertTrue("two samples are well over 100 bytes of JSON", ingest.bytesUp > 100)
        assertEquals(rows.sumOf { it.bytesUp + it.bytesDown }, server.bytesSince(0).first())
        assertEquals(0L, server.bytesSince(clock.millis() + 1).first())
    }

    @Test fun ringSyncUnaffectedByServerFailure() = runBlocking {
        repo.store(HistoryBatch(listOf(hr(1_000)), emptyList(), emptyList()))
        pairQuietly()
        failAll = true
        val before = web.requestCount
        sync.afterRingSync() // must return at once and never throw
        withTimeout(5_000) { while (sync.lastResult.value?.ok != false) delay(10) }
        val failed = sync.lastResult.value!!
        assertFalse(failed.needsRepair)
        assertTrue(logged.any { !it.ok })

        // Backoff: the next ring sync a minute later does not hit the server again.
        val after = web.requestCount
        assertTrue(after > before)
        clock.advance(60_000)
        sync.afterRingSync()
        delay(200)
        assertEquals(after, web.requestCount)

        // After the backoff it tries again and recovers.
        failAll = false
        clock.advance(10 * 60_000)
        sync.afterRingSync()
        withTimeout(5_000) { while (sync.lastResult.value?.ok != true) delay(10) }
    }

    @Test fun identityChangeStopsAutomaticSyncUntilRepaired() = runBlocking {
        pairQuietly()
        // The server now presents a different certificate (e.g. reinstalled, or someone else on that address).
        val stored = store.current()!!
        store.save(stored.copy(fingerprint = "0".repeat(64)))
        val r = sync.syncNow()!!
        assertFalse(r.ok)
        assertTrue(r.needsRepair)
        val count = web.requestCount
        clock.advance(7 * 3_600_000L) // past any backoff
        sync.afterRingSync()
        delay(200)
        assertEquals(count, web.requestCount)
        // Pairing again clears the block.
        server.pair(link())
        withTimeout(5_000) { while (sync.lastResult.value?.ok != true) delay(10) }
    }

    @Test fun unpairRevokesAndForgets() = runBlocking {
        pairQuietly()
        assertTrue(server.unpair())
        assertNull(store.current())
        assertFalse(File(dir, "server.bin").exists())
        assertEquals(listOf("revoke"), logged.map { it.purpose })
    }

    @Test fun setScopesFailureSavesNothingAndRevokes() = runBlocking {
        failScopes = true
        try {
            server.pair(link(), setOf(Scope.HEART))
            throw AssertionError("expected the narrowing failure to be thrown")
        } catch (e: ServerHttpError) {
            assertEquals(500, e.status)
        }
        assertNull("an all-scopes pairing must never be stored", store.current())
        assertFalse(File(dir, "server.bin").exists())
        assertEquals(listOf("POST /v1/pair", "PATCH /v1/devices/me/scopes", "DELETE /v1/devices/me"), paths.toList())
        assertNull("no push happened", sync.lastResult.value)
        delay(200)
        assertTrue(ingests.isEmpty())
    }

    @Test fun narrowedScopesFilterOutgoingBatchesLocally() = runBlocking {
        repo.store(HistoryBatch(
            listOf(hr(1_000), Sample(Metric.SPO2, Instant.ofEpochMilli(2_000), 97.0, SampleSource.MEASURED),
                Sample(Metric.STEPS, Instant.ofEpochMilli(3_000), 50.0, SampleSource.HISTORY)),
            listOf(night(4_000_000)), emptyList(),
        ))
        device = DeviceInfoDto(42, "1.0.7", 80)
        scopesEchoAll = true // the server claims every scope even though the user picked heart only
        val info = server.pair(link(), setOf(Scope.HEART))
        assertEquals(setOf(Scope.HEART), info.scopes)
        withTimeout(5_000) { while (sync.lastResult.value == null) delay(10) }
        val sent = ingests.toList()
        assertEquals(listOf("HEART_RATE"), sent.flatMap { it.samples }.map { it.metric })
        assertTrue(sent.all { it.sleep.isEmpty() && it.device == null })

        // Narrowing later applies locally even when the server cannot be reached.
        ingests.clear()
        failScopes = true
        try {
            server.setScopes(emptySet())
            throw AssertionError("expected the PATCH failure")
        } catch (e: ServerHttpError) {
            assertEquals(emptySet<String>(), store.current()!!.effectiveScopes)
        }
        sync.syncNow()
        assertTrue(ingests.flatMap { it.samples }.isEmpty())
    }

    private fun day() = 86_400_000L
}

/** A clock tests can move. */
class MutableClock(start: Instant) : Clock() {
    @Volatile private var now = start
    override fun instant(): Instant = now
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId?): Clock = this
    fun advance(ms: Long) { now = now.plusMillis(ms) }
}
