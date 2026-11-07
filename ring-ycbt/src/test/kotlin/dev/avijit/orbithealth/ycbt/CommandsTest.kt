package dev.avijit.orbithealth.ycbt

import dev.avijit.orbithealth.ring.MeasureKind
import dev.avijit.orbithealth.ring.Sex
import dev.avijit.orbithealth.ring.UserProfile
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class CommandsTest {
    @Test fun deviceInfoBytes() {
        assertEquals("0200080047436fec", Commands.deviceInfo().encode().toHex())
    }

    @Test fun measureHrStart() {
        val f = Commands.measure(true, MeasureKind.HEART_RATE)
        assertEquals(0x032F, f.type)
        assertArrayEquals(byteArrayOf(1, 0), f.payload)
    }

    @Test fun measureTypes() {
        assertEquals(2, Commands.measure(false, MeasureKind.SPO2).payload[1].toInt())
        assertEquals(10, Commands.measure(false, MeasureKind.HRV).payload[1].toInt())
        assertEquals(12, Commands.measure(false, MeasureKind.STRESS).payload[1].toInt())
    }

    @Test fun measureStartAndStopForEveryKind() {
        val types = mapOf(
            MeasureKind.HEART_RATE to "00", MeasureKind.BLOOD_PRESSURE to "01", MeasureKind.SPO2 to "02",
            MeasureKind.HRV to "0a", MeasureKind.STRESS to "0c",
        )
        for ((kind, t) in types) {
            assertEquals(Frame(0x032F, hex("01$t")), Commands.measure(true, kind))
            assertEquals(Frame(0x032F, hex("00$t")), Commands.measure(false, kind))
            assertEquals(t.toInt(16), Commands.measureType(kind))
        }
    }

    @Test fun monitorFramesMatchTheVerifiedBytes() {
        // Verified on the TK5 2026-11-06: HR [01, 0a] and SpO₂ [01, 1e] (30 min), both acked 00.
        assertEquals(Frame(0x010C, hex("010a")), Commands.setHeartMonitor(true, 10))
        assertEquals(Frame(0x010C, hex("000a")), Commands.setHeartMonitor(false, 10)) // off = [0, interval]
        assertEquals(Frame(0x0126, hex("011e")), Commands.setSpo2Monitor(true, 30))
        assertEquals(Frame(0x0126, hex("003c")), Commands.setSpo2Monitor(false, 60))
    }

    @Test fun setTimeEncodesSundayAs6() {
        val f = Commands.setTime(LocalDateTime.of(2026, 11, 8, 10, 0).atZone(ZoneId.of("UTC")))
        assertEquals(0x0100, f.type)
        assertEquals(6, f.payload[7].toInt())
        assertEquals(0, Commands.setTime(LocalDateTime.of(2026, 11, 9, 10, 0).atZone(ZoneId.of("UTC"))).payload[7].toInt())
        assertEquals("ea070b080a000006", f.payload.toHex())
    }

    @Test fun setTimeZonePayload() {
        val now = Instant.parse("2026-07-01T00:00:00Z")
        val f = Commands.setTimeZone(now, ZoneId.of("Europe/London"))
        assertEquals(0x0144, f.type)
        assertEquals(9, f.payload.size)
        assertEquals(now.epochSecond, java.nio.ByteBuffer.wrap(f.payload, 0, 8).order(java.nio.ByteOrder.LITTLE_ENDIAN).long)
        assertEquals(1, f.payload[8].toInt())
        assertEquals(-5, Commands.setTimeZone(Instant.parse("2026-01-01T00:00:00Z"), ZoneId.of("America/New_York")).payload[8].toInt())
    }

    @Test fun setUserInfo() {
        assertArrayEquals(byteArrayOf(170.toByte(), 60, 1, 30), Commands.setUserInfo(UserProfile(170, 60, Sex.FEMALE, 30)).payload)
        assertEquals(0, Commands.setUserInfo(UserProfile(170, 60, Sex.UNSPECIFIED, 30)).payload[2].toInt())
        assertEquals(0x0103, Commands.setUserInfo(UserProfile(170, 60, Sex.MALE, 30)).type)
    }

    @Test fun monitors() {
        assertEquals(0x010C, Commands.setHeartMonitor(true, 10).type)
        assertArrayEquals(byteArrayOf(1, 10), Commands.setHeartMonitor(true, 10).payload)
        assertEquals(0x0126, Commands.setSpo2Monitor(false, 30).type)
        assertArrayEquals(byteArrayOf(0, 30), Commands.setSpo2Monitor(false, 30).payload)
    }

    @Test fun realtime() {
        val f = Commands.realtime(true, 1)
        assertEquals(0x0309, f.type)
        assertArrayEquals(byteArrayOf(1, 1, 2), f.payload)
    }

    @Test fun historyFrames() {
        assertEquals(0x0541, Commands.historyDelete(HistoryType.SLEEP).type)
        assertArrayEquals(byteArrayOf(2), Commands.historyDelete(HistoryType.SLEEP).payload)
        assertEquals(0x0504, Commands.historyRequest(HistoryType.SLEEP).type)
        assertEquals(0, Commands.historyRequest(HistoryType.SLEEP).payload.size)
        assertEquals(0x051A, Commands.historyRequest(HistoryType.SPO2).type)
        assertEquals(0x054E, Commands.historyDelete(HistoryType.BODY).type)
        assertEquals(0x0580, Commands.historyAck(true).type)
        assertArrayEquals(byteArrayOf(0), Commands.historyAck(true).payload)
        assertArrayEquals(byteArrayOf(4), Commands.historyAck(false).payload)
    }

    @Test fun ringTimeAcrossDstUsesPerTimestampOffset() {
        val london = ZoneId.of("Europe/London")
        fun raw(s: String) = LocalDateTime.parse(s).toEpochSecond(ZoneOffset.UTC) - 946684800L
        assertEquals(Instant.parse("2026-10-24T11:00:00Z"), RingTime.toInstant(raw("2026-10-24T12:00:00"), london))
        assertEquals(Instant.parse("2026-10-26T12:00:00Z"), RingTime.toInstant(raw("2026-10-26T12:00:00"), london))
    }
}
