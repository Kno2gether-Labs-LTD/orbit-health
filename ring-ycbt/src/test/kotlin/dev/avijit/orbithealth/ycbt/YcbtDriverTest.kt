package dev.avijit.orbithealth.ycbt

import dev.avijit.orbithealth.ring.HistoryBatch
import dev.avijit.orbithealth.ring.MeasureEvent
import dev.avijit.orbithealth.ring.MeasureFailure
import dev.avijit.orbithealth.ring.MeasureKind
import dev.avijit.orbithealth.ring.MonitorKind
import dev.avijit.orbithealth.ring.MonitorSetting
import dev.avijit.orbithealth.ring.MonitoringConfig
import dev.avijit.orbithealth.ring.Metric
import dev.avijit.orbithealth.ring.Sex
import dev.avijit.orbithealth.ring.SyncFailed
import dev.avijit.orbithealth.ring.UserProfile
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class YcbtDriverTest {
    private val zone = ZoneId.of("Australia/Melbourne")
    private val clock = Clock.fixed(Instant.parse("2026-11-05T01:00:00Z"), ZoneOffset.UTC)
    private val profile = UserProfile(heightCm = 175, weightKg = 70, sex = Sex.MALE, age = 40)

    private val historyFixtures = FakeTransport.fixtureReplies(
        "history_sleep", "history_heart", "history_all", "history_spo2",
        "history_blood", "history_body", "history_sport",
    )

    /** Empty SPO2 header, so every type answers. */
    private val emptySpo2Header = Frame(0x051A, ByteArray(2)).encode()

    private val deleteTypes = 0x0540..0x054E

    private val anyBatch = HistoryBatch(emptyList(), emptyList(), emptyList())

    /** Answers like the real ring: fixtures for history, echo for commands/deletes, nothing for acks. */
    private fun ringScript(spo2Answers: Boolean = true, overrides: Map<Int, List<ByteArray>> = emptyMap()): (Frame) -> List<ByteArray> = { f ->
        when {
            f.type in overrides -> overrides.getValue(f.type)
            f.type == 0x0580 -> emptyList()
            f.type == 0x051A -> if (spo2Answers) listOf(emptySpo2Header) else emptyList()
            f.type shr 8 == 0x05 && f.type in historyFixtures -> historyFixtures.getValue(f.type)
            f.type == 0x0200 -> fixtureRx("device_info")
            f.type == 0x0201 -> fixtureRx("support_function")
            else -> listOf(FakeTransport.echo(f))
        }
    }

    private fun driver(t: FakeTransport) = YcbtDriver(t, zone = { zone }, clock = clock)

    @Test
    fun handshakeSendsTimeTimezoneProfileThenReadsInfo() = runTest {
        val t = FakeTransport(ringScript())
        val (info, caps) = driver(t).handshake(profile)
        assertEquals(listOf(0x0100, 0x0144, 0x0103, 0x0200, 0x0201), t.tx.map { it.type })
        assertEquals("1.18", info.firmware)
        assertTrue(Metric.HEART_RATE in caps.history)
    }

    @Test
    fun clearAfterSuccessfulSyncDeletesCompletedTypes() = runTest {
        val t = FakeTransport(ringScript())
        val d = driver(t)
        val batch = d.syncHistory()
        assertFalse(t.tx.any { it.type in deleteTypes })
        // Requests went out in the documented order.
        val requests = t.tx.map { it.type }.filter { it shr 8 == 0x05 && it != 0x0580 }
        assertEquals(listOf(0x0504, 0x0506, 0x0509, 0x051A, 0x0508, 0x0533, 0x0502), requests)
        assertTrue(batch.samples.any { it.metric == Metric.HEART_RATE })
        assertTrue(batch.samples.any { it.metric == Metric.STEPS })
        assertTrue(batch.workouts.isEmpty())

        val before = t.tx.size
        d.clearSyncedHistory(batch)
        // SLEEP (0x0541) is not cleared while CLEAR_SLEEP_VERIFIED is false.
        val expected = listOf(0x0542, 0x0544, 0x0545, 0x0543, 0x054E, 0x0540)
        assertEquals(expected, t.tx.drop(before).map { it.type })
    }

    @Test
    fun syncHistoryNeverSendsDelete() = runTest {
        val t = FakeTransport(ringScript())
        val batch = driver(t).syncHistory()
        assertFalse(t.tx.any { it.type in deleteTypes })
        assertTrue(batch.samples.isNotEmpty())
    }

    @Test
    fun syncAbortsWithoutDeleteWhenTransportDropsMidTransfer() = runTest {
        val heartHeaderAndBlock = historyFixtures.getValue(0x0506).take(2)
        val t = FakeTransport(ringScript(overrides = mapOf(0x0506 to heartHeaderAndBlock)))
        t.dropAfter = 0x0506
        val d = driver(t)
        assertSyncFailed { d.syncHistory() }
        d.clearSyncedHistory(anyBatch)
        assertFalse(t.tx.any { it.type in deleteTypes })
    }

    @Test
    fun failedSyncForgetsEarlierSuccess() = runTest {
        var stall = false
        val headerOnly = historyFixtures.getValue(0x0509).take(1)
        val base = ringScript()
        val t = FakeTransport { f -> if (stall && f.type == 0x0509) headerOnly else base(f) }
        val d = driver(t)
        d.syncHistory()
        stall = true
        assertSyncFailed { d.syncHistory() }
        d.clearSyncedHistory(anyBatch)
        assertFalse(t.tx.any { it.type in deleteTypes })
    }

    @Test
    fun unansweredTypeIsSkippedAndNotDeleted() = runTest {
        val t = FakeTransport(ringScript(spo2Answers = false))
        val d = driver(t)
        val batch = d.syncHistory()
        assertEquals("request + 1 retry", 2, t.tx.count { it.type == 0x051A })
        d.clearSyncedHistory(batch)
        val deletes = t.tx.map { it.type }.filter { it in deleteTypes }
        assertEquals(listOf(0x0542, 0x0544, 0x0543, 0x054E, 0x0540), deletes)
        assertTrue(batch.samples.isNotEmpty())
    }

    @Test
    fun corruptTransferAbortsWithNackAndNoDelete() = runTest {
        val heart = historyFixtures.getValue(0x0506).map { checkNotNull(FrameDecoder.decode(it)) }
        val tampered = heart.map { f ->
            if (f.type == 0x0515) Frame(f.type, f.payload.copyOf().also { it[3] = (it[3].toInt() xor 1).toByte() }) else f
        }.map { it.encode() }
        val t = FakeTransport(ringScript(overrides = mapOf(0x0506 to tampered)))
        val d = driver(t)
        assertSyncFailed { d.syncHistory() }
        assertEquals(Frame(0x0580, byteArrayOf(4)), t.tx.last())
        d.clearSyncedHistory(anyBatch)
        assertFalse(t.tx.any { it.type in deleteTypes })
    }

    @Test
    fun startedTransferThatStallsAbortsWithoutDelete() = runTest {
        val headerOnly = historyFixtures.getValue(0x0509).take(1)
        val t = FakeTransport(ringScript(overrides = mapOf(0x0509 to headerOnly)))
        val d = driver(t)
        assertSyncFailed { d.syncHistory() }
        assertEquals("no retry once a transfer started", 1, t.tx.count { it.type == 0x0509 })
        d.clearSyncedHistory(anyBatch)
        assertFalse(t.tx.any { it.type in deleteTypes })
    }

    @Test
    fun clearWithDifferentBatchIsNoOp() = runTest {
        val t = FakeTransport(ringScript())
        val d = driver(t)
        val batch = d.syncHistory()
        d.clearSyncedHistory(batch.copy()) // equal content, different instance
        d.clearSyncedHistory(anyBatch)
        assertFalse(t.tx.any { it.type in deleteTypes })
        d.clearSyncedHistory(batch)
        assertEquals(6, // every verified type except SLEEP
            t.tx.count { it.type in deleteTypes })
    }

    @Test
    fun cancelledSyncLeavesNothingClearable() = runTest {
        var job: Job? = null
        var sportRequested = false
        val base = ringScript()
        val t = FakeTransport { f ->
            if (f.type == 0x0502) sportRequested = true
            // Cancel the caller right after the last type's end-frame ack is written.
            if (f.type == 0x0580 && sportRequested) job?.cancel()
            base(f)
        }
        val d = driver(t)
        job = launch { d.syncHistory() }
        job.join()
        assertTrue(job.isCancelled)
        assertTrue("sync ran to the last ack", t.tx.last() == Frame(0x0580, byteArrayOf(0)))
        // Whatever batch the caller might hold (here: an equal one from an independent sync), nothing is cleared.
        val lookalike = driver(FakeTransport(ringScript())).syncHistory()
        d.clearSyncedHistory(lookalike)
        d.clearSyncedHistory(anyBatch)
        assertFalse(t.tx.any { it.type in deleteTypes })
    }

    @Test
    fun clearWithoutPriorSyncIsNoOp() = runTest {
        val t = FakeTransport(ringScript())
        driver(t).clearSyncedHistory(anyBatch)
        assertTrue(t.tx.isEmpty())
    }

    @Test
    fun secondClearIsNoOp() = runTest {
        val t = FakeTransport(ringScript())
        val d = driver(t)
        val batch = d.syncHistory()
        d.clearSyncedHistory(batch)
        val after = t.tx.size
        d.clearSyncedHistory(batch)
        assertEquals(after, t.tx.size)
        assertEquals(6, t.tx.count { it.type in deleteTypes })
    }

    @Test
    fun deleteWithoutReplyDoesNotFailClear() = runTest {
        val silentDelete = (deleteTypes).associateWith { emptyList<ByteArray>() }
        val t = FakeTransport(ringScript(overrides = silentDelete))
        val d = driver(t)
        val batch = d.syncHistory()
        d.clearSyncedHistory(batch)
        assertEquals("one attempt per delete, no retry", 6, t.tx.count { it.type in deleteTypes })
    }

    @Test
    fun liveHeartRateStopsMeasurementOnCancel() = runTest {
        val hrFrames = fixtureRx("realtime_hr").filter { checkNotNull(FrameDecoder.decode(it)).type == 0x0601 }
        val t = FakeTransport { f ->
            when (f.type) {
                0x032F -> if (f.payload[0].toInt() == 1) listOf(FakeTransport.echo(f)) + hrFrames else listOf(FakeTransport.echo(f))
                else -> listOf(FakeTransport.echo(f))
            }
        }
        val values = driver(t).liveHeartRate().take(3).toList()
        assertEquals(listOf(72, 72, 73), values)
        assertEquals(Frame(0x0309, hex("010102")), t.tx.first())
        assertEquals(Frame(0x032F, hex("0000")), t.tx[t.tx.size - 2])
        assertEquals(Frame(0x0309, hex("000102")), t.tx.last())
    }

    @Test
    fun liveHeartRateDropsInvalidReadings() = runTest {
        val frames = listOf(0, 250, 72).map { Frame(0x0601, byteArrayOf(it.toByte())).encode() }
        val t = FakeTransport { f ->
            if (f.type == 0x032F && f.payload[0].toInt() == 1) listOf(FakeTransport.echo(f)) + frames else listOf(FakeTransport.echo(f))
        }
        assertEquals(listOf(72), driver(t).liveHeartRate().take(1).toList())
    }

    @Test
    fun measureHeartRateReturnsLastValidReadingAndStops() = runTest {
        val rx = fixtureRx("realtime_hr").filter { checkNotNull(FrameDecoder.decode(it)).type in setOf(0x0601, 0x040E) }
        val t = FakeTransport { f ->
            if (f.type == 0x032F && f.payload[0].toInt() == 1) listOf(FakeTransport.echo(f)) + rx else listOf(FakeTransport.echo(f))
        }
        val events = driver(t).measure(MeasureKind.HEART_RATE).toList()
        assertEquals(MeasureEvent.Started, events.first())
        assertEquals(MeasureEvent.Result(mapOf(Metric.HEART_RATE to 73.0)), events.last())
        val live = events.drop(1).dropLast(1)
        assertTrue(live.isNotEmpty() && live.all { it is MeasureEvent.Live })
        assertEquals(MeasureEvent.Live(mapOf(Metric.HEART_RATE to 73.0)), live.last())
        assertEquals(
            listOf(Frame(0x0309, hex("010102")), Frame(0x032F, hex("0100")), Frame(0x032F, hex("0000")), Frame(0x0309, hex("000102"))),
            t.tx,
        )
    }

    @Test
    fun measureFailureStatusIsRingError() = runTest {
        val rx = listOf(Frame(0x0601, byteArrayOf(80)).encode(), Frame(0x040E, hex("0000")).encode())
        val t = FakeTransport { f ->
            if (f.type == 0x032F && f.payload[0].toInt() == 1) listOf(FakeTransport.echo(f)) + rx else listOf(FakeTransport.echo(f))
        }
        assertEquals(MeasureEvent.Failed(MeasureFailure.RING_ERROR), driver(t).measure(MeasureKind.HEART_RATE).toList().last())
        assertEquals(Frame(0x0309, hex("000102")), t.tx.last())
    }

    @Test
    fun measureTimesOutAndStillStops() = runTest {
        val t = FakeTransport { f -> listOf(FakeTransport.echo(f)) }
        assertEquals(
            listOf(MeasureEvent.Started, MeasureEvent.Failed(MeasureFailure.TIMEOUT)),
            driver(t).measure(MeasureKind.HEART_RATE).toList(),
        )
        assertEquals(Frame(0x0309, hex("000102")), t.tx.last())
    }

    @Test
    fun measureNoReplyIsRingErrorWithoutStarted() = runTest {
        val t = FakeTransport { emptyList() }
        assertEquals(listOf(MeasureEvent.Failed(MeasureFailure.RING_ERROR)), driver(t).measure(MeasureKind.HEART_RATE).toList())
    }

    @Test
    fun applyMonitoringSetsHeartThenSpo2() = runTest {
        val t = FakeTransport { f -> listOf(FakeTransport.echo(f)) }
        val want = MonitoringConfig(
            mapOf(MonitorKind.SPO2 to MonitorSetting(true, 60), MonitorKind.HEART_RATE to MonitorSetting(true, 10), MonitorKind.HRV to MonitorSetting(true, 30)),
        )
        val accepted = driver(t).applyMonitoring(want)
        assertEquals(listOf(Frame(0x010C, hex("010a")), Frame(0x0126, hex("013c"))), t.tx)
        assertEquals(want.settings - MonitorKind.HRV, accepted.settings) // HRV is not a YCBT monitor: not sent, not accepted
    }

    @Test
    fun readMonitoringIsNullBecauseTheTk5AnswersFc() = runTest {
        assertNull(driver(FakeTransport { f -> listOf(FakeTransport.echo(f)) }).readMonitoring())
    }

    @Test
    fun commandIsRetriedOnceThenFails() = runTest {
        val t = FakeTransport { emptyList() }
        assertSyncFailed { driver(t).handshake(profile) }
        assertEquals(listOf(0x0100, 0x0100), t.tx.map { it.type })
    }

    /** A complete, CRC-valid history transfer carrying [data] for the request [requestType]. */
    private fun transferOf(requestType: Int, data: ByteArray): List<ByteArray> {
        val crc = Crc16.compute(data, data.size)
        val end = byteArrayOf(1, 0, data.size.toByte(), (data.size shr 8).toByte(), crc.toByte(), (crc shr 8).toByte())
        return listOf(Frame(requestType, ByteArray(12)), Frame(0x0515, data), Frame(0x0580, end)).map { it.encode() }
    }

    private fun le(v: Long, n: Int) = ByteArray(n) { ((v shr (8 * it)) and 0xFF).toByte() }

    private val requestToDelete = mapOf(
        0x0504 to 0x0541, 0x0506 to 0x0542, 0x0509 to 0x0544, 0x051A to 0x0545,
        0x0508 to 0x0543, 0x0533 to 0x054E, 0x0502 to 0x0540,
    )
    private val requestToType = mapOf(
        0x0504 to HistoryType.SLEEP, 0x0506 to HistoryType.HEART, 0x0509 to HistoryType.ALL, 0x051A to HistoryType.SPO2,
        0x0508 to HistoryType.BLOOD, 0x0533 to HistoryType.BODY, 0x0502 to HistoryType.SPORT,
    )

    /**
     * A CRC-valid transfer whose payload fails strict decoding: the sync still succeeds, that type is reported in
     * lastDecodeFailures, contributes nothing to the batch and is never deleted; the other types are still cleared.
     */
    private suspend fun assertMalformedTypeSkippedAndNeverDeleted(requestType: Int, data: ByteArray) {
        val t = FakeTransport(ringScript(overrides = mapOf(requestType to transferOf(requestType, data))))
        val d = driver(t)
        val batch = d.syncHistory()
        val type = requestToType.getValue(requestType)
        assertEquals(setOf(type), d.lastDecodeFailures)
        d.clearSyncedHistory(batch)
        val deletes = t.tx.map { it.type }.filter { it in deleteTypes }
        assertFalse("$type must never be deleted", requestToDelete.getValue(requestType) in deletes)
        assertTrue("other types are still cleared", deletes.isNotEmpty())
        assertTrue(batch.samples.isNotEmpty())
    }

    private val sleepStart = 790212600L // 2025-01-14 23:30 ring time

    private fun sleepRecord(len: Int = 36): ByteArray {
        val t0 = sleepStart
        return le(0xAA55, 2) + le(len.toLong(), 2) + le(t0, 4) + le(t0 + 720, 4) + le(2, 2) + ByteArray(6) +
            byteArrayOf(241.toByte()) + le(t0, 4) + le(600, 3) +
            byteArrayOf(244.toByte()) + le(t0 + 600, 4) + le(120, 3)
    }

    @Test
    fun transferHelperRoundTripsWellFormedData() = runTest {
        val t = FakeTransport(ringScript(overrides = mapOf(0x0504 to transferOf(0x0504, sleepRecord()))))
        val batch = driver(t).syncHistory()
        assertEquals(1, batch.sleep.size)
    }

    @Test
    fun truncatedHeartPayloadSkipsHeartAndNeverDeletesIt() = runTest {
        assertMalformedTypeSkippedAndNeverDeleted(0x0506, le(790236000, 4) + byteArrayOf(0, 58) + le(790236060, 4) + byteArrayOf(0))
    }

    @Test
    fun extraSportBytesSkipSportAndNeverDeleteIt() = runTest {
        val bucket = le(790241398, 4) + le(790243198, 4) + le(415, 2) + le(298, 2) + le(17, 2)
        assertMalformedTypeSkippedAndNeverDeleted(0x0502, bucket + ByteArray(3))
    }

    @Test
    fun sleepRecordOverrunSkipsSleepAndNeverDeletesIt() = runTest {
        assertMalformedTypeSkippedAndNeverDeleted(0x0504, sleepRecord(len = 60))
    }

    @Test
    fun sleepRecordShorterThanHeaderSkipsSleepAndNeverDeletesIt() = runTest {
        assertMalformedTypeSkippedAndNeverDeleted(0x0504, sleepRecord(len = 12))
    }

    @Test
    fun leftoverSleepBytesSkipSleepAndNeverDeleteIt() = runTest {
        assertMalformedTypeSkippedAndNeverDeleted(0x0504, sleepRecord() + ByteArray(7))
    }

    @Test
    fun truncatedHeartPayloadKeepsOtherTypesAndTheirSamples() = runTest {
        val t = FakeTransport(ringScript(overrides = mapOf(0x0506 to transferOf(0x0506, ByteArray(7)))))
        val d = driver(t)
        val batch = d.syncHistory()
        assertEquals(setOf(HistoryType.HEART), d.lastDecodeFailures)
        assertTrue(batch.samples.any { it.metric == Metric.STEPS })
        d.clearSyncedHistory(batch)
        assertEquals(listOf(0x0544, 0x0545, 0x0543, 0x054E, 0x0540), t.tx.map { it.type }.filter { it in deleteTypes })
    }

    @Test
    fun decodeFailureInSleepSkipsSleepButSyncsOthers() = runTest {
        val t = FakeTransport(ringScript(overrides = mapOf(0x0504 to transferOf(0x0504, sleepRecord() + ByteArray(7)))))
        val d = driver(t)
        val batch = d.syncHistory()
        assertEquals(setOf(HistoryType.SLEEP), d.lastDecodeFailures)
        assertTrue("no sleep stored from a malformed payload", batch.sleep.isEmpty())
        assertTrue(batch.samples.any { it.metric == Metric.HEART_RATE })
        assertTrue(batch.samples.any { it.metric == Metric.STEPS })
        d.clearSyncedHistory(batch)
        val deletes = t.tx.map { it.type }.filter { it in deleteTypes }
        assertEquals("others cleared, SLEEP not", listOf(0x0542, 0x0544, 0x0545, 0x0543, 0x054E, 0x0540), deletes)
    }

    @Test
    fun decodeFailuresResetOnNextCleanSync() = runTest {
        var bad = true
        val base = ringScript()
        val t = FakeTransport { f -> if (bad && f.type == 0x0506) transferOf(0x0506, ByteArray(7)) else base(f) }
        val d = driver(t)
        d.syncHistory()
        assertEquals(setOf(HistoryType.HEART), d.lastDecodeFailures)
        bad = false
        d.syncHistory()
        assertTrue(d.lastDecodeFailures.isEmpty())
    }

    @Test
    fun sleepIsNeverClearedWhileUnverified() = runTest {
        assertFalse("flip only after a real sleep capture passes (see YcbtDriver.CLEAR_SLEEP_VERIFIED)", YcbtDriver.CLEAR_SLEEP_VERIFIED)
        // Both an empty and a non-empty sleep transfer: neither may lead to the 0x0541 delete.
        for (sleepReply in listOf(historyFixtures.getValue(0x0504), sleepNight, transferOf(0x0504, sleepRecord()))) {
            val t = FakeTransport(ringScript(overrides = mapOf(0x0504 to sleepReply)))
            val d = driver(t)
            val batch = d.syncHistory()
            d.clearSyncedHistory(batch)
            val deletes = t.tx.map { it.type }.filter { it in deleteTypes }
            assertFalse(0x0541 in deletes)
            assertTrue("other verified types are still cleared", 0x0542 in deletes)
        }
    }

    /** Synthetic multi-packet night (two block frames). */
    private val sleepNight = FakeTransport.fixtureReplies("history_sleep_night").getValue(0x0504)

    @Test
    fun syncDecodesMultiPacketSleepNightAndAcksIt() = runTest {
        val t = FakeTransport(ringScript(overrides = mapOf(0x0504 to sleepNight)))
        val batch = driver(t).syncHistory()
        assertEquals(1, batch.sleep.size)
        val s = batch.sleep[0]
        // Ring wall-clock time is read in the driver's zone (Melbourne, UTC+11 in January).
        assertEquals(RingTime.toInstant(790212600L, zone), s.start)
        assertEquals(Instant.parse("2025-01-14T12:30:00Z"), s.start)
        assertEquals(Instant.parse("2025-01-14T20:00:00Z"), s.end)
        assertEquals(14, s.segments.size)
        // The sleep transfer is acked right after the 0x0504 request, before the HEART request.
        val types = t.tx.map { it.type }
        val i = types.indexOf(0x0504)
        assertEquals(listOf(0x0504, 0x0580, 0x0506), types.subList(i, i + 3))
        assertEquals(Frame(0x0580, byteArrayOf(0)), t.tx[i + 1])
    }

    private suspend fun assertSyncFailed(block: suspend () -> Unit) {
        try {
            block()
            fail("expected SyncFailed")
        } catch (e: SyncFailed) {
            // expected
        }
    }
}
