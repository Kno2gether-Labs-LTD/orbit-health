package dev.avijit.orbithealth.ui.sleep

import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Test

class EstimateCopyTest {
    private val zone = ZoneOffset.UTC
    private fun t(s: String) = Instant.parse("2026-11-${s}Z")

    @Test fun nightHeadlineUsesConversationalTimes() {
        assertEquals("Looks like you slept 3:10–10:40. Right?", EstimateCopy.nightHeadline(t("07T03:10:00"), t("07T10:40:00"), zone))
        assertEquals("Looks like you slept 23:40–7:05. Right?", EstimateCopy.nightHeadline(t("06T23:40:00"), t("07T07:05:00"), zone))
    }

    @Test fun nightDetailLeavesOutWhatWasNotMeasured() {
        assertEquals("7h 30m · lowest 54 bpm · SpO₂ low 93%", EstimateCopy.nightDetail(27_000, 54, 93))
        assertEquals("7h 30m · lowest 54 bpm", EstimateCopy.nightDetail(27_000, 54, null))
        assertEquals("7h 30m", EstimateCopy.nightDetail(27_000, null, null))
    }

    @Test fun napHeadline() {
        assertEquals("Nap · 14:10–14:50 · 40 min", EstimateCopy.napHeadline(t("07T14:10:00"), t("07T14:50:00"), zone))
        assertEquals("Nap · 13:00–14:05 · 1h 05m", EstimateCopy.napHeadline(t("07T13:00:00"), t("07T14:05:00"), zone))
    }

    @Test fun stepsStayWithinFourHoursAndKeepTheNightLongEnough() {
        val suggested = t("07T03:00:00")
        assertEquals(t("07T03:10:00"), EstimateCopy.step(suggested, 1, suggested, Instant.MIN, Instant.MAX))
        // Four hours earlier at most.
        assertEquals(t("06T23:00:00"), EstimateCopy.step(t("06T23:00:00"), -1, suggested, Instant.MIN, Instant.MAX))
        // Never past the upper limit (e.g. 30 min before waking).
        assertEquals(t("07T04:00:00"), EstimateCopy.step(t("07T04:00:00"), 1, suggested, Instant.MIN, t("07T04:00:00")))
        assertEquals(Duration.ofMinutes(10), Duration.ofMinutes(EstimateCopy.ADJUST_STEP_MIN))
    }
}
