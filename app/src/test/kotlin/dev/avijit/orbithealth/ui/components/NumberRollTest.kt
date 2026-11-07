package dev.avijit.orbithealth.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tokens §6 number roll: per-digit 18dp slide, 360 ms, 30 ms stagger from the rightmost changed digit. */
class NumberRollTest {
    @Test fun onlyChangedDigitsRollStaggeredFromTheRight() {
        // 72 -> 74: only the units digit moves, immediately.
        assertEquals(listOf(null, 0), NumberRoll.delays("72", "74"))
        // 68 -> 71: both move, units first, tens 30 ms later.
        assertEquals(listOf(30, 0), NumberRoll.delays("68", "71"))
        // 99 -> 100: a new leading digit slides in last.
        assertEquals(listOf(60, 30, 0), NumberRoll.delays("99", "100"))
        // Grouped thousands: the comma stays put, digits around it stagger.
        assertEquals(listOf(null, null, null, 30, 0), NumberRoll.delays("8,214", "8,251"))
        // Same text: nothing rolls.
        assertEquals(listOf(null, null), NumberRoll.delays("84", "84"))
    }

    @Test fun directionFollowsTheValue() {
        assertTrue(NumberRoll.rollsUp("72", "74"))
        assertFalse(NumberRoll.rollsUp("74", "72"))
        assertTrue(NumberRoll.rollsUp("9,980", "10,020"))
        // From a placeholder: rolls up into the first reading.
        assertTrue(NumberRoll.rollsUp("—", "64"))
    }

    @Test fun specMatchesTokens() {
        assertEquals(18, NumberRoll.SLIDE_DP)
        assertEquals(360, NumberRoll.DURATION_MS)
        assertEquals(30, NumberRoll.STAGGER_MS)
    }
}
