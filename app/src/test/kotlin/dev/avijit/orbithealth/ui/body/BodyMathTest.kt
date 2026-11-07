package dev.avijit.orbithealth.ui.body

import dev.avijit.orbithealth.ring.Metric
import dev.avijit.orbithealth.ring.Sample
import dev.avijit.orbithealth.ring.SampleSource
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import dev.avijit.orbithealth.ui.today.HrFreshness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Living Skies Body copy and chart helpers: the month sentence, the usual range, by-hour notes and the axes. */
class BodyMathTest {
    private val zone: ZoneId = ZoneOffset.UTC
    private val day: LocalDate = LocalDate.of(2026, 11, 6)
    private fun at(h: Int, m: Int = 0): Instant = day.atTime(LocalTime.of(h, m)).atZone(zone).toInstant()

    /** 30 daily points falling (or rising) linearly by [change] from [from]. */
    private fun line(from: Float, change: Float, n: Int = 30) = (0 until n).map { i -> i.toFloat() to from + change * i / (n - 1) }

    /** The same points one week apart (a year's worth of days). */
    private fun weekly(p: List<Pair<Float, Float>>) = p.map { (i, v) -> i * 7 to v }

    /** Nights alternating ±[firstSwing] min around 7h in the first half and ±[secondSwing] in the second. */
    private fun nights(firstSwing: Float, secondSwing: Float, n: Int = 30, mean: Float = 420f) = (0 until n).map { i ->
        val swing = if (i < n / 2) firstSwing else secondSwing
        i.toFloat() to mean + if (i % 2 == 0) swing else -swing
    }

    @Test fun trendsSentence() {
        // The mockup's month: resting HR down 3 bpm, nights swinging less in the second half.
        assertEquals(
            "Over the last month your heart has settled and your nights are steadier.",
            BodyMath.trendsSentence(Range.MONTH, line(64f, -3f), nights(30f, 8f)),
        )
        // A week of rising resting HR and longer nights.
        assertEquals(
            "Over the last week your resting heart rate has crept up and you're sleeping longer.",
            BodyMath.trendsSentence(Range.WEEK, line(58f, 2f, 7), line(400f, 40f, 7)),
        )
        // Less regular nights, shorter nights.
        assertEquals(
            "Over the last month your heart has held steady and your nights have been less regular.",
            BodyMath.trendsSentence(Range.MONTH, line(60f, 0.2f), nights(8f, 30f)),
        )
        assertEquals(
            "Over the last year your heart has settled and you're sleeping less.",
            BodyMath.trendsSentence(Range.YEAR, weekly(line(64f, -4f, 52)), weekly(line(450f, -40f, 52))),
        )
        // Both steady reads as one clause, not "held steady and ... held steady".
        assertEquals(
            "Over the last month your heart and your sleep have held steady.",
            BodyMath.trendsSentence(Range.MONTH, line(60f, 0.3f), nights(10f, 10f)),
        )
        // One series only, or too little of the other.
        assertEquals("Over the last month your heart has settled.", BodyMath.trendsSentence(Range.MONTH, line(64f, -3f), line(420f, 0f, 3)))
        assertEquals("Over the last month you're sleeping longer.", BodyMath.trendsSentence(Range.MONTH, emptyList(), line(400f, 40f)))
        // A short history doesn't speak for the whole range: it says how many days it covers.
        assertEquals("Over the last 4 days your heart has settled.", BodyMath.trendsSentence(Range.MONTH, line(64f, -3f, 4), emptyList()))
        assertEquals("Over the last 3 days your heart has settled.", BodyMath.trendsSentence(Range.YEAR, line(64f, -3f, 3), emptyList()))
        assertEquals("Over the last 14 days your heart has settled.", BodyMath.trendsSentence(Range.MONTH, line(64f, -3f, 14), emptyList()))
        // Half the range is enough to name it.
        assertEquals("Over the last month your heart has settled.", BodyMath.trendsSentence(Range.MONTH, line(64f, -3f, 15), emptyList()))
        // Nothing yet.
        assertEquals(BodyMath.NO_TRENDS, BodyMath.trendsSentence(Range.MONTH, line(60f, -3f, 2), emptyList()))
    }

    @Test fun heartLatestSaysHowOldTheReadingIs() {
        val saved = java.util.Locale.getDefault()
        java.util.Locale.setDefault(java.util.Locale.UK)
        try {
            val now = at(14, 0)
            fun hr(t: Instant) = Sample(Metric.HEART_RATE, t, 63.6, SampleSource.HISTORY)
            assertEquals(HeartLatest(HrFreshness.NONE, null, ""), BodyMath.heartLatest(null, now, zone))
            // Today: the time alone (current or a few hours old).
            assertEquals(HeartLatest(HrFreshness.CURRENT, 64, "Last reading · 13:20"), BodyMath.heartLatest(hr(at(13, 20)), now, zone))
            assertEquals(HeartLatest(HrFreshness.STALE, 64, "Last reading · 08:05"), BodyMath.heartLatest(hr(at(8, 5)), now, zone))
            // An earlier day names it, so an old reading never reads as today's.
            val tue = day.minusDays(3).atTime(21, 40).atZone(zone).toInstant()
            assertEquals(HeartLatest(HrFreshness.STALE, 64, "Last reading · Tue 3 Nov 21:40"), BodyMath.heartLatest(hr(tue), now, zone))
            // Over 7 days: no value (a dash), and the hero stops beating.
            val old = day.minusDays(8).atTime(9, 0).atZone(zone).toInstant()
            assertEquals(HeartLatest(HrFreshness.HIDDEN, null, BodyMath.NO_RECENT_HR), BodyMath.heartLatest(hr(old), now, zone))
            // The deps zone decides the day and the time, not the device's.
            val kolkata = ZoneId.of("Asia/Kolkata")
            assertEquals("Last reading · 13:50", BodyMath.heartLatest(hr(at(8, 20)), now, kolkata).caption)
        } finally {
            java.util.Locale.setDefault(saved)
        }
    }

