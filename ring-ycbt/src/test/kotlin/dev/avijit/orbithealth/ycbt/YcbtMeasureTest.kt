package dev.avijit.orbithealth.ycbt

import dev.avijit.orbithealth.ring.MeasureEvent
import dev.avijit.orbithealth.ring.MeasureFailure
import dev.avijit.orbithealth.ring.MeasureFailedException
import dev.avijit.orbithealth.ring.MeasureKind
import dev.avijit.orbithealth.ring.Metric
import dev.avijit.orbithealth.ring.MonitorKind
import dev.avijit.orbithealth.ring.MonitorSetting
import dev.avijit.orbithealth.ring.MonitoringConfig
import dev.avijit.orbithealth.ring.MonitoringNotApplied
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.ZoneId

/** On-demand measurement of every TK5 kind and the automatic-check settings, with the bytes verified on 2026-11-06. */
class YcbtMeasureTest {
    private fun driver(t: FakeTransport) = YcbtDriver(t, zone = { ZoneId.of("UTC") })

    private fun enc(type: Int, hex: String) = Frame(type, hex(hex)).encode()

    /** Echo-acks everything; after the 0x032F start of [kind] it sends [rx]. */
    private fun ring(kind: MeasureKind, rx: List<ByteArray>, startAck: Int = 0) = FakeTransport { f ->
        val ack = Frame(f.type, byteArrayOf(0)).encode()
        if (f.type == 0x032F && f.payload[0].toInt() == 1 && f.payload[1].toInt() == Commands.measureType(kind)) {
            listOf(Frame(0x032F, byteArrayOf(startAck.toByte())).encode()) + if (startAck == 0) rx else emptyList()
        } else {
            listOf(ack)
        }
    }

    private val bp = enc(0x0603, "794f48000000" + "00".repeat(8))
    private val hrv = enc(0x0603, "0000002a" + "00".repeat(10))
    private val spo2 = enc(0x0602, "63")
    private val stress = enc(0x0610, "000000000c030000" + "00".repeat(14))

    @Test
    fun spo2LiveThenResultAndStop() = runTest {
        val t = ring(MeasureKind.SPO2, listOf(enc(0x0602, "62"), spo2, enc(0x040E, "0201")))
        val events = driver(t).measure(MeasureKind.SPO2).toList()
        assertEquals(
            listOf(
                MeasureEvent.Started,
                MeasureEvent.Live(mapOf(Metric.SPO2 to 98.0)),
                MeasureEvent.Live(mapOf(Metric.SPO2 to 99.0)),
                MeasureEvent.Result(mapOf(Metric.SPO2 to 99.0)),
            ),
            events,
        )
        // No 0x0309 realtime switch for anything but heart rate.
        assertEquals(listOf(Frame(0x032F, hex("0102")), Frame(0x032F, hex("0002"))), t.tx)
    }

    @Test
    fun bloodPressureResultCarriesSysDiaAndHeartRate() = runTest {
        val t = ring(MeasureKind.BLOOD_PRESSURE, listOf(bp, enc(0x040E, "0101")))
        val events = driver(t).measure(MeasureKind.BLOOD_PRESSURE).toList()
        val want = mapOf(Metric.BLOOD_PRESSURE_SYS to 121.0, Metric.BLOOD_PRESSURE_DIA to 79.0, Metric.HEART_RATE to 72.0)
        assertEquals(listOf(MeasureEvent.Started, MeasureEvent.Live(want), MeasureEvent.Result(want)), events)
        assertEquals(Frame(0x032F, hex("0001")), t.tx.last())
    }

    @Test
    fun hrvReadsByteThreeOfTheBloodFrame() = runTest {
        val t = ring(MeasureKind.HRV, listOf(hrv, enc(0x040E, "0a01")))
        assertEquals(MeasureEvent.Result(mapOf(Metric.HRV to 42.0)), driver(t).measure(MeasureKind.HRV).toList().last())
        assertEquals(Frame(0x032F, hex("000a")), t.tx.last())
    }

    @Test
    fun hrvWaitsForALateDoneRatherThanStoppingAtSixtySeconds() = runTest {
        // Verified run: the HRV values came as a burst and its 0x040E 0a01 arrived after 60 s.
        val t = ring(MeasureKind.HRV, listOf(hrv))
        val events = async { driver(t).measure(MeasureKind.HRV).toList() }
        advanceTimeBy(70_000)
        assertTrue("still measuring at 70 s", t.tx.none { it == Frame(0x032F, hex("000a")) })
        t.inject(enc(0x040E, "0a01"))
        assertEquals(MeasureEvent.Result(mapOf(Metric.HRV to 42.0)), events.await().last())
        assertEquals(70_000L, currentTime) // ended by the ring's "done", not the clock
        assertEquals(Frame(0x032F, hex("000a")), t.tx.last())
    }

