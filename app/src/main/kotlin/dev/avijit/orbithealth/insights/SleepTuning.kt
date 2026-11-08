package dev.avijit.orbithealth.insights

import java.time.Duration
import java.time.Instant
import kotlin.math.roundToInt

/** What the user said about one suggestion: [CONFIRMED] (maybe after adjusting its times) or [DISMISSED]. */
enum class SleepVerdict { CONFIRMED, DISMISSED }

/**
 * One decided suggestion, as [SleepTuning] learns from it: what was suggested ([suggestedStart]..[suggestedEnd], with the
 * bias of the day already in it) and what the user kept ([finalStart]..[finalEnd]; equal when confirmed as is).
 */
data class SleepDecision(
    val kind: SleepKind,
    val verdict: SleepVerdict,
    val suggestedStart: Instant,
    val suggestedEnd: Instant,
    val finalStart: Instant = suggestedStart,
    val finalEnd: Instant = suggestedEnd,
)

/**
 * Personal tuning of [SleepEstimator], learned on the phone from every Yes / Adjust / No. Simple and deterministic: each
 * decision moves each knob by a bounded step, and the whole state is a fold over the decisions in the order they were
 * made ([replay]), so it needs no storage of its own: the decided estimates in the encrypted database are the memory.
 *
 * - **Adjust** (a night confirmed with other times): each bias moves [LEARN_RATE] (35%) of the way towards the shift the
 *   user made, at most [MAX_STEP_MIN] (15 min) per decision, and stays within ±[MAX_BIAS_MIN] (90 min). Repeating the
 *   same correction converges on it geometrically.
 * - **Dismiss**: stricter. The heart-rate ratio drops [RATIO_STEP] (0.01) and the confidence bar rises
 *   [CONFIDENCE_STEP] (0.03).
 * - **Confirm** (any night or nap kept): half a step back towards the defaults on both, so a few early dismissals do not
 *   silence the detector for good.
 * - Bounds: ratio [MIN_RATIO]..[MAX_RATIO] (0.80..0.96), confidence bar [MIN_CONFIDENCE]..[MAX_CONFIDENCE] (0.40..0.85).
 *   Naps never move the biases (their times are confirmed or dismissed, not adjusted).
 */
data class SleepTuning(
    val hrRatio: Double = SleepEstimator.DEFAULT_HR_RATIO,
    val startBiasMin: Double = 0.0,
    val endBiasMin: Double = 0.0,
    val minConfidence: Double = SleepEstimator.DEFAULT_MIN_CONFIDENCE,
) {
    /** The detector settings these knobs stand for. */
    fun params(): EstimatorParams = EstimatorParams(hrRatio, startBiasMin.roundToInt(), endBiasMin.roundToInt(), minConfidence)

    /** After one more [decision]. */
    fun after(decision: SleepDecision): SleepTuning = when (decision.verdict) {
        SleepVerdict.DISMISSED -> copy(
            hrRatio = (hrRatio - RATIO_STEP).coerceIn(MIN_RATIO, MAX_RATIO),
            minConfidence = (minConfidence + CONFIDENCE_STEP).coerceIn(MIN_CONFIDENCE, MAX_CONFIDENCE),
        )
        SleepVerdict.CONFIRMED -> {
            val relaxed = copy(
                hrRatio = towards(hrRatio, SleepEstimator.DEFAULT_HR_RATIO, RATIO_STEP / 2).coerceIn(MIN_RATIO, MAX_RATIO),
                minConfidence = towards(minConfidence, SleepEstimator.DEFAULT_MIN_CONFIDENCE, CONFIDENCE_STEP / 2)
                    .coerceIn(MIN_CONFIDENCE, MAX_CONFIDENCE),
            )
            if (decision.kind != SleepKind.NIGHT) relaxed
            else relaxed.copy(
                startBiasMin = learn(startBiasMin, minutes(decision.suggestedStart, decision.finalStart)),
                endBiasMin = learn(endBiasMin, minutes(decision.suggestedEnd, decision.finalEnd)),
            )
        }
    }

    companion object {
        const val LEARN_RATE = 0.35
        const val MAX_STEP_MIN = 15.0
        const val MAX_BIAS_MIN = 90.0
        const val RATIO_STEP = 0.01
        const val CONFIDENCE_STEP = 0.03
        const val MIN_RATIO = 0.80
        const val MAX_RATIO = 0.96
        const val MIN_CONFIDENCE = 0.40
        const val MAX_CONFIDENCE = 0.85

        /** The tuning after every decision in [decisions], oldest first, starting from the defaults. */
        fun replay(decisions: List<SleepDecision>): SleepTuning = decisions.fold(SleepTuning()) { t, d -> t.after(d) }

        private fun minutes(from: Instant, to: Instant): Double = Duration.between(from, to).seconds / 60.0

        /** The bias moved towards bias + [shift] (the shift is relative to a suggestion that already had the bias). */
        private fun learn(bias: Double, shift: Double): Double =
            (bias + (LEARN_RATE * shift).coerceIn(-MAX_STEP_MIN, MAX_STEP_MIN)).coerceIn(-MAX_BIAS_MIN, MAX_BIAS_MIN)

        private fun towards(v: Double, target: Double, step: Double): Double = when {
            v < target -> minOf(target, v + step)
            v > target -> maxOf(target, v - step)
            else -> v
        }
    }
}
