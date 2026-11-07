package dev.avijit.orbithealth.insights

import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

data class Goal(val label: String, val target: Int, val progress: Int, val why: String?)

object SmartGoals {
    /**
     * Step goal = base x (1.15 if readiness >= 75; 0.85 if < 50; else 1.0), rounded to the nearest 100.
     * Also a "2 calm breaks" goal and an "In bed by HH:mm" goal (no target; sleepGoalMin is reserved for bedtime copy).
     */
    @Suppress("UNUSED_PARAMETER")
    fun today(
        readiness: Score?,
        stepBase: Int,
        sleepGoalMin: Int,
        bedtime: LocalTime,
        steps: Int,
        calmBreaks: Int = 0,
    ): List<Goal> {
        val r = readiness?.value
        val (factor, why) = when {
            r == null -> 1.0 to null
            r >= 75 -> 1.15 to "raised: well recovered"
            r < 50 -> 0.85 to "lowered: recovery day"
            else -> 1.0 to null
        }
        val target = ((stepBase * factor) / 100).roundToInt() * 100
        return listOf(
            Goal("Steps", target, steps, why),
            Goal("2 calm breaks", 2, calmBreaks, null),
            Goal("In bed by ${bedtime.format(DateTimeFormatter.ofPattern("HH:mm"))}", 0, 0, null),
        )
    }
}
