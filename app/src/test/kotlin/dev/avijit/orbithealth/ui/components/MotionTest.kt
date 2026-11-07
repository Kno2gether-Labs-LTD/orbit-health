package dev.avijit.orbithealth.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionTest {

    @Test
    fun animatedNumberEndsExactlyOnTarget() {
        // Count-up from 0 (first show) and a count-down after a value change both land exactly on the target.
        assertEquals(78, Motion.countUpValue(from = 0, to = 78, fraction = 1f))
        assertEquals(12, Motion.countUpValue(from = 78, to = 12, fraction = 1f))
        assertEquals(9200, Motion.countUpValue(from = 914, to = 9200, fraction = 1f))
        // Overshooting fractions (spring-like callers) never pass the target, and start is exact.
        assertEquals(78, Motion.countUpValue(from = 0, to = 78, fraction = 1.2f))
        assertEquals(0, Motion.countUpValue(from = 0, to = 78, fraction = 0f))
    }

    @Test
    fun animatedNumberIsMonotonic() {
        val up = (0..600).map { Motion.countUpValue(0, 78, it / 600f) }
        assertTrue("count-up must never step back", up.zipWithNext().all { (a, b) -> b >= a })
        assertEquals(78, up.last())
        val down = (0..600).map { Motion.countUpValue(78, 12, it / 600f) }
        assertTrue("count-down must never step back up", down.zipWithNext().all { (a, b) -> b <= a })
        assertEquals(12, down.last())
    }

    @Test
    fun countUpEasesOut() {
        // Ease-out: most of the distance is covered in the first half, so the number settles gently on its value.
        assertTrue(Motion.countUpValue(0, 100, 0.5f) > 70)
    }

    @Test
    fun staggerIsFortyMillisecondsPerCard() {
        assertEquals(0, Motion.staggerDelayMs(0))
        assertEquals(40, Motion.staggerDelayMs(1))
        assertEquals(200, Motion.staggerDelayMs(5))
        // Long lists stop staggering so late cards are not left waiting.
        assertEquals(Motion.staggerDelayMs(Motion.MAX_STAGGERED), Motion.staggerDelayMs(40))
    }

    @Test
    fun animationsOffWhenSystemScaleIsZero() {
        assertFalse(Motion.animationsEnabled(0f))
        assertTrue(Motion.animationsEnabled(1f))
        assertTrue(Motion.animationsEnabled(0.5f))
    }
}
