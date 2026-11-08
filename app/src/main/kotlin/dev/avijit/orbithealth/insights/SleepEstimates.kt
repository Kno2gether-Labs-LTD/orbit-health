package dev.avijit.orbithealth.insights

import dev.avijit.orbithealth.ring.SleepSession
import java.time.Duration
import java.time.Instant
import kotlin.math.min
import kotlin.math.roundToInt

/** Where a suggestion stands: waiting for the user, kept, or turned down (never suggested again). */
enum class EstimateStatus { SUGGESTED, CONFIRMED, DISMISSED }

/**
 * A stored estimate. [start]..[end] are what the app shows (the user's times once adjusted); [detectedStart] ..
 * [detectedEnd] are what was suggested. [decidedAt] is when the user answered (null while [EstimateStatus.SUGGESTED]).
 */
data class SleepEstimate(
    val id: Long,
    val kind: SleepKind,
    val status: EstimateStatus,
    val start: Instant,
    val end: Instant,
    val detectedStart: Instant,
    val detectedEnd: Instant,
    val lowestHr: Int?,
    val lowestSpo2: Int?,
    val confidence: Double,
    val decidedAt: Instant? = null,
) {
    val durationSec: Int get() = Duration.between(start, end).seconds.toInt()

    /** As a ring-shaped session with no segments (no stages are known), for code that only needs the times. */
    fun asSession(): SleepSession = SleepSession(start, end, emptyList())

    fun overlaps(from: Instant, to: Instant): Boolean = start < to && end > from

    /** The decision [SleepTuning] learns from; null while undecided. */
    fun decision(): SleepDecision? = when (status) {
        EstimateStatus.SUGGESTED -> null
        EstimateStatus.CONFIRMED -> SleepDecision(kind, SleepVerdict.CONFIRMED, detectedStart, detectedEnd, start, end)
        EstimateStatus.DISMISSED -> SleepDecision(kind, SleepVerdict.DISMISSED, detectedStart, detectedEnd)
    }
}

/** What a detection run changes in storage (see [SleepEstimates.plan]). */
data class EstimatePlan(
    val insert: List<EstimatedSleep> = emptyList(),
    /** Suggestions refined by newer data: the stored id with its new detection. */
    val update: List<Pair<Long, EstimatedSleep>> = emptyList(),
    /** Suggestions no longer seen (e.g. the ring's own night has since arrived). */
    val delete: List<Long> = emptyList(),
) {
    val isEmpty: Boolean get() = insert.isEmpty() && update.isEmpty() && delete.isEmpty()
}

/** Rules for storing and showing estimates next to the ring's own nights. */
object SleepEstimates {
    /**
     * Idempotent merge of a fresh detection over [scanFrom]..[scanTo] into what is stored:
     * - a detection overlapping a confirmed or dismissed estimate is ignored: a confirmed night is never overridden and
     *   a dismissed one never comes back;
     * - a detection overlapping a suggestion refreshes it in place (same id) when anything changed;
     * - otherwise it is inserted as a new suggestion;
     * - a suggestion ending inside the scanned window that no detection overlaps any more is deleted, as is any
     *   suggestion that a ring session now overlaps.
     * Running it twice with the same input changes nothing the second time.
     */
    fun plan(
        stored: List<SleepEstimate>,
        detected: List<EstimatedSleep>,
        ringSessions: List<SleepSession>,
        scanFrom: Instant,
        scanTo: Instant,
    ): EstimatePlan {
        val decided = stored.filter { it.status != EstimateStatus.SUGGESTED }
        val suggested = stored.filter { it.status == EstimateStatus.SUGGESTED }
        val insert = mutableListOf<EstimatedSleep>()
        val update = mutableListOf<Pair<Long, EstimatedSleep>>()
        val kept = mutableSetOf<Long>()
        for (d in detected) {
            if (decided.any { it.overlaps(d.start, d.end) || overlapsDetected(it, d) }) continue
            val match = suggested.firstOrNull { it.id !in kept && it.overlaps(d.start, d.end) }
            if (match == null) {
                insert += d
            } else {
                kept += match.id
                if (match.start != d.start || match.end != d.end || match.kind != d.kind || match.lowestHr != d.lowestHr ||
                    match.lowestSpo2 != d.lowestSpo2 || match.confidence != d.confidence
                ) update += match.id to d
            }
        }
        val delete = suggested.filter { s ->
            s.id !in kept && (
                (s.end > scanFrom && s.end <= scanTo) ||
                    ringSessions.any { it.start < s.end && it.end > s.start }
                )
        }.map { it.id }
        return EstimatePlan(insert, update, delete)
    }

