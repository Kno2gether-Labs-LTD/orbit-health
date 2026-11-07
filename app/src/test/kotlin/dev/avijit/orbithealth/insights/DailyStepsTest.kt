package dev.avijit.orbithealth.insights

import dev.avijit.orbithealth.ring.Metric
import dev.avijit.orbithealth.ring.Sample
import dev.avijit.orbithealth.ring.SampleSource
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class DailyStepsTest {
    private val t0 = Instant.parse("2026-11-05T09:30:00Z")
    private fun s(m: Metric, minutes: Long, v: Double) = Sample(m, t0.plusSeconds(minutes * 60), v, SampleSource.HISTORY)

    @Test fun bucketsOnlySumTheIntervals() {
        val buckets = listOf(s(Metric.STEPS, 0, 58.0), s(Metric.STEPS, 30, 17.0), s(Metric.STEPS, 60, 0.0), s(Metric.STEPS, 90, 705.0))
        assertEquals(780, DailySteps.of(buckets, emptyList()))
    }

    @Test fun dayTotalWinsWhenOlderBucketsWereClearedElsewhere() {
        // The vendor app cleared the morning's buckets; the ring's running total still knows them.
        val buckets = listOf(s(Metric.STEPS, 60, 120.0), s(Metric.STEPS, 90, 80.0))
        val totals = listOf(s(Metric.STEPS_DAY_TOTAL, 30, 914.0), s(Metric.STEPS_DAY_TOTAL, 150, 1218.0), s(Metric.STEPS_DAY_TOTAL, 90, 1071.0))
        assertEquals(1218, DailySteps.of(buckets, totals))
    }

    @Test fun bucketsWinWhenTheTotalIsStale() {
        val buckets = listOf(s(Metric.STEPS, 0, 900.0), s(Metric.STEPS, 30, 400.0))
        val totals = listOf(s(Metric.STEPS_DAY_TOTAL, 0, 914.0))
        assertEquals(1300, DailySteps.of(buckets, totals))
    }

    @Test fun totalJustAfterMidnightCarriesOverToThePreviousDay() {
        val zone = java.time.ZoneId.of("Europe/London")
        val day = java.time.LocalDate.of(2026, 11, 5)
        val start = day.atStartOfDay(zone).toInstant()
        val end = day.plusDays(1).atStartOfDay(zone).toInstant()
        fun total(at: Instant, v: Double) = Sample(Metric.STEPS_DAY_TOTAL, at, v, SampleSource.HISTORY)
        val yesterdaysLast = total(start.plusSeconds(5 * 60), 9000.0) // 00:05: yesterday's final count, stamped late
        val edge = total(start.plusSeconds(15 * 60), 40.0) // 00:15: today's own
        val tonightsLast = total(end.plusSeconds(10 * 60), 8100.0) // 00:10 tomorrow: today's final count
        val all = listOf(yesterdaysLast, edge, total(start.plusSeconds(9 * 3600), 700.0), tonightsLast)
        assertEquals(listOf(edge, all[2], tonightsLast), DailySteps.totalsForDay(all, start, end))
        assertEquals(day.minusDays(1), DailySteps.dayOfTotal(yesterdaysLast, zone))
        assertEquals(day, DailySteps.dayOfTotal(edge, zone))
        assertEquals(day, DailySteps.dayOfTotal(tonightsLast, zone))
        // Yesterday's count must not become today's steps.
        assertEquals(700, DailySteps.of(emptyList(), DailySteps.totalsForDay(all.dropLast(1), start, end)))
    }

    @Test fun theLargestTotalWinsSoALateResetNeverShrinksTheDay() {
        val totals = listOf(s(Metric.STEPS_DAY_TOTAL, 0, 8000.0), s(Metric.STEPS_DAY_TOTAL, 30, 12.0))
        assertEquals(8000, DailySteps.of(emptyList(), totals))
    }

    @Test fun intervalSumsForDistanceAndCalories() {
        assertEquals(0, DailySteps.sum(emptyList()))
        assertEquals(43, DailySteps.sum(listOf(s(Metric.DISTANCE_M, 0, 40.0), s(Metric.DISTANCE_M, 30, 3.0))))
    }
}
