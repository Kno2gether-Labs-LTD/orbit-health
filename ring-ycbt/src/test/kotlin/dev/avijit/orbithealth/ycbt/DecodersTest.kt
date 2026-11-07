package dev.avijit.orbithealth.ycbt

import dev.avijit.orbithealth.ring.MeasureKind
import dev.avijit.orbithealth.ring.MeasureSpec
import dev.avijit.orbithealth.ring.Metric
import dev.avijit.orbithealth.ring.MonitorKind
import dev.avijit.orbithealth.ring.MonitorSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DecodersTest {
    private fun payloadOf(name: String, type: Int) =
        fixtureRx(name).mapNotNull { FrameDecoder.decode(it) }.first { it.type == type }.payload

    @Test fun deviceInfoFromFixture() {
        val info = decodeDeviceInfo(payloadOf("device_info", 0x0200))
        assertEquals("1.18", info.firmware)
        assertEquals(0x00a3, info.deviceId)
        assertEquals(62, info.batteryPct) // invented level in the synthetic fixture
    }

    @Test fun capabilitiesFromFixture() {
        val c = decodeCapabilities(payloadOf("support_function", 0x0201))
        assertTrue(c.history.containsAll(setOf(Metric.HEART_RATE, Metric.HRV, Metric.SPO2, Metric.STRESS,
            Metric.BLOOD_PRESSURE_SYS, Metric.BLOOD_PRESSURE_DIA, Metric.STEPS, Metric.DISTANCE_M, Metric.CALORIES_KCAL)))
        assertFalse(Metric.RESPIRATORY_RATE in c.history)
        assertTrue(c.sleep)
        assertTrue(c.workouts)
        assertTrue(c.realtime)
    }

    @Test fun capabilityBitPositionsArePinned() {
        fun with(idx: Int, bit: Int): ByteArray = ByteArray(23).also { it[idx] = (1 shl bit).toByte() }
        assertEquals(0 to 7, CapabilityBits.STEP_COUNT)
        assertEquals(0 to 6, CapabilityBits.SLEEP)
        assertEquals(0 to 5, CapabilityBits.REAL_DATA)
        assertEquals(0 to 3, CapabilityBits.HEART_RATE)
        assertEquals(0 to 0, CapabilityBits.BLOOD)
        assertEquals(1 to 3, CapabilityBits.BLOOD_OXYGEN)
        assertEquals(1 to 2, CapabilityBits.RESPIRATORY_RATE)
        assertEquals(1 to 1, CapabilityBits.HRV)
        assertEquals(1 to 0, CapabilityBits.MORE_SPORT)
        assertEquals(11 to 7, CapabilityBits.RUNNING)
        assertEquals(22 to 6, CapabilityBits.PRESSURE)
        // each single bit toggles exactly its own capability
        assertEquals(setOf(Metric.STEPS, Metric.DISTANCE_M, Metric.CALORIES_KCAL), decodeCapabilities(with(0, 7)).history)
        assertTrue(decodeCapabilities(with(0, 6)).sleep)
        assertTrue(decodeCapabilities(with(0, 5)).realtime)
        assertEquals(setOf(Metric.HEART_RATE), decodeCapabilities(with(0, 3)).history)
        assertEquals(setOf(Metric.BLOOD_PRESSURE_SYS, Metric.BLOOD_PRESSURE_DIA), decodeCapabilities(with(0, 0)).history)
        assertEquals(setOf(Metric.SPO2), decodeCapabilities(with(1, 3)).history)
        assertEquals(setOf(Metric.RESPIRATORY_RATE), decodeCapabilities(with(1, 2)).history)
        assertEquals(setOf(Metric.HRV), decodeCapabilities(with(1, 1)).history)
        assertTrue(decodeCapabilities(with(1, 0)).workouts)
        assertTrue(decodeCapabilities(with(11, 7)).workouts)
        assertEquals(setOf(Metric.STRESS), decodeCapabilities(with(22, 6)).history)
        assertFalse(decodeCapabilities(ByteArray(23)).let { it.sleep || it.workouts || it.realtime || it.history.isNotEmpty() })
    }

    @Test fun shortCapabilityPayloadDoesNotCrash() {
        val c = decodeCapabilities(hex("f90b"))
        assertTrue(Metric.HEART_RATE in c.history)
        assertFalse(Metric.STRESS in c.history)
    }

    @Test fun realtimeHrFromFixture() {
        val vals = fixtureRx("realtime_hr").mapNotNull { FrameDecoder.decode(it) }
            .filter { it.type == 0x0601 }.map { decodeRealtimeHeart(it.payload) }
        assertEquals(listOf(72, 72, 73, 74, 74, 75, 75, 74, 73, 73), vals)
        assertNull(decodeRealtimeHeart(ByteArray(0)))
    }

    @Test fun measureStatusFromFixture() {
        assertEquals(MeasureKind.HEART_RATE to MEASURE_DONE, decodeMeasureStatus(payloadOf("measure_result", 0x040e)))
        assertEquals(MeasureKind.SPO2 to 0, decodeMeasureStatus(hex("0200")))
        assertEquals(null to 1, decodeMeasureStatus(hex("6301")))
        assertNull(decodeMeasureStatus(hex("02")))
    }

    @Test fun pedometerKnownBytes() =
        assertEquals(Triple(1205, 850, 48), decodePedometer(hex("07b50400520300300000")))

    @Test fun invalidValuesAreDropped() {
        assertFalse(Validity.hr(0)); assertFalse(Validity.hr(255)); assertFalse(Validity.spo2(0))
        assertFalse(Validity.bp(0, 0)); assertFalse(Validity.bp(80, 120))
        assertTrue(Validity.hr(72)); assertTrue(Validity.spo2(98)); assertTrue(Validity.bp(120, 80))
        assertTrue(Validity.hrv(40)); assertFalse(Validity.hrv(0)); assertTrue(Validity.stress(50)); assertFalse(Validity.stress(101))
    }

    // Frame layouts verified on the TK5 (docs/protocol/ycbt-settings.md); the values here are invented.
    private val bpFrame = Frame(0x0603, hex("794f48000000" + "00".repeat(8)))
    private val hrvFrame = Frame(0x0603, hex("0000002a" + "00".repeat(10)))
    private val spo2Frame = Frame(0x0602, hex("61"))
    private val stressFrame = Frame(0x0610, hex("000000000c030000" + "00".repeat(14)))

    @Test fun verifiedBloodPressureBytes() {
        assertEquals(BloodReading(121, 79, 72, 0), decodeRealtimeBlood(bpFrame.payload))
        assertEquals(
            mapOf(Metric.BLOOD_PRESSURE_SYS to 121.0, Metric.BLOOD_PRESSURE_DIA to 79.0, Metric.HEART_RATE to 72.0),
            decodeMeasureValue(MeasureKind.BLOOD_PRESSURE, bpFrame),
        )
        // HR is added only when the ring sent one.
        assertEquals(
            mapOf(Metric.BLOOD_PRESSURE_SYS to 121.0, Metric.BLOOD_PRESSURE_DIA to 79.0),
            decodeMeasureValue(MeasureKind.BLOOD_PRESSURE, Frame(0x0603, hex("794f00" + "00".repeat(11)))),
        )
        assertNull(decodeMeasureValue(MeasureKind.BLOOD_PRESSURE, hrvFrame)) // zeros: no BP yet
    }

    @Test fun verifiedHrvBytes() {
        assertEquals(42, decodeRealtimeBlood(hrvFrame.payload)!!.hrv)
        assertEquals(mapOf(Metric.HRV to 42.0), decodeMeasureValue(MeasureKind.HRV, hrvFrame))
    }

    @Test fun verifiedSpo2Bytes() {
        assertEquals(97, decodeRealtimeSpo2(spo2Frame.payload))
        assertEquals(mapOf(Metric.SPO2 to 97.0), decodeMeasureValue(MeasureKind.SPO2, spo2Frame))
        assertNull(decodeRealtimeSpo2(ByteArray(0)))
        assertNull(decodeMeasureValue(MeasureKind.SPO2, Frame(0x0602, hex("00"))))
    }

    @Test fun verifiedStressBytes() {
        assertEquals(12.3, decodeRealtimeStress(stressFrame.payload)!!, 1e-9)
        assertEquals(mapOf(Metric.STRESS to 12.3), decodeMeasureValue(MeasureKind.STRESS, stressFrame))
        assertNull(decodeRealtimeStress(hex("0000000007")))
    }

    @Test fun stressFractionUnderTenIsTenths() {
        assertEquals(7.0, decodeRealtimeStress(hex("000000000700"))!!, 1e-9)
        assertEquals(7.9, decodeRealtimeStress(hex("000000000709"))!!, 1e-9)
    }

    @Test fun stressFractionTenOrMoreIsHundredths() {
        // The old string join read 07 0b as "7.11" but 07 0a as "7.10" = 7.1 and 07 64 as "7.100"; hundredths is monotonic.
        assertEquals(7.10, decodeRealtimeStress(hex("00000000070a"))!!, 1e-9)
        assertEquals(7.11, decodeRealtimeStress(hex("00000000070b"))!!, 1e-9)
        assertEquals(7.45, decodeRealtimeStress(hex("00000000072d"))!!, 1e-9)
        assertEquals(7.99, decodeRealtimeStress(hex("000000000763"))!!, 1e-9)
    }

    @Test fun valueFramesOfAnotherKindAreIgnored() {
        assertNull(decodeMeasureValue(MeasureKind.HEART_RATE, spo2Frame))
        assertNull(decodeMeasureValue(MeasureKind.SPO2, bpFrame))
        assertNull(decodeMeasureValue(MeasureKind.STRESS, bpFrame))
        assertEquals(mapOf(Metric.HEART_RATE to 72.0), decodeMeasureValue(MeasureKind.HEART_RATE, Frame(0x0601, hex("48"))))
    }

    @Test fun measureStatusKeepsTheRawStatus() {
        assertEquals(MeasureKind.BLOOD_PRESSURE to 1, decodeMeasureStatus(hex("0101")))
        assertEquals(MeasureKind.SPO2 to 1, decodeMeasureStatus(hex("0201")))
        assertEquals(MeasureKind.HRV to 1, decodeMeasureStatus(hex("0a01")))
        assertEquals(MeasureKind.STRESS to 1, decodeMeasureStatus(hex("0c01")))
        assertEquals(MeasureKind.HEART_RATE to 2, decodeMeasureStatus(hex("0002")))
        assertNull(decodeMeasureStatus(hex("01")))
    }

    @Test fun tk5DeclaresAllFiveMeasuresAndTwoMonitors() {
        val c = decodeCapabilities(payloadOf("support_function", 0x0201))
        assertEquals(YcbtDefaults.MEASURE, c.measure)
        assertEquals(MeasureSpec(30, live = true, estimate = false), c.measure[MeasureKind.HEART_RATE])
        assertEquals(MeasureSpec(45, live = true, estimate = false), c.measure[MeasureKind.SPO2])
        assertEquals(MeasureSpec(75, live = true, estimate = false), c.measure[MeasureKind.HRV])
        assertEquals(MeasureSpec(60, live = false, estimate = true), c.measure[MeasureKind.STRESS])
        assertEquals(MeasureSpec(50, live = true, estimate = true), c.measure[MeasureKind.BLOOD_PRESSURE])
        assertEquals(
            mapOf(
                MonitorKind.HEART_RATE to MonitorSpec(listOf(10, 15, 20, 30, 60), canTurnOff = true, supportsWindow = false),
                MonitorKind.SPO2 to MonitorSpec(listOf(30, 60), canTurnOff = true, supportsWindow = false),
            ),
            c.monitoring,
        )
        // A ring without the HRV / pressure / blood bits doesn't offer those measurements.
        assertEquals(setOf(MeasureKind.HEART_RATE), decodeCapabilities(hex("08")).measure.keys)
    }
}