    private fun overlapsDetected(e: SleepEstimate, d: EstimatedSleep): Boolean = e.detectedStart < d.end && e.detectedEnd > d.start

    /**
     * The confirmed estimated night for the day [dayStart, dayEnd) (the longest ending in it), or null when the ring
     * has a night that day (the ring always wins) or none was confirmed.
     */
    fun confirmedNight(
        estimates: List<SleepEstimate>,
        ringSessions: List<SleepSession>,
        dayStart: Instant,
        dayEnd: Instant,
    ): SleepEstimate? {
        if (RestingHr.mainSleep(ringSessions, dayStart, dayEnd) != null) return null
        return estimates.filter {
            it.status == EstimateStatus.CONFIRMED && it.kind == SleepKind.NIGHT && it.end >= dayStart && it.end < dayEnd &&
                ringSessions.none { r -> r.start < it.end && r.end > it.start }
        }.maxByOrNull { it.durationSec }
    }

    /**
     * Confirmed estimated nights to show beside the ring's [ringNights] (sessions with stages): one per wake day, and
     * none on a day the ring has a night or where it overlaps a ring session.
     */
    fun confirmedNights(estimates: List<SleepEstimate>, ringNights: List<SleepSession>, zone: java.time.ZoneId): List<SleepEstimate> {
        val ringDays = ringNights.map { it.end.atZone(zone).toLocalDate() }.toSet()
        return estimates
            .filter { it.status == EstimateStatus.CONFIRMED && it.kind == SleepKind.NIGHT }
            .filter { e -> e.end.atZone(zone).toLocalDate() !in ringDays && ringNights.none { it.start < e.end && it.end > e.start } }
            .groupBy { it.end.atZone(zone).toLocalDate() }
            .map { (_, l) -> l.maxBy { it.durationSec } }
            .sortedBy { it.start }
    }

    /** The newest suggestion of [kind] ending at or after [since], or null. */
    fun pending(estimates: List<SleepEstimate>, kind: SleepKind, since: Instant): SleepEstimate? =
        estimates.filter { it.status == EstimateStatus.SUGGESTED && it.kind == kind && it.end >= since }.maxByOrNull { it.end }

    /**
     * The newest waiting night suggestion ending since [since], unless the ring has since recorded a night for that wake
     * day (then there is nothing to ask).
     */
    fun pendingNight(estimates: List<SleepEstimate>, ringSessions: List<SleepSession>, since: Instant, zone: java.time.ZoneId): SleepEstimate? {
        val e = pending(estimates, SleepKind.NIGHT, since) ?: return null
        val d = e.end.atZone(zone).toLocalDate()
        val ds = d.atStartOfDay(zone).toInstant()
        val de = d.plusDays(1).atStartOfDay(zone).toInstant()
        return if (RestingHr.mainSleep(ringSessions, ds, de) != null) null else e
    }

    /** Highest score an estimated night can reach: without stages, quality is unknown, so it never reads "Optimal". */
    const val ESTIMATE_SCORE_CAP = 80

    /**
     * Sleep score for an estimated night: [ESTIMATE_SCORE_CAP] × min(1, duration / goal). Duration only, because there
     * are no stages or awake time to weigh.
     */
    fun score(e: SleepEstimate, goalMin: Int): Score? {
        if (goalMin <= 0 || e.durationSec <= 0) return null
        val part = min(1.0, e.durationSec / (goalMin * 60.0))
        val value = (ESTIMATE_SCORE_CAP * part).roundToInt().coerceIn(0, 100)
        val m = (e.durationSec / 60.0).roundToInt()
        return Score(
            value,
            listOf(
                "Slept ${m / 60}h ${m % 60}m of ${goalMin / 60}h ${goalMin % 60}m goal",
                "Estimated from heart rate and movement, no stages",
            ),
        )
    }
}
