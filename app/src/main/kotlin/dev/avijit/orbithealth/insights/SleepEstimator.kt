package dev.avijit.orbithealth.insights

import dev.avijit.orbithealth.ring.Metric
import dev.avijit.orbithealth.ring.Sample
import dev.avijit.orbithealth.ring.SleepSession
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToInt

/** What an estimated sleep looks like: a main [NIGHT], or a shorter, mostly daytime [NAP]. */
enum class SleepKind { NIGHT, NAP }

/**
 * A sleep the app inferred from heart rate and movement because the ring recorded none. Times only: the ring's stages
 * cannot be inferred, so there are none. [confidence] is 0..1 (see [SleepEstimator]).
 */
data class EstimatedSleep(
    val start: Instant,
    val end: Instant,
    val kind: SleepKind,
    val lowestHr: Int?,
    val lowestSpo2: Int?,
    val confidence: Double,
) {
    val durationSec: Long get() = Duration.between(start, end).seconds
}

/**
 * The knobs [SleepTuning] learns per user. [hrRatio]: a slot counts as asleep when its heart rate is at or under
 * `awake baseline × hrRatio`. [startBiasMin] / [endBiasMin]: minutes added to a detected night's start / end (the
 * user's habit of moving them). [minConfidence]: estimates under this are not suggested.
 */
data class EstimatorParams(
    val hrRatio: Double = SleepEstimator.DEFAULT_HR_RATIO,
    val startBiasMin: Int = 0,
    val endBiasMin: Int = 0,
    val minConfidence: Double = SleepEstimator.DEFAULT_MIN_CONFIDENCE,
)

/**
 * Finds sleep the ring missed, from what the phone does have: heart rate (auto-measured every 10 min), 30-minute step
 * buckets and SpO₂. Pure and deterministic; no accelerometer data reaches the phone, so stillness comes from steps.
 *
 * Rules and defaults:
 * - **Awake baseline**: the median heart rate between 09:00 and 21:00 local, outside ring sleep, over the samples given
 *   (callers pass about a week). Fewer than [MIN_BASELINE_READINGS] readings: no estimates at all (still learning).
 * - **Slots**: the scan window is cut into [SLOT] (10 min) slots. A slot's heart rate is the median of the readings within
 *   ±[HR_HALF_WINDOW] (15 min) of its centre, which smooths over a single high or low reading. No reading there: the
 *   slot is unknown (ring off or not measuring). Slots touching a ring sleep session are blocked.
 * - **Asleep slot**: heart rate ≤ baseline × [EstimatorParams.hrRatio] (default [DEFAULT_HR_RATIO] = 0.90) and the
 *   slot's step bucket ≤ [STILL_STEPS] (60 steps per 30 min) or missing.
 * - **Runs**: consecutive asleep slots, bridging up to [MAX_GAP] (40 min) of awake or unknown slots (a trip to the
 *   bathroom, a gap in readings). A blocked slot always ends a run. A run still touching the newest heart reading, or
 *   followed by less than [MAX_GAP] of data, is open (you may still be asleep) and not reported yet.
 * - **Worn**: at least [MIN_HR_COVERAGE] (60%) of a run's slots must have heart rate, from at least [MIN_READINGS] (3)
 *   readings. No heart rate, no estimate.
 * - **Kind**: [NIGHT_MIN] (3 h) or more is a [SleepKind.NIGHT]; [NAP_MIN] (20 min) up to 3 h is a [SleepKind.NAP] when
 *   its midpoint falls between [DAY_START_HOUR] (09:00) and [DAY_END_HOUR] (21:00); a short run at night is dropped.
 * - **Ring wins**: a night is not suggested when a ring session of 3 h or more ends on the same local day.
 * - **Personal bias**: [EstimatorParams.startBiasMin] / [EstimatorParams.endBiasMin] shift a night's times (naps keep
 *   theirs); the result never shrinks below [NIGHT_MIN].
 * - **Confidence** = 0.40 × heart-rate coverage + 0.35 × depth + 0.25 × stillness, where depth is how far the run's mean
 *   heart rate sits under the baseline (20% under = 1) and stillness is the share of the run's step buckets at or under
 *   [STILL_STEPS] (a missing bucket counts half). Under [EstimatorParams.minConfidence] (default 0.50): dropped.
 */
