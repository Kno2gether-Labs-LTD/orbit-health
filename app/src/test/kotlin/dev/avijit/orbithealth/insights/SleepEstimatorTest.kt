package dev.avijit.orbithealth.insights

import dev.avijit.orbithealth.ring.Metric
import dev.avijit.orbithealth.ring.Sample
import dev.avijit.orbithealth.ring.SampleSource
import dev.avijit.orbithealth.ring.SleepSegment
import dev.avijit.orbithealth.ring.SleepSession
import dev.avijit.orbithealth.ring.SleepStage
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Synthetic days: the ring measures heart rate every 10 minutes (72 bpm through the day, 56 asleep) and writes 30-minute
 * step buckets. The scenario under test is the night the ring recorded nothing: asleep 03:10 to 10:40.
 */
class SleepEstimatorTest {
    private val zone = ZoneOffset.UTC
    private fun at(day: Int, h: Int, m: Int = 0): Instant = LocalDateTime.of(2026, 11, day, h, m).toInstant(zone)
    private fun hr(t: Instant, v: Double) = Sample(Metric.HEART_RATE, t, v, SampleSource.HISTORY)
    private fun steps(t: Instant, v: Double) = Sample(Metric.STEPS, t, v, SampleSource.HISTORY)
    private fun spo2(t: Instant, v: Double) = Sample(Metric.SPO2, t, v, SampleSource.HISTORY)

    private fun every(from: Instant, to: Instant, step: Duration, f: (Instant) -> Sample?): List<Sample> =
        generateSequence(from) { it.plus(step) }.takeWhile { it < to }.mapNotNull(f).toList()

    /** Days 3..5, 09:00–21:00 awake at 72 bpm with steps: the awake baseline. */
    private val baselineDays: List<Sample> = (3..5).flatMap { d ->
        every(at(d, 9), at(d, 21), Duration.ofMinutes(10)) { hr(it, 72.0) } +
            every(at(d, 9), at(d, 21), Duration.ofMinutes(30)) { steps(it, 400.0) }
    }

    /** Evening of the 6th awake, asleep on the 7th 03:10–10:40, then awake until 13:00. */
    private fun night(
        sleepFrom: Instant = at(7, 3, 10),
        sleepTo: Instant = at(7, 10, 40),
        sleepHr: (Instant) -> Double? = { 56.0 },
        sleepSteps: (Instant) -> Double? = { 0.0 },
        wakeUntil: Instant = at(7, 13),
    ): List<Sample> {
        val hrs = every(at(6, 9), wakeUntil, Duration.ofMinutes(10)) { t ->
            if (t >= sleepFrom && t < sleepTo) sleepHr(t)?.let { hr(t, it) } else hr(t, 74.0)
        }
        val st = every(at(6, 9), wakeUntil, Duration.ofMinutes(30)) { t ->
            if (t >= sleepFrom.minus(Duration.ofMinutes(29)) && t < sleepTo) sleepSteps(t)?.let { steps(t, it) } else steps(t, 350.0)
        }
        return hrs + st
    }

    private fun detect(
        samples: List<Sample>,
        sessions: List<SleepSession> = emptyList(),
        params: EstimatorParams = EstimatorParams(),
        spo2: List<Sample> = emptyList(),
        to: Instant = at(7, 13),
    ): List<EstimatedSleep> {
        val all = baselineDays + samples
        return SleepEstimator.detect(
            hr = all.filter { it.metric == Metric.HEART_RATE },
            steps = all.filter { it.metric == Metric.STEPS },
            spo2 = spo2,
            ringSessions = sessions,
            from = at(6, 12),
            to = to,
            zone = zone,
            params = params,
        )
    }

    private fun minutesOff(a: Instant, b: Instant) = Math.abs(Duration.between(a, b).toMinutes())

