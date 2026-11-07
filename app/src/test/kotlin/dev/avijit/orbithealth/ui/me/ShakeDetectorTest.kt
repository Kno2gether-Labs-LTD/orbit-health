package dev.avijit.orbithealth.ui.me

import org.junit.Assert.assertEquals
import org.junit.Test

class ShakeDetectorTest {
    private var shakes = 0
    private val detector = ShakeDetector { shakes++ }

    /** A jolt of about 3 g along one axis. */
    private fun jolt(t: Long) = detector.feed(t, 30f, 0f, 0f)

    @Test fun threeJoltsWithinTwoSecondsIsAShake() {
        jolt(0); jolt(400); jolt(800)
        assertEquals(1, shakes)
    }

    @Test fun phoneAtRestNeverShakes() {
        repeat(100) { detector.feed(it * 20L, 0f, 0f, 9.81f) }
        assertEquals(0, shakes)
    }

    @Test fun oneLongJoltCountsOnce() {
        (0L..200L step 20).forEach(::jolt)
        assertEquals(0, shakes)
    }

    @Test fun slowJoltsSpreadOverTimeNeverShake() {
        jolt(0); jolt(1_500); jolt(3_000); jolt(4_500)
        assertEquals(0, shakes)
    }

    @Test fun countStartsAgainAfterAShake() {
        jolt(0); jolt(400); jolt(800)
        jolt(1_200); jolt(1_600)
        assertEquals(1, shakes)
        jolt(2_000)
        assertEquals(2, shakes)
    }
}
