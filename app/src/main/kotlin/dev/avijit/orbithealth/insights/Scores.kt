package dev.avijit.orbithealth.insights

import dev.avijit.orbithealth.ring.SleepSession
import dev.avijit.orbithealth.ring.SleepStage
import dev.avijit.orbithealth.ring.totalSleepSec
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A 0-100 score plus short human-readable [reasons] explaining what drove it. */
data class Score(val value: Int, val reasons: List<String>)

object Scores {
    /**
     * Sleep = 0.5 x min(1, asleep/goal) + 0.3 x min(1, deepRemShare/0.4) + 0.2 x max(0, 1 - awakeShare x 4), scaled to 0-100.
     * deepRemShare = (deep+REM)/asleep; awakeShare = awake/(asleep+awake).
     */
    fun sleep(s: SleepSession?, goalMin: Int): Score? {
        if (s == null || goalMin <= 0) return null
        val asleep = s.totalSleepSec
        if (asleep <= 0) return null
        val awake = s.segments.filter { it.stage == SleepStage.AWAKE }.sumOf { it.durationSec }
        val deepRem = s.segments.filter { it.stage == SleepStage.DEEP || it.stage == SleepStage.REM }.sumOf { it.durationSec }
        val durationPart = min(1.0, asleep / (goalMin * 60.0))
        val deepRemShare = deepRem.toDouble() / asleep
        val stagePart = min(1.0, deepRemShare / 0.4)
        val awakeShare = awake.toDouble() / (asleep + awake)
        val awakePart = max(0.0, 1.0 - awakeShare * 4)
        val value = (100 * (0.5 * durationPart + 0.3 * stagePart + 0.2 * awakePart)).roundToInt().coerceIn(0, 100)
        val reasons = listOf(
            "Slept ${hm(asleep / 60)} of ${hm(goalMin)} goal",
            "Deep + REM ${(deepRemShare * 100).roundToInt()}% of sleep (target 40%)",
            "Awake ${(awakeShare * 100).roundToInt()}% of the night",
        )
        return Score(value, reasons)
    }

    /**
     * Readiness = 70 + 10 x z(HRV) - 10 x z(resting HR) + 0.2 x (sleepScore - 70), clamped 0-100.
     * Null when either baseline (or its current value) is missing; the sleep term is dropped when [sleep] is null.
     */
    fun readiness(restingHr: Double?, hrBase: Baseline?, hrv: Double?, hrvBase: Baseline?, sleep: Score?): Score? {
        if (restingHr == null || hrBase == null || hrv == null || hrvBase == null) return null
        var raw = 70.0 + 10 * hrvBase.z(hrv) - 10 * hrBase.z(restingHr)
        val reasons = mutableListOf<String>()
        reasons += diff("HRV", hrv - hrvBase.mean, "ms", "above", "below")
        reasons += diff("Resting HR", restingHr - hrBase.mean, "bpm", "above", "below")
        if (sleep != null) {
            raw += 0.2 * (sleep.value - 70)
            reasons += "Sleep score ${sleep.value}"
        }
        return Score(raw.roundToInt().coerceIn(0, 100), reasons)
    }

    /** Activity = min(100, 100 x steps/goal). */
    fun activity(steps: Int, goal: Int): Score {
        val value = if (goal <= 0) 0 else (100.0 * steps / goal).roundToInt().coerceIn(0, 100)
        return Score(value, listOf("$steps of $goal steps"))
    }

    private fun diff(name: String, d: Double, unit: String, up: String, down: String): String {
        val n = abs(d).roundToInt()
        return if (n == 0) "$name at your normal" else "$name $n $unit ${if (d > 0) up else down} ${if (name == "HRV") "your normal" else "normal"}"
    }

    private fun hm(min: Int) = "${min / 60}h ${min % 60}m"
}