object SleepEstimator {
    val SLOT: Duration = Duration.ofMinutes(10)
    val HR_HALF_WINDOW: Duration = Duration.ofMinutes(15)
    val STEP_BUCKET: Duration = Duration.ofMinutes(30)
    val MAX_GAP: Duration = Duration.ofMinutes(40)
    val NIGHT_MIN: Duration = Duration.ofHours(3)
    val NAP_MIN: Duration = Duration.ofMinutes(20)
    const val STILL_STEPS = 60.0
    const val MIN_HR_COVERAGE = 0.6
    const val MIN_READINGS = 3
    const val MIN_BASELINE_READINGS = 30
    const val DAY_START_HOUR = 9
    const val DAY_END_HOUR = 21
    const val DEFAULT_HR_RATIO = 0.90
    const val DEFAULT_MIN_CONFIDENCE = 0.50

    /**
     * The median daytime (09:00–21:00 local) heart rate outside ring sleep, or null with fewer than
     * [MIN_BASELINE_READINGS] readings.
     */
    fun awakeBaseline(hr: List<Sample>, ringSessions: List<SleepSession>, zone: ZoneId): Double? {
        val day = hr.filter { s ->
            s.metric == Metric.HEART_RATE &&
                s.time.atZone(zone).hour in DAY_START_HOUR until DAY_END_HOUR &&
                ringSessions.none { s.time >= it.start && s.time < it.end }
        }.map { it.value }
        if (day.size < MIN_BASELINE_READINGS) return null
        return median(day)
    }

    private enum class Slot { ASLEEP, AWAKE, UNKNOWN, BLOCKED }

    /**
     * Estimated sleeps that end inside [from]..[to] (the scan window; slots are aligned to [SLOT]). [hr] should reach a
     * few days further back than [from] for the baseline. [steps] are the ring's 30-minute STEPS buckets.
     */
    fun detect(
        hr: List<Sample>,
        steps: List<Sample>,
        spo2: List<Sample>,
        ringSessions: List<SleepSession>,
        from: Instant,
        to: Instant,
        zone: ZoneId,
        params: EstimatorParams = EstimatorParams(),
    ): List<EstimatedSleep> {
        val heart = hr.filter { it.metric == Metric.HEART_RATE }.sortedBy { it.time }
        val baseline = awakeBaseline(heart, ringSessions, zone) ?: return emptyList()
        val threshold = baseline * params.hrRatio
        val slotMs = SLOT.toMillis()
        val start = Math.floorDiv(from.toEpochMilli(), slotMs) * slotMs
        // Scan only up to the newest heart reading: past it nothing is known yet.
        val lastHr = heart.lastOrNull()?.time?.toEpochMilli() ?: return emptyList()
        val endMs = minOf(to.toEpochMilli(), lastHr + slotMs)
        if (endMs <= start) return emptyList()
        val n = ((endMs - start + slotMs - 1) / slotMs).toInt()

        val times = LongArray(heart.size) { heart[it].time.toEpochMilli() }
        val half = HR_HALF_WINDOW.toMillis()
        val bucketMs = STEP_BUCKET.toMillis()
        val bucketSteps = steps.filter { it.metric == Metric.STEPS }
            .groupBy { Math.floorDiv(it.time.toEpochMilli(), bucketMs) }
            .mapValues { (_, l) -> l.maxOf { it.value } }

        val slotHr = arrayOfNulls<Double>(n)
        val state = Array(n) { i ->
            val a = start + i * slotMs
            val b = a + slotMs
            if (ringSessions.any { it.start.toEpochMilli() < b && it.end.toEpochMilli() > a }) return@Array Slot.BLOCKED
            val centre = a + slotMs / 2
            val lo = lowerBound(times, centre - half)
            val hi = lowerBound(times, centre + half + 1)
            if (hi <= lo) return@Array Slot.UNKNOWN
            val v = median((lo until hi).map { heart[it].value })
            slotHr[i] = v
            val st = bucketSteps[Math.floorDiv(a, bucketMs)]
            if (v <= threshold && (st == null || st <= STILL_STEPS)) Slot.ASLEEP else Slot.AWAKE
        }

        val maxGap = (MAX_GAP.toMillis() / slotMs).toInt()
        val out = mutableListOf<EstimatedSleep>()
        var runStart = -1
        var runEnd = -1
        var gap = 0
        fun close() {
            if (runStart >= 0) candidate(runStart, runEnd, start, slotMs, slotHr, bucketSteps, heart, spo2, ringSessions, baseline, zone, params)
                ?.let { out += it }
            runStart = -1; runEnd = -1; gap = 0
        }
        for (i in 0 until n) {
            when (state[i]) {
                Slot.ASLEEP -> { if (runStart < 0) runStart = i; runEnd = i; gap = 0 }
                Slot.BLOCKED -> close()
                else -> if (runStart >= 0 && ++gap > maxGap) close()
            }
        }
        // A run that reaches the end of the data is still open: the person may not have woken yet.
        return out.filter { it.end <= to }.sortedBy { it.start }
    }

