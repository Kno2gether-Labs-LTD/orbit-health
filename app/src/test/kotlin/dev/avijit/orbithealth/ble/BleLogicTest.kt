package dev.avijit.orbithealth.ble

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class BleLogicTest {
    @Test
    fun timeoutBecomesIoException() {
        val t = runBlocking { runCatching { withTimeout(1) { kotlinx.coroutines.awaitCancellation() } }.exceptionOrNull()!! }
        assertTrue(t is TimeoutCancellationException)
        val m = mapConnectFailure(t)
        assertTrue(m is IOException && m.cause === t)
    }

    @Test
    fun plainCancellationPassesThrough() {
        val c = CancellationException("cancelled")
        assertSame(c, mapConnectFailure(c))
    }

    @Test
    fun otherFailuresWrapped() {
        val io = IOException("x")
        assertSame(io, mapConnectFailure(io))
        val m = mapConnectFailure(IllegalStateException("boom"))
        assertTrue(m is IOException && m.cause is IllegalStateException)
    }

    @Test
    fun rssiThreshold() {
        assertTrue(RingScanner.shouldEmit(null, -60))
        assertFalse(RingScanner.shouldEmit(-60, -62))
        assertFalse(RingScanner.shouldEmit(-60, -60))
        assertTrue(RingScanner.shouldEmit(-60, -63))
        assertTrue(RingScanner.shouldEmit(-60, -57))
        assertEquals(3, RingScanner.RSSI_DELTA_DBM)
    }
}