    @Test
    fun stressHasNoLiveValuesOnlyAResult() = runTest {
        val t = ring(MeasureKind.STRESS, listOf(stress, enc(0x040E, "0c01")))
        assertEquals(
            listOf(MeasureEvent.Started, MeasureEvent.Result(mapOf(Metric.STRESS to 12.3))),
            driver(t).measure(MeasureKind.STRESS).toList(),
        )
        assertEquals(Frame(0x032F, hex("000c")), t.tx.last())
    }

    @Test
    fun doneForAnotherKindIsIgnored() = runTest {
        // A stale "HR done" must not end an SpO₂ measurement; its own "done" does.
        val t = ring(MeasureKind.SPO2, listOf(enc(0x040E, "0001"), spo2, enc(0x040E, "0201")))
        assertEquals(MeasureEvent.Result(mapOf(Metric.SPO2 to 99.0)), driver(t).measure(MeasureKind.SPO2).toList().last())
    }

    @Test
    fun cancelSendsStop() = runTest {
        val t = ring(MeasureKind.SPO2, listOf(spo2)) // values, never "done"
        val first = driver(t).measure(MeasureKind.SPO2).first { it is MeasureEvent.Live }
        assertEquals(MeasureEvent.Live(mapOf(Metric.SPO2 to 99.0)), first)
        assertEquals(Frame(0x032F, hex("0002")), t.tx.last())
    }

    @Test
    fun aQueuedCallerRunsOnlyAfterTheStop() = runTest {
        val t = ring(MeasureKind.SPO2, listOf(spo2)) // values, never "done"
        val d = driver(t)
        val measuring = launch { d.measure(MeasureKind.SPO2).collect {} }
        runCurrent()
        val queued = launch {
            d.applyMonitoring(MonitoringConfig(mapOf(MonitorKind.HEART_RATE to MonitorSetting(true, 10))))
        }
        runCurrent()
        assertTrue("no frame while the ring measures", t.tx.none { it.type == 0x010C })
        measuring.cancel()
        queued.join()
        val stop = t.tx.indexOf(Frame(0x032F, hex("0002")))
        val next = t.tx.indexOfFirst { it.type == 0x010C }
        assertTrue("stop $stop before the queued command $next", stop in 0 until next)
    }

    @Test
    fun valuesWithoutDoneGiveAResultAtTypicalSeconds() = runTest {
        val t = ring(MeasureKind.BLOOD_PRESSURE, listOf(bp))
        val events = driver(t).measure(MeasureKind.BLOOD_PRESSURE).toList()
        assertTrue(events.last() is MeasureEvent.Result)
        assertEquals(50_000L, currentTime) // MeasureSpec.typicalSeconds for BP
        assertEquals(Frame(0x032F, hex("0001")), t.tx.last())
    }

    @Test
    fun noValueGivesTimeout() = runTest {
        val t = ring(MeasureKind.HRV, emptyList())
        assertEquals(
            listOf(MeasureEvent.Started, MeasureEvent.Failed(MeasureFailure.TIMEOUT)),
            driver(t).measure(MeasureKind.HRV).toList(),
        )
        assertEquals(105_000L, currentTime) // HRV typicalSeconds 75 + the 30 s grace
        assertEquals(Frame(0x032F, hex("000a")), t.tx.last())
    }

    @Test
    fun nonDoneStatusIsRingError() = runTest {
        val t = ring(MeasureKind.SPO2, listOf(spo2, enc(0x040E, "0203")))
        assertEquals(MeasureEvent.Failed(MeasureFailure.RING_ERROR), driver(t).measure(MeasureKind.SPO2).toList().last())
        assertEquals(Frame(0x032F, hex("0002")), t.tx.last())
    }

    @Test
    fun status02RightAfterStartIsNotWorn() = runTest {
        // Observed 2026-11-06 with the ring off a finger: 0x040E [type, 02] straight after the start ack, no value.
        val t = ring(MeasureKind.HEART_RATE, listOf(enc(0x040E, "0002")))
        val events = driver(t).measure(MeasureKind.HEART_RATE).toList()
        assertEquals(listOf(MeasureEvent.Started, MeasureEvent.Failed(MeasureFailure.NOT_WORN)), events)
        assertEquals(Frame(0x0309, hex("000102")), t.tx.last()) // the stop still goes out
    }

    @Test
    fun startRefusedWithFcIsUnsupported() = runTest {
        val t = ring(MeasureKind.STRESS, emptyList(), startAck = 0xFC)
        assertEquals(listOf(MeasureEvent.Failed(MeasureFailure.UNSUPPORTED)), driver(t).measure(MeasureKind.STRESS).toList())
        assertEquals(Frame(0x032F, hex("000c")), t.tx.last()) // still stopped
    }