    private fun candidate(
        a: Int,
        b: Int,
        origin: Long,
        slotMs: Long,
        slotHr: Array<Double?>,
        bucketSteps: Map<Long, Double>,
        heart: List<Sample>,
        spo2: List<Sample>,
        ringSessions: List<SleepSession>,
        baseline: Double,
        zone: ZoneId,
        params: EstimatorParams,
    ): EstimatedSleep? {
        var start = Instant.ofEpochMilli(origin + a * slotMs)
        var end = Instant.ofEpochMilli(origin + (b + 1) * slotMs)
        val length = Duration.between(start, end)
        if (length < NAP_MIN) return null
        val count = b - a + 1
        val coverage = (a..b).count { slotHr[it] != null }.toDouble() / count
        if (coverage < MIN_HR_COVERAGE) return null

        val kind = when {
            length >= NIGHT_MIN -> SleepKind.NIGHT
            start.plus(length.dividedBy(2)).atZone(zone).hour in DAY_START_HOUR until DAY_END_HOUR -> SleepKind.NAP
            else -> return null
        }
        if (kind == SleepKind.NIGHT) {
            val wakeDay = end.atZone(zone).toLocalDate()
            val ringNight = ringSessions.any {
                Duration.between(it.start, it.end) >= NIGHT_MIN && it.end.atZone(zone).toLocalDate() == wakeDay
            }
            if (ringNight) return null
        }

        val raw = heart.filter { it.time >= start && it.time < end }
        if (raw.size < MIN_READINGS) return null
        val mean = raw.map { it.value }.average()
        val depth = ((baseline - mean) / (0.20 * baseline)).coerceIn(0.0, 1.0)
        val bucketMs = STEP_BUCKET.toMillis()
        val buckets = (Math.floorDiv(start.toEpochMilli(), bucketMs)..Math.floorDiv(end.toEpochMilli() - 1, bucketMs)).toList()
        val stillness = buckets.map { k ->
            val v = bucketSteps[k]
            when {
                v == null -> 0.5
                v <= STILL_STEPS -> 1.0
                else -> 0.0
            }
        }.average()
        val confidence = (0.40 * coverage + 0.35 * depth + 0.25 * stillness).coerceIn(0.0, 1.0)
        if (confidence < params.minConfidence) return null

        val lowestHr = raw.minOf { it.value }.roundToInt()
        val lowestSpo2 = spo2.filter { it.metric == Metric.SPO2 && it.time >= start && it.time < end }
            .minOfOrNull { it.value }?.roundToInt()
        if (kind == SleepKind.NIGHT && (params.startBiasMin != 0 || params.endBiasMin != 0)) {
            val s = start.plus(Duration.ofMinutes(params.startBiasMin.toLong()))
            val e = end.plus(Duration.ofMinutes(params.endBiasMin.toLong()))
            if (Duration.between(s, e) >= NIGHT_MIN) { start = s; end = e }
        }
        return EstimatedSleep(start, end, kind, lowestHr, lowestSpo2, confidence)
    }

    private fun lowerBound(a: LongArray, key: Long): Int {
        var lo = 0
        var hi = a.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (a[mid] < key) lo = mid + 1 else hi = mid
        }
        return lo
    }

    private fun median(v: List<Double>): Double {
        val s = v.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2.0
    }
}
