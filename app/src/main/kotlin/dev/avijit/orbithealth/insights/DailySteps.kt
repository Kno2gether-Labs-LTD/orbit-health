package dev.avijit.orbithealth.insights

import dev.avijit.orbithealth.ring.Sample
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

/**
 * A day's step count from two ring sources (shared by Today and Activity):
 * STEPS are 30-minute buckets, STEPS_DAY_TOTAL is the ring's running total for the day. The vendor app may clear
 * buckets before we sync them, while the total keeps counting; a total may also lag the newest buckets. So the day
 * is the larger of the buckets' sum and the day's largest total. Callers pass only that day's samples (for totals,
 * pick them with [totalsForDay] / [dayOfTotal]).
 */
object DailySteps {
    /**
     * A running total stamped within this long after local midnight is the previous day's final count: the ring
     * stamps its all-day record when it is read, so a sync just after midnight reports yesterday's total.
     */
    val CarryOver: Duration = Duration.ofMinutes(15)

    fun of(steps: List<Sample>, dayTotals: List<Sample>): Int {
        // Totals only grow within a day, so the largest is the latest; max also survives a reset that slipped in.
        val total = dayTotals.maxOfOrNull { it.value }?.roundToInt() ?: 0
        return maxOf(sum(steps), total)
    }

    /** The running totals that belong to the day [dayStart, dayEnd), with the [CarryOver] after each midnight. */
    fun totalsForDay(totals: List<Sample>, dayStart: Instant, dayEnd: Instant): List<Sample> {
        val from = dayStart.plus(CarryOver)
        val to = dayEnd.plus(CarryOver)
        return totals.filter { it.time >= from && it.time < to }
    }

    /** The local day a running total counts towards (see [CarryOver]). */
    fun dayOfTotal(total: Sample, zone: ZoneId): LocalDate = total.time.minus(CarryOver).atZone(zone).toLocalDate()

    /** Sum of interval samples (steps, distance in m, kcal). */
    fun sum(intervals: List<Sample>): Int = intervals.sumOf { it.value }.roundToInt()
}