    @Test
    fun disconnectWhileMeasuringIsDisconnected() = runTest {
        val t = ring(MeasureKind.SPO2, listOf(spo2))
        t.dropAfter = 0x032F
        assertEquals(MeasureEvent.Failed(MeasureFailure.DISCONNECTED), driver(t).measure(MeasureKind.SPO2).toList().last())
    }

    @Test
    fun liveHeartRateThrowsWhenTheRingRefuses() = runTest {
        val t = ring(MeasureKind.HEART_RATE, emptyList(), startAck = 0x01)
        try {
            driver(t).liveHeartRate().toList()
            fail("expected MeasureFailedException")
        } catch (e: MeasureFailedException) {
            assertEquals(MeasureFailure.RING_ERROR, e.reason)
        }
        assertEquals(listOf(Frame(0x032F, hex("0000")), Frame(0x0309, hex("000102"))), t.tx.takeLast(2))
    }

    // --- automatic checks ---

    private fun acking(acks: Map<Int, Int>) = FakeTransport { f -> listOf(Frame(f.type, byteArrayOf((acks[f.type] ?: 0).toByte())).encode()) }

    @Test
    fun applySendsVerifiedFramesAndReturnsWhatWasAccepted() = runTest {
        val t = acking(emptyMap())
        val want = MonitoringConfig(mapOf(MonitorKind.HEART_RATE to MonitorSetting(false, 15), MonitorKind.SPO2 to MonitorSetting(true, 30)))
        assertEquals(want, driver(t).applyMonitoring(want))
        assertEquals(listOf(Frame(0x010C, hex("000f")), Frame(0x0126, hex("011e"))), t.tx)
    }

    @Test
    fun fcAckDropsTheKind() = runTest {
        val t = acking(mapOf(0x0126 to 0xFC))
        val want = MonitoringConfig(mapOf(MonitorKind.HEART_RATE to MonitorSetting(true, 10), MonitorKind.SPO2 to MonitorSetting(true, 30)))
        assertEquals(MonitoringConfig(mapOf(MonitorKind.HEART_RATE to MonitorSetting(true, 10))), driver(t).applyMonitoring(want))
        assertEquals(2, t.tx.size)
    }

    @Test
    fun otherNonZeroAckIsNotAcceptedAndIsRetryable() = runTest {
        // HR answers 01 (maybe transient), SpO₂ is still sent and accepted; the throw carries both facts.
        val t = acking(mapOf(0x010C to 0x01))
        val want = MonitoringConfig(
            mapOf(MonitorKind.HEART_RATE to MonitorSetting(true, 10), MonitorKind.SPO2 to MonitorSetting(true, 30)),
        )
        try {
            driver(t).applyMonitoring(want)
            fail("expected MonitoringNotApplied")
        } catch (e: MonitoringNotApplied) {
            assertEquals(setOf(MonitorKind.HEART_RATE), e.refused)
            assertEquals(MonitoringConfig(mapOf(MonitorKind.SPO2 to MonitorSetting(true, 30))), e.accepted)
        }
        assertTrue(t.tx.any { it.type == 0x0126 })
    }

    @Test
    fun outOfCapsIntervalIsRejectedBeforeSending() = runTest {
        // The ring would ack 255 or 5 happily; the driver must not send them.
        for (bad in listOf(
            MonitorKind.HEART_RATE to MonitorSetting(true, 5),
            MonitorKind.HEART_RATE to MonitorSetting(true, 255),
            MonitorKind.SPO2 to MonitorSetting(true, 10),
        )) {
            val t = acking(emptyMap())
            val want = MonitoringConfig(mapOf(MonitorKind.SPO2 to MonitorSetting(true, 30), bad))
            try {
                driver(t).applyMonitoring(want)
                fail("expected IllegalArgumentException for $bad")
            } catch (_: IllegalArgumentException) {
            }
            assertTrue(t.tx.isEmpty())
        }
    }

    @Test
    fun unsupportedKindsAreNeverSent() = runTest {
        val t = acking(emptyMap())
        val want = MonitoringConfig(
            mapOf(
                MonitorKind.BLOOD_PRESSURE to MonitorSetting(true, 60),
                MonitorKind.HRV to MonitorSetting(true, 30),
                MonitorKind.TEMPERATURE to MonitorSetting(true, 30),
            ),
        )
        assertEquals(MonitoringConfig.EMPTY, driver(t).applyMonitoring(want))
        assertTrue(t.tx.isEmpty())
    }
}
