package dev.avijit.orbithealth.insights

import dev.avijit.orbithealth.ring.Metric
import dev.avijit.orbithealth.ring.Sample
import dev.avijit.orbithealth.ring.SampleSource
import dev.avijit.orbithealth.ring.SleepSegment
import dev.avijit.orbithealth.ring.SleepSession
import dev.avijit.orbithealth.ring.SleepStage
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class RestingHrTest {
    private val dayStart = Instant.parse("2026-11-05T00:00:00Z")
    private val dayEnd = Instant.parse("2026-11-06T00:00:00Z")
    private val sleepStart = Instant.parse("2026-11-04T23:00:00Z")
    private val sleepEnd = Instant.parse("2026-11-05T07:00:00Z")

    private fun hr(at: Instant, v: Double, source: SampleSource = SampleSource.HISTORY) = Sample(Metric.HEART_RATE, at, v, source)
    private fun session(start: Instant, end: Instant) =
        SleepSession(start, end, listOf(SleepSegment(SleepStage.LIGHT, start, (end.epochSecond - start.epochSecond).toInt())))

    @Test fun lowestThirtyMinuteRollingMeanDuringMainSleep() {
        // Every 10 min through the night at 60, with a 30-min trough 50/52/54 at 03:00 and one LIVE outlier of 40.
        val night = (0 until 48).map { i ->
            val t = sleepStart.plusSeconds(i * 600L)
            val v = when (i) { 24 -> 50.0; 25 -> 52.0; 26 -> 54.0; else -> 60.0 }
            hr(t, v)
        } + hr(sleepStart.plusSeconds(3600), 40.0, SampleSource.LIVE)
        val day = (0 until 10).map { hr(dayStart.plusSeconds(10 * 3600L + it * 600L), 90.0) }
        assertEquals(52.0, RestingHr.of(night + day, session(sleepStart, sleepEnd), dayStart, dayEnd)!!, 1e-9)
    }

    @Test fun fallsBackToTenthPercentileOfTheDayWithoutSleep() {
        // 1..100 bpm spread over the day: nearest-rank 10th percentile is 10.
        val day = (1..100).map { hr(dayStart.plusSeconds(it * 600L), it.toDouble(), SampleSource.LIVE) }
        val yesterday = hr(dayStart.minusSeconds(60), 1.0)
        assertEquals(10.0, RestingHr.of(day + yesterday, null, dayStart, dayEnd)!!, 1e-9)
    }

    @Test fun fallsBackWhenSleepHasNoHistoryHeartRate() {
        val day = (1..10).map { hr(dayStart.plusSeconds(12 * 3600L + it * 60L), 60.0 + it, SampleSource.LIVE) }
        assertEquals(61.0, RestingHr.of(day, session(sleepStart, sleepEnd), dayStart, dayEnd)!!, 1e-9)
    }

    @Test fun nullWithoutAnyHeartRate() {
        assertNull(RestingHr.of(emptyList(), session(sleepStart, sleepEnd), dayStart, dayEnd))
    }

    @Test fun mainSleepIsTheLongestSessionEndingThatDay() {
        val nap = session(Instant.parse("2026-11-05T13:00:00Z"), Instant.parse("2026-11-05T13:40:00Z"))
        val night = session(sleepStart, sleepEnd)
        val previous = session(Instant.parse("2026-11-03T23:00:00Z"), Instant.parse("2026-11-04T08:00:00Z"))
        assertSame(night, RestingHr.mainSleep(listOf(previous, night, nap), dayStart, dayEnd))
        assertNull(RestingHr.mainSleep(listOf(previous), dayStart, dayEnd))
    }
}
