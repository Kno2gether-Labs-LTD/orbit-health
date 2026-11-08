package dev.avijit.orbithealth.insights

import dev.avijit.orbithealth.ring.SleepSegment
import dev.avijit.orbithealth.ring.SleepSession
import dev.avijit.orbithealth.ring.SleepStage
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepEstimatesTest {
    private fun t(s: String) = Instant.parse("2026-11-${s}Z")
    private val scanFrom = t("05T00:00:00")
    private val scanTo = t("07T13:00:00")

    private fun detected(a: String, b: String, kind: SleepKind = SleepKind.NIGHT, hr: Int = 55) =
        EstimatedSleep(t(a), t(b), kind, hr, 93, 0.9)

    private fun stored(id: Long, a: String, b: String, status: EstimateStatus, kind: SleepKind = SleepKind.NIGHT) =
        SleepEstimate(id, kind, status, t(a), t(b), t(a), t(b), 55, 93, 0.9, if (status == EstimateStatus.SUGGESTED) null else t("07T12:00:00"))

    private val night = detected("07T03:10:00", "07T10:40:00")

    @Test fun aNewDetectionIsInserted() {
        val p = SleepEstimates.plan(emptyList(), listOf(night), emptyList(), scanFrom, scanTo)
        assertEquals(listOf(night), p.insert)
        assertTrue(p.update.isEmpty() && p.delete.isEmpty())
    }

    @Test fun runningAgainChangesNothing() {
        val s = stored(1, "07T03:10:00", "07T10:40:00", EstimateStatus.SUGGESTED)
        assertTrue(SleepEstimates.plan(listOf(s), listOf(night), emptyList(), scanFrom, scanTo).isEmpty)
    }

    @Test fun aRefinedDetectionUpdatesTheSuggestionInPlace() {
        val s = stored(1, "07T03:10:00", "07T09:00:00", EstimateStatus.SUGGESTED)
        val p = SleepEstimates.plan(listOf(s), listOf(night), emptyList(), scanFrom, scanTo)
        assertEquals(listOf(1L to night), p.update)
        assertTrue(p.insert.isEmpty() && p.delete.isEmpty())
    }

    @Test fun aDismissedWindowNeverComesBack() {
        val s = stored(1, "07T03:00:00", "07T10:00:00", EstimateStatus.DISMISSED)
        assertTrue(SleepEstimates.plan(listOf(s), listOf(night), emptyList(), scanFrom, scanTo).isEmpty)
    }

    @Test fun aConfirmedNightIsNeverOverridden() {
        // The user moved it to 04:00–09:00; the detector still sees 03:10–10:40, which must not replace it.
        val s = SleepEstimate(1, SleepKind.NIGHT, EstimateStatus.CONFIRMED, t("07T04:00:00"), t("07T09:00:00"),
            t("07T03:10:00"), t("07T10:40:00"), 55, 93, 0.9, t("07T12:00:00"))
        assertTrue(SleepEstimates.plan(listOf(s), listOf(night), emptyList(), scanFrom, scanTo).isEmpty)
    }

    @Test fun aSuggestionNoLongerSeenIsDeletedButOnlyInsideTheScan() {
        val gone = stored(1, "06T03:00:00", "06T10:00:00", EstimateStatus.SUGGESTED)
        val old = stored(2, "01T03:00:00", "01T10:00:00", EstimateStatus.SUGGESTED)
        val p = SleepEstimates.plan(listOf(gone, old), emptyList(), emptyList(), scanFrom, scanTo)
        assertEquals(listOf(1L), p.delete)
    }

    @Test fun aRingSessionRemovesAnOverlappingSuggestion() {
        val s = stored(1, "01T03:00:00", "01T10:00:00", EstimateStatus.SUGGESTED)
        val ring = SleepSession(t("01T02:00:00"), t("01T09:00:00"), emptyList())
        assertEquals(listOf(1L), SleepEstimates.plan(listOf(s), emptyList(), listOf(ring), scanFrom, scanTo).delete)
    }

    @Test fun confirmedNightGivesWayToTheRing() {
        val e = stored(1, "07T03:10:00", "07T10:40:00", EstimateStatus.CONFIRMED)
        val dayStart = t("07T00:00:00")
        val dayEnd = t("08T00:00:00")
        assertEquals(e, SleepEstimates.confirmedNight(listOf(e), emptyList(), dayStart, dayEnd))
        val ring = SleepSession(t("06T23:00:00"), t("07T07:00:00"), listOf(SleepSegment(SleepStage.LIGHT, t("06T23:00:00"), 28_800)))
        assertNull(SleepEstimates.confirmedNight(listOf(e), listOf(ring), dayStart, dayEnd))
    }

    @Test fun suggestionsAndDismissedAreNotNights() {
        val s = stored(1, "07T03:10:00", "07T10:40:00", EstimateStatus.SUGGESTED)
        val d = stored(2, "07T03:10:00", "07T10:40:00", EstimateStatus.DISMISSED)
        assertNull(SleepEstimates.confirmedNight(listOf(s, d), emptyList(), t("07T00:00:00"), t("08T00:00:00")))
        assertTrue(SleepEstimates.confirmedNights(listOf(s, d), emptyList(), ZoneOffset.UTC).isEmpty())
    }

    @Test fun confirmedNightsSkipRingDays() {
        val a = stored(1, "05T03:00:00", "05T10:00:00", EstimateStatus.CONFIRMED)
        val b = stored(2, "07T03:10:00", "07T10:40:00", EstimateStatus.CONFIRMED)
        val ring = SleepSession(t("04T23:00:00"), t("05T06:00:00"), listOf(SleepSegment(SleepStage.LIGHT, t("04T23:00:00"), 25_200)))
        assertEquals(listOf(b), SleepEstimates.confirmedNights(listOf(a, b), listOf(ring), ZoneOffset.UTC))
    }

    @Test fun pendingPicksTheNewestSuggestionOfAKind() {
        val n1 = stored(1, "05T03:00:00", "05T10:00:00", EstimateStatus.SUGGESTED)
        val n2 = stored(2, "07T03:00:00", "07T10:00:00", EstimateStatus.SUGGESTED)
        val nap = stored(3, "07T14:10:00", "07T14:50:00", EstimateStatus.SUGGESTED, SleepKind.NAP)
        assertEquals(n2, SleepEstimates.pending(listOf(n1, n2, nap), SleepKind.NIGHT, t("06T00:00:00")))
        assertEquals(nap, SleepEstimates.pending(listOf(n1, n2, nap), SleepKind.NAP, t("06T00:00:00")))
        assertNull(SleepEstimates.pending(listOf(n1), SleepKind.NIGHT, t("06T00:00:00")))
    }

    @Test fun estimatedScoreIsDurationOnlyAndCapped() {
        val full = stored(1, "07T00:00:00", "07T08:00:00", EstimateStatus.CONFIRMED)
        assertEquals(SleepEstimates.ESTIMATE_SCORE_CAP, SleepEstimates.score(full, 450)!!.value)
        val half = stored(2, "07T03:00:00", "07T06:45:00", EstimateStatus.CONFIRMED)
        assertEquals(40, SleepEstimates.score(half, 450)!!.value)
    }

    @Test fun decisionsFeedTheTuning() {
        val c = SleepEstimate(1, SleepKind.NIGHT, EstimateStatus.CONFIRMED, t("07T04:00:00"), t("07T10:40:00"),
            t("07T03:10:00"), t("07T10:40:00"), 55, 93, 0.9, t("07T12:00:00"))
        val d = c.decision()!!
        assertEquals(SleepVerdict.CONFIRMED, d.verdict)
        assertEquals(t("07T04:00:00"), d.finalStart)
        assertEquals(t("07T03:10:00"), d.suggestedStart)
        assertNull(stored(2, "07T03:10:00", "07T10:40:00", EstimateStatus.SUGGESTED).decision())
    }
}