    @Test fun findsTheNightTheRingMissed() {
        val found = detect(night(), spo2 = listOf(spo2(at(7, 5), 94.0), spo2(at(7, 6), 92.0), spo2(at(7, 12), 90.0)))
        assertEquals(1, found.size)
        val n = found.single()
        assertEquals(SleepKind.NIGHT, n.kind)
        assertTrue("start ${n.start}", minutesOff(n.start, at(7, 3, 10)) <= 20)
        assertTrue("end ${n.end}", minutesOff(n.end, at(7, 10, 40)) <= 20)
        assertEquals(56, n.lowestHr)
        assertEquals(92, n.lowestSpo2) // the 12:00 reading is after waking
        assertTrue("confidence ${n.confidence}", n.confidence > 0.85)
    }

    @Test fun noBaselineNoEstimate() {
        val samples = night()
        val found = SleepEstimator.detect(
            hr = samples.filter { it.metric == Metric.HEART_RATE && it.time >= at(7, 0) },
            steps = samples.filter { it.metric == Metric.STEPS }, spo2 = emptyList(), ringSessions = emptyList(),
            from = at(6, 12), to = at(7, 13), zone = zone,
        )
        assertTrue(found.isEmpty())
    }

    @Test fun ringOffMeansNoEstimate() {
        // Still and quiet (no steps) but no heart rate: the ring is on the bedside table, not on a sleeping finger.
        assertTrue(detect(night(sleepHr = { null })).isEmpty())
    }

    @Test fun hourlyHeartRateStillCountsAsWorn() {
        // A ring set to measure once an hour: each reading covers the slots within 15 min of it, two thirds of the night.
        val hourly = night(sleepHr = { t -> if (t.atZone(zone).minute == 0) 56.0 else null })
        assertEquals(SleepKind.NIGHT, detect(hourly).single().kind)
    }

    @Test fun patchyHeartRateBelowCoverageIsDropped() {
        // One reading every two hours: too thin to say the ring was worn and you were asleep.
        val sparse = night(sleepHr = { t -> if (t.atZone(zone).minute == 0 && t.atZone(zone).hour % 2 == 0) 56.0 else null })
        val r = detect(sparse)
        assertTrue(r.toString(), r.isEmpty())
    }

    @Test fun aSingleHighReadingIsSmoothedOver() {
        val spike = night(sleepHr = { t -> if (t == at(7, 6)) 95.0 else 56.0 })
        val n = detect(spike).single()
        assertTrue(minutesOff(n.start, at(7, 3, 10)) <= 20 && minutesOff(n.end, at(7, 10, 40)) <= 20)
    }

    @Test fun aBathroomTripDoesNotSplitTheNight() {
        // 06:00–06:30 up: heart rate raised and a few steps, then back to sleep.
        val trip = night(
            sleepHr = { t -> if (t >= at(7, 6) && t < at(7, 6, 30)) 80.0 else 56.0 },
            sleepSteps = { t -> if (t == at(7, 6)) 40.0 else 0.0 },
        )
        val found = detect(trip)
        assertEquals(1, found.size)
        assertTrue(minutesOff(found.single().end, at(7, 10, 40)) <= 20)
    }

    @Test fun aLongBreakSplitsIntoTwo() {
        // Up for two hours 05:00–07:00: two runs, the first too short to be a night and at night, so dropped.
        val split = night(
            sleepHr = { t -> if (t >= at(7, 5) && t < at(7, 7)) 80.0 else 56.0 },
            sleepSteps = { t -> if (t >= at(7, 5) && t < at(7, 7)) 500.0 else 0.0 },
        )
        val found = detect(split)
        assertEquals(1, found.size)
        assertTrue(minutesOff(found.single().start, at(7, 7)) <= 20)
    }

    @Test fun walkingAboutKeepsYouAwakeEvenWithLowHeartRate() {
        assertTrue(detect(night(sleepSteps = { 300.0 })).isEmpty())
    }

    @Test fun missingStepBucketsStillAllowAnEstimateWithLessConfidence() {
        val withSteps = detect(night()).single()
        val noSteps = detect(night(sleepSteps = { null })).single()
        assertTrue(noSteps.confidence < withSteps.confidence)
    }

    @Test fun stillAsleepIsNotSuggestedYet() {
        // Data ends at 09:00 while the heart rate is still low: the run is open.
        val ongoing = night(wakeUntil = at(7, 9))
        assertTrue(detect(ongoing, to = at(7, 9)).isEmpty())
    }

