package dev.avijit.orbithealth.insights

import kotlin.math.sqrt

/** Personal norm for a metric: mean and population standard deviation over [days] daily values. */
data class Baseline(val mean: Double, val sd: Double, val days: Int) {
    /** z = (x - mean) / sd; 0 when sd is 0 so flat baselines never produce NaN/Infinity. */
    fun z(x: Double): Double = if (sd == 0.0) 0.0 else (x - mean) / sd
}

object Baselines {
    const val MIN_DAYS = 3

    /** [values] holds one value per day. Returns null when fewer than [MIN_DAYS] values. */
    fun of(values: List<Double>): Baseline? {
        if (values.size < MIN_DAYS) return null
        val mean = values.average()
        val variance = values.sumOf { (it - mean) * (it - mean) } / values.size
        return Baseline(mean, sqrt(variance), values.size)
    }
}
