package dev.avijit.orbithealth.insights

import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepTuningTest {
    private val t0 = Instant.parse("2026-11-07T03:00:00Z")
    private val t1 = Instant.parse("2026-11-07T10:00:00Z")

    private fun adjust(t: SleepTuning, startShift: Long, endShift: Long, rawStart: Instant = t0, rawEnd: Instant = t1): SleepDecision {
        // What the detector suggested already carries the current bias; the user moves it to raw + shift.
        val p = t.params()
        val sugStart = rawStart.plus(Duration.ofMinutes(p.startBiasMin.toLong()))
        val sugEnd = rawEnd.plus(Duration.ofMinutes(p.endBiasMin.toLong()))
        return SleepDecision(
            SleepKind.NIGHT, SleepVerdict.CONFIRMED, sugStart, sugEnd,
            rawStart.plus(Duration.ofMinutes(startShift)), rawEnd.plus(Duration.ofMinutes(endShift)),
        )
    }

    @Test fun defaultsMatchTheDetector() {
        val p = SleepTuning().params()
        assertEquals(EstimatorParams(), p)
    }

    @Test fun repeatedAdjustmentsConvergeOnTheUsersHabit() {
        // The user always says they fell asleep 30 min later and woke 20 min earlier than the raw detection.
        var t = SleepTuning()
        repeat(15) { t = t.after(adjust(t, 30, -20)) }
        assertEquals(30.0, t.startBiasMin, 1.0)
        assertEquals(-20.0, t.endBiasMin, 1.0)
        // Converged: one more identical correction barely moves it.
        val next = t.after(adjust(t, 30, -20))
        assertTrue(Math.abs(next.startBiasMin - t.startBiasMin) < 0.5)
    }

    @Test fun oneAdjustmentMovesABoundedStep() {
        val t = SleepTuning().after(adjust(SleepTuning(), 240, -240))
        assertEquals(SleepTuning.MAX_STEP_MIN, t.startBiasMin, 0.0)
        assertEquals(-SleepTuning.MAX_STEP_MIN, t.endBiasMin, 0.0)
    }

    @Test fun biasesStayWithinBounds() {
        var t = SleepTuning()
        repeat(100) { t = t.after(adjust(t, 600, -600)) }
        assertEquals(SleepTuning.MAX_BIAS_MIN, t.startBiasMin, 0.0)
        assertEquals(-SleepTuning.MAX_BIAS_MIN, t.endBiasMin, 0.0)
    }

    @Test fun dismissalsMakeItStricterWithinBounds() {
        val d = SleepDecision(SleepKind.NIGHT, SleepVerdict.DISMISSED, t0, t1)
        val once = SleepTuning().after(d)
        assertTrue(once.hrRatio < SleepEstimator.DEFAULT_HR_RATIO)
        assertTrue(once.minConfidence > SleepEstimator.DEFAULT_MIN_CONFIDENCE)
        val many = SleepTuning.replay(List(200) { d })
        assertEquals(SleepTuning.MIN_RATIO, many.hrRatio, 1e-9)
        assertEquals(SleepTuning.MAX_CONFIDENCE, many.minConfidence, 1e-9)
    }

    @Test fun confirmationsRecoverTowardsDefaultsButNoFurther() {
        val dismiss = SleepDecision(SleepKind.NIGHT, SleepVerdict.DISMISSED, t0, t1)
        val confirm = SleepDecision(SleepKind.NAP, SleepVerdict.CONFIRMED, t0, t1)
        val t = SleepTuning.replay(List(3) { dismiss } + List(50) { confirm })
        assertEquals(SleepEstimator.DEFAULT_HR_RATIO, t.hrRatio, 1e-9)
        assertEquals(SleepEstimator.DEFAULT_MIN_CONFIDENCE, t.minConfidence, 1e-9)
    }

    @Test fun napsDoNotMoveTheBiases() {
        val nap = SleepDecision(SleepKind.NAP, SleepVerdict.CONFIRMED, t0, t1, t0.plusSeconds(3600), t1)
        assertEquals(0.0, SleepTuning().after(nap).startBiasMin, 0.0)
    }

    @Test fun confirmingAsSuggestedKeepsTheBias() {
        val t = SleepTuning(startBiasMin = 20.0)
        val asIs = SleepDecision(SleepKind.NIGHT, SleepVerdict.CONFIRMED, t0, t1)
        assertEquals(20.0, t.after(asIs).startBiasMin, 0.0)
    }

    @Test fun replayIsAFoldInOrder() {
        val a = SleepDecision(SleepKind.NIGHT, SleepVerdict.DISMISSED, t0, t1)
        val b = adjust(SleepTuning(), 30, 0)
        assertEquals(SleepTuning().after(a).after(b), SleepTuning.replay(listOf(a, b)))
    }
}