    @Test fun usualDaytimeRange() {
        val week = (0 until 40).map { 60.0 + it * 0.75 } // 60..89
        assertEquals("Within your usual daytime range", BodyMath.usualRangeLine(73.0, week))
        assertEquals("Above your usual daytime range", BodyMath.usualRangeLine(96.0, week))
        assertEquals("Below your usual daytime range", BodyMath.usualRangeLine(55.0, week))
        // Too few readings to know what's usual.
        assertNull(BodyMath.usualRangeLine(73.0, week.take(10)))
    }

    @Test fun heartFeetCompareWithLastMonthAndYourNormal() {
        assertEquals("↓ 3 vs last month", BodyMath.restingFoot(-3))
        assertEquals("↑ 2 vs last month", BodyMath.restingFoot(2))
        assertEquals("Same as last month", BodyMath.restingFoot(0))
        assertEquals("Today", BodyMath.restingFoot(null))
        assertEquals("↑ Above your normal", BodyMath.hrvFoot(4))
        assertEquals("↓ Below your normal", BodyMath.hrvFoot(-5))
        assertEquals("At your normal", BodyMath.hrvFoot(2))
        assertEquals("Today", BodyMath.hrvFoot(null))
        // Week view: the minis are 7-day means, so the foot names the period, never "Today".
        assertEquals("Last 7 days", BodyMath.restingFoot(null, week = true))
        assertEquals("Last 7 days", BodyMath.hrvFoot(null, week = true))
        assertEquals("↓ 3 vs last month", BodyMath.restingFoot(-3, week = true))
    }

    @Test fun byHourNotes() {
        val hours = IntArray(24).also { it[7] = 62; it[8] = 148; it[9] = 210; it[10] = 96; it[11] = 398; it[14] = 1250 }
        assertEquals("Busiest 14:00 · 1,250", BodyMath.busiestLabel(hours))
        assertEquals("", BodyMath.busiestLabel(IntArray(24)))
        // Hours with 250 steps or more count as active hours.
        assertEquals(2, BodyMath.activeHours(hours))
    }

    @Test fun heartChartPlacesTodayOnTheClockWithNowAtTheEnd() {
        val hr = (0..35).map { Sample(Metric.HEART_RATE, at(6).plusSeconds(it * 600L), 60.0 + it % 7, SampleSource.HISTORY) }
        val chart = BodyMath.heartChart(HeartUiState(range = Range.DAY, hrToday = hr), at(11, 52), zone)
        assertEquals(listOf("06:00", "08:00", "10:00", "Now"), chart.axis.map { it.second })
        assertEquals(0f, chart.axis.first().first, 1e-4f)
        assertEquals(2f / (5f + 52f / 60f), chart.axis[1].first, 1e-3f)
        assertEquals(1f, chart.axis.last().first, 1e-4f)
        assertEquals(0f, chart.points.first().first, 1e-4f)
        assertTrue(chart.points.last().first in 0.98f..1f)

        // The week: one point per day at its place, labelled through to "Today".
        val week = BodyMath.heartChart(
            HeartUiState(range = Range.WEEK, hrWeek = listOf(0f to 70f, 2f to 72f, 6f to 69f)), at(11, 52), zone,
        )
        assertEquals(listOf(0f, 1f / 3, 1f), week.points.map { it.first })
        assertEquals(listOf("Sat", "Mon", "Wed", "Today"), week.axis.map { it.second })

        assertTrue(BodyMath.heartChart(HeartUiState(), at(11, 52), zone).points.isEmpty())
    }

    @Test fun trendAxisNeverRepeatsADate() {
        val end = LocalDate.of(2026, 11, 6)
        assertEquals(emptyList<LocalDate>(), BodyMath.trendAxis(0, end))
        assertEquals(listOf(end), BodyMath.trendAxis(1, end))
        assertEquals(listOf(end.minusDays(1), end), BodyMath.trendAxis(2, end))
        assertEquals(listOf(end.minusDays(2), end.minusDays(1), end), BodyMath.trendAxis(3, end))
        assertEquals(listOf(end.minusDays(29), end.minusDays(15), end), BodyMath.trendAxis(30, end))
        assertEquals(listOf(1f), BodyMath.trendAxisPositions(1))
        assertEquals(listOf(0f, 1f), BodyMath.trendAxisPositions(2))
        assertEquals(listOf(0f, 0.5f, 1f), BodyMath.trendAxisPositions(3))
    }

    @Test fun trendBarsGetAFixedSlotCountPerRange() {
        assertEquals(7, BodyMath.trendBarSlots(Range.WEEK))
        assertEquals(30, BodyMath.trendBarSlots(Range.MONTH))
        assertEquals(53, BodyMath.trendBarSlots(Range.YEAR))
    }
}