    @Test fun ringSessionTimeIsExcludedAndARingNightSuppressesTheEstimate() {
        val ring = SleepSession(at(7, 3), at(7, 10, 30), listOf(SleepSegment(SleepStage.LIGHT, at(7, 3), 27_000)))
        assertTrue(detect(night(), listOf(ring)).isEmpty())
    }

    @Test fun aShortRingSessionDoesNotHideTheRestOfTheNight() {
        // The ring caught only 03:10–04:00 (under 3 h): the estimate covers what followed, not the ring's part.
        val ring = SleepSession(at(7, 3, 10), at(7, 4), listOf(SleepSegment(SleepStage.LIGHT, at(7, 3, 10), 3000)))
        val n = detect(night(), listOf(ring)).single()
        assertTrue(n.start >= at(7, 4))
        assertEquals(SleepKind.NIGHT, n.kind)
    }

    @Test fun anAfternoonNapIsANap() {
        val nap = every(at(7, 12), at(7, 18), Duration.ofMinutes(10)) { t ->
            hr(t, if (t >= at(7, 14, 10) && t < at(7, 14, 50)) 58.0 else 75.0)
        } + every(at(7, 12), at(7, 18), Duration.ofMinutes(30)) { t ->
            steps(t, if (t >= at(7, 14) && t < at(7, 14, 50)) 0.0 else 300.0)
        }
        val found = SleepEstimator.detect(
            hr = (baselineDays + nap).filter { it.metric == Metric.HEART_RATE },
            steps = (baselineDays + nap).filter { it.metric == Metric.STEPS }, spo2 = emptyList(), ringSessions = emptyList(),
            from = at(7, 12), to = at(7, 18), zone = zone,
        )
        val n = found.single()
        assertEquals(SleepKind.NAP, n.kind)
        assertTrue(minutesOff(n.start, at(7, 14, 10)) <= 15)
        assertTrue(minutesOff(n.end, at(7, 14, 50)) <= 15)
    }

    @Test fun aTenMinuteDipIsNotANap() {
        val dip = every(at(7, 12), at(7, 18), Duration.ofMinutes(10)) { t -> hr(t, if (t == at(7, 14, 10)) 58.0 else 75.0) }
        val found = SleepEstimator.detect(
            hr = baselineDays + dip, steps = emptyList(), spo2 = emptyList(), ringSessions = emptyList(),
            from = at(7, 12), to = at(7, 18), zone = zone,
        )
        assertTrue(found.isEmpty())
    }

    @Test fun learnedBiasShiftsANight() {
        val plain = detect(night()).single()
        val biased = detect(night(), params = EstimatorParams(startBiasMin = 20, endBiasMin = -10)).single()
        assertEquals(20, Duration.between(plain.start, biased.start).toMinutes())
        assertEquals(-10, Duration.between(plain.end, biased.end).toMinutes())
    }

    @Test fun aStricterRatioRejectsShallowDips() {
        // Asleep at 63 bpm against a 72 bpm baseline: under the default 0.90 (64.8)...
        val shallow = night(sleepHr = { 63.0 })
        assertEquals(1, detect(shallow).size)
        // ...but over a learned, stricter 0.85 (61.2).
        assertTrue(detect(shallow, params = EstimatorParams(hrRatio = 0.85)).isEmpty())
    }

    @Test fun aHighConfidenceBarDropsWeakEstimates() {
        val weak = night(sleepHr = { 63.0 }, sleepSteps = { null })
        assertEquals(1, detect(weak).size)
        assertTrue(detect(weak, params = EstimatorParams(minConfidence = 0.85)).isEmpty())
    }

    @Test fun detectionIsDeterministic() {
        assertEquals(detect(night()), detect(night()))
    }

    @Test fun baselineIgnoresRingSleepAndNight() {
        val b = SleepEstimator.awakeBaseline(baselineDays.filter { it.metric == Metric.HEART_RATE }, emptyList(), zone)
        assertEquals(72.0, b!!, 0.0)
        assertNull(SleepEstimator.awakeBaseline(listOf(hr(at(7, 12), 70.0)), emptyList(), zone))
    }
}
