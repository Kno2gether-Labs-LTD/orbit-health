package dev.avijit.orbithealth.ui.sleep

import dev.avijit.orbithealth.insights.EstimateStatus
import dev.avijit.orbithealth.insights.SleepEstimate
import dev.avijit.orbithealth.insights.SleepEstimates
import dev.avijit.orbithealth.insights.SleepKind
import dev.avijit.orbithealth.ring.Metric
import dev.avijit.orbithealth.ring.Sample
import dev.avijit.orbithealth.ring.SampleSource
import dev.avijit.orbithealth.ring.SleepSegment
import dev.avijit.orbithealth.ring.SleepSession
import dev.avijit.orbithealth.ring.SleepStage
import dev.avijit.orbithealth.ui.today.DayInput
import dev.avijit.orbithealth.ui.today.GoalSettings
import dev.avijit.orbithealth.ui.today.TodayMath
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Confirmed estimates on Today and Sleep: they fill a missed night, never replace the ring's, and carry no stages. */
class EstimatedNightsTest {
    private val zone = ZoneOffset.UTC
    private val today = LocalDate.of(2026, 11, 7)
    private fun t(s: String) = Instant.parse("2026-11-${s}Z")
    private val goals = GoalSettings(8000, 450, LocalTime.of(23, 15))

    private fun estimate(id: Long, a: String, b: String, status: EstimateStatus = EstimateStatus.CONFIRMED, kind: SleepKind = SleepKind.NIGHT) =
        SleepEstimate(id, kind, status, t(a), t(b), t(a), t(b), 54, 93, 0.9, if (status == EstimateStatus.SUGGESTED) null else t("07T12:00:00"))

    private fun ringNight(a: String, b: String): SleepSession {
        val start = t(a)
        val sec = (t(b).epochSecond - start.epochSecond).toInt()
        return SleepSession(start, t(b), listOf(SleepSegment(SleepStage.LIGHT, start, sec / 2), SleepSegment(SleepStage.DEEP, start.plusSeconds(sec / 2L), sec / 2)))
    }

    private fun day(sessions: List<SleepSession> = emptyList(), estimates: List<SleepEstimate> = emptyList(), hr: List<Sample> = emptyList()) =
        TodayMath.compute(
            DayInput(
                today, zone, hr, emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), sessions, goals,
                estimates = estimates,
            ),
        )

    @Test fun aConfirmedEstimateFillsTodaysSleep() {
        val r = day(estimates = listOf(estimate(1, "07T03:10:00", "07T10:40:00")))
        assertTrue(r.lastNightEstimated)
        assertEquals(t("07T03:10:00"), r.lastNight!!.start)
        assertTrue("no stages", r.lastNight!!.segments.isEmpty())
        assertEquals(SleepEstimates.ESTIMATE_SCORE_CAP, r.sleep!!.value) // 7h 30m meets the 7h 30m goal
        assertEquals(EstimateCopy.NoStages, r.copy.sleepMeta)
        assertTrue(r.copy.daySentence, r.copy.daySentence.startsWith("You slept about 7h 30m."))
        assertEquals("Last night you were 3h 55m later", r.copy.bedtimeNote)
    }

    @Test fun theRingAlwaysWins() {
        val r = day(listOf(ringNight("06T23:00:00", "07T07:00:00")), listOf(estimate(1, "07T08:00:00", "07T11:30:00")))
        assertFalse(r.lastNightEstimated)
        assertEquals(t("06T23:00:00"), r.lastNight!!.start)
    }

    @Test fun suggestionsAndDismissalsDoNotCountAsSleep() {
        val r = day(estimates = listOf(estimate(1, "07T03:10:00", "07T10:40:00", EstimateStatus.SUGGESTED)))
        assertNull(r.lastNight)
        assertNull(r.sleep)
        assertEquals(1L, r.nightSuggestion!!.id)
        val d = day(estimates = listOf(estimate(1, "07T03:10:00", "07T10:40:00", EstimateStatus.DISMISSED)))
        assertNull(d.lastNight)
        assertNull(d.nightSuggestion)
    }

    @Test fun noNightQuestionOnceTheRingHasThatNight() {
        val r = day(listOf(ringNight("06T23:00:00", "07T07:00:00")), listOf(estimate(1, "07T08:00:00", "07T11:30:00", EstimateStatus.SUGGESTED)))
        assertNull(r.nightSuggestion)
    }

    @Test fun napsAreAskedAboutSeparately() {
        val r = day(estimates = listOf(estimate(3, "07T14:10:00", "07T14:50:00", EstimateStatus.SUGGESTED, SleepKind.NAP)))
        assertEquals(3L, r.napSuggestion!!.id)
        assertNull(r.nightSuggestion)
    }

    @Test fun restingHeartRateComesFromTheEstimatedNight() {
        val hr = (0 until 40).map { i ->
            val time = t("07T03:30:00").plusSeconds(i * 600L)
            Sample(Metric.HEART_RATE, time, if (i in 10..14) 50.0 else 58.0, SampleSource.HISTORY)
        } + Sample(Metric.HEART_RATE, t("07T12:00:00"), 40.0, SampleSource.HISTORY) // awake, outside the night
        val r = day(estimates = listOf(estimate(1, "07T03:10:00", "07T10:40:00")), hr = hr)
        assertEquals(50, r.restingHr)
    }

    @Test fun sleepScreenPagesIncludeConfirmedEstimatesBesideRingNights() {
        val ring = ringNight("05T23:00:00", "06T07:00:00")
        val est = estimate(1, "07T03:10:00", "07T10:40:00")
        val clash = estimate(2, "06T08:00:00", "06T11:00:00") // the ring has the 6th: not shown
        val nights = SleepMath.nights(listOf(ring), emptyList(), emptyList(), 450, today, zone, estimates = listOf(est, clash))
        assertEquals(listOf(false, true), nights.map { it.estimated })
        val n = nights.last()
        assertEquals("Last night", n.title)
        assertEquals("7h 30m", n.duration)
        assertEquals(SleepEstimates.ESTIMATE_SCORE_CAP, n.score!!.value)
        assertTrue(n.stageTotals.values.all { it == 0 })
    }

    @Test fun estimatedNightsCountTowardsTheUsualBedtime() {
        val nights = (1..3).map { estimate(it.toLong(), "0${it}T23:00:00", "0${it + 1}T07:00:00") }
        val late = ringNight("06T01:00:00", "06T08:00:00")
        val all = SleepMath.nights(listOf(late), emptyList(), emptyList(), 450, today, zone, estimates = nights)
        val insight = all.last { !it.estimated }.insight.orEmpty()
        assertTrue(insight, insight.endsWith("Bedtime was 2h 00m later than usual."))
    }
}
