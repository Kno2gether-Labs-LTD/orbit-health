package dev.avijit.orbithealth.insights

import dev.avijit.orbithealth.ring.Metric
import dev.avijit.orbithealth.ring.Sample
import dev.avijit.orbithealth.ring.SampleSource
import dev.avijit.orbithealth.ring.SleepSession
import dev.avijit.orbithealth.ring.totalSleepSec
import java.time.Instant
import kotlin.math.ceil

/**
 * Daily resting heart rate, shared by Today and Body so both screens agree:
 * the lowest 30-minute rolling mean of ring-history heart rate during the day's main sleep; without usable sleep data,
 * the 10th percentile (nearest rank) of the day's heart rate.
 */
object RestingHr {
    const val WINDOW_SEC = 30 * 60L

    /**
     * [hr] may span more than the day (sleep starts the evening before); only HEART_RATE samples are used.
     * Sleep windows start at each HISTORY reading, must end inside [mainSleep] and hold at least two readings.
     * The fallback uses readings of any source in [dayStart, dayEnd). Null when there is no heart rate at all.
     */
    fun of(hr: List<Sample>, mainSleep: SleepSession?, dayStart: Instant, dayEnd: Instant): Double? {
        val heart = hr.filter { it.metric == Metric.HEART_RATE }
        if (mainSleep != null) duringSleep(heart, mainSleep)?.let { return it }
        val day = heart.filter { it.time >= dayStart && it.time < dayEnd }.map { it.value }.sorted()
        if (day.isEmpty()) return null
        val rank = ceil(0.10 * day.size).toInt().coerceIn(1, day.size)
        return day[rank - 1]
    }

    private fun duringSleep(heart: List<Sample>, sleep: SleepSession): Double? {
        val night = heart
            .filter { it.source == SampleSource.HISTORY && it.time >= sleep.start && it.time <= sleep.end }
            .sortedBy { it.time }
        var best: Double? = null
        var end = 0
        var sum = 0.0
        for (i in night.indices) {
            val windowEnd = night[i].time.plusSeconds(WINDOW_SEC)
            if (windowEnd > sleep.end) break
            if (end < i) { end = i; sum = 0.0 }
            while (end < night.size && night[end].time < windowEnd) sum += night[end++].value
            val n = end - i
            if (n >= 2) {
                val mean = sum / n
                if (best == null || mean < best) best = mean
            }
            sum -= night[i].value
        }
        return best
    }

    /** The day's main sleep: the longest session (by time asleep, then by length) that ends in [dayStart, dayEnd). */
    fun mainSleep(sessions: List<SleepSession>, dayStart: Instant, dayEnd: Instant): SleepSession? =
        sessions.filter { it.end >= dayStart && it.end < dayEnd }
            .maxWithOrNull(compareBy<SleepSession>({ it.totalSleepSec }, { it.end.epochSecond - it.start.epochSecond }))
}
