package dev.avijit.orbithealth.ycbt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FrameTest {
    @Test fun encodesDeviceInfoRequestExactly() =
        assertEquals("0200080047436fec", Frame(0x0200, hex("4743")).encode().toHex())

    @Test fun encodesEmptyPayload() =
        assertEquals("02200600c845", Frame(0x0220, ByteArray(0)).encode().toHex())

    @Test fun decodesFixtureDeviceInfoFrame() {
        /* first rx frame of fixtures/device_info.json */
        val f = FrameDecoder.decode(fixtureRx("device_info")[0])!!
        assertEquals(0x0200, f.type)
        assertEquals("a3001201003e000100030000000001000000010000000000", f.payload.toHex())
    }

    @Test fun everyFixtureFrameIsCrcValid() {
        val names = listOf("device_info", "support_function", "realtime_hr", "measure_result", "history_sport",
            "history_sleep", "history_sleep_night", "history_heart", "history_blood", "history_all", "history_spo2", "history_body")
        for (n in names) for (f in fixtureFrames(n)) {
            val d = FrameDecoder.decode(f.bytes)
            assertEquals("$n ${f.bytes.toHex()}", f.bytes.toHex(), d?.encode()?.toHex())
        }
    }

    @Test fun rejectsCorruptCrc() {
        val b = Frame(0x0200, hex("4743")).encode()
        b[b.size - 1] = 0
        assertNull(FrameDecoder.decode(b))
    }

    @Test fun rejectsFewerThanSixBytes() {
        assertNull(FrameDecoder.decode(ByteArray(5)))
        assertNull(FrameDecoder.decode(ByteArray(0)))
    }

    @Test fun rejectsDeclaredLengthMismatch() {
        val b = Frame(0x0200, hex("4743")).encode()
        assertNull(FrameDecoder.decode(b.copyOf(b.size - 1)))
        assertNull(FrameDecoder.decode(b + byteArrayOf(0)))
    }

    @Test fun roundTripsPayloadAndType() {
        val f = Frame(0x1234, hex("00ff10"))
        assertEquals(f, FrameDecoder.decode(f.encode()))
    }
}
