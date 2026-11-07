package dev.avijit.orbithealth.ui.charts

import androidx.compose.ui.geometry.Offset
import dev.avijit.orbithealth.ring.Sample
import dev.avijit.orbithealth.ring.SleepSegment
import dev.avijit.orbithealth.ring.SleepSession
import dev.avijit.orbithealth.ring.SleepStage
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

/** One cubic Bezier piece: from the previous point, through control points [c1] and [c2], to [end]. */
data class CubicSegment(val c1: Offset, val c2: Offset, val end: Offset)

/** Pure geometry for the custom charts. No Android or Compose runtime, so it is JVM-testable. */
object ChartMath {

    /**
     * The time window a hypnogram is drawn over: the session's start..end widened to cover any segment that spills
     * outside it. The stage line, heart-rate overlay and time labels must all map time through this one window
     * (see [timeX]) or they drift apart. Returned as (startEpochS, endEpochS), end > start.
     */
    fun hypnogramWindow(session: SleepSession): Pair<Long, Long> {
        val segs = session.segments.filter { it.durationSec > 0 }
        var start = session.start.epochSecond
        var end = session.end.epochSecond
        segs.forEach { start = minOf(start, it.start.epochSecond); end = maxOf(end, it.start.epochSecond + it.durationSec) }
        return start to maxOf(end, start + 1)
    }

    /** x of [epochS] within [window] mapped onto [0, widthPx], clamped. */
    fun timeX(epochS: Long, window: Pair<Long, Long>, widthPx: Float): Float {
        val span = (window.second - window.first).coerceAtLeast(1).toFloat()
        return ((epochS - window.first) / span * widthPx).coerceIn(0f, widthPx)
    }

    /**
     * Stepped hypnogram polyline over [window] (default [hypnogramWindow]). Segments are sorted by start time;
     * each contributes a horizontal run on its stage's lane, joined by vertical risers. Gaps between segments are
     * bridged by stretching the previous run, so x never decreases.
     */
    fun hypnogramSteps(
        session: SleepSession,
        widthPx: Float,
        laneYs: Map<SleepStage, Float>,
        window: Pair<Long, Long> = hypnogramWindow(session),
    ): List<Offset> {
        val segs = session.segments.filter { it.durationSec > 0 && it.stage in laneYs }.sortedBy { it.start }
        if (segs.isEmpty() || widthPx <= 0f) return emptyList()
        fun x(epochS: Long) = timeX(epochS, window, widthPx)

        val out = ArrayList<Offset>(segs.size * 2)
        var lastX = 0f
        segs.forEachIndexed { i, s ->
            val y = laneYs.getValue(s.stage)
            val x0 = if (i == 0) x(s.start.epochSecond) else maxOf(lastX, x(s.start.epochSecond))
            val x1 = maxOf(x0, x(s.start.epochSecond + s.durationSec))
            if (i > 0 && x0 > lastX) out += Offset(x0, out.last().y) // stretch the previous run across any gap
            out += Offset(x0, y)
            out += Offset(x1, y)
            lastX = x1
        }
        return out
    }

    /** Stress zone: 0 relaxed (< 26), 1 normal (< 51), 2 medium (< 76), 3 high. */
    fun stressZone(v: Int): Int = when {
        v < 26 -> 0
        v < 51 -> 1
        v < 76 -> 2
        else -> 3
    }

    /** Longest stretch one stress reading can stand for: a gap in the data is not time spent in a zone. */
    const val ZONE_GAP_CAP_SEC = 30 * 60L

    /**
     * Fraction of time in each stress zone. Each reading counts for the time until the next one, capped at
     * [ZONE_GAP_CAP_SEC] so gaps (ring off, not worn) don't inflate the zone before them; the last reading counts
     * for the gap before it, likewise capped. A single reading counts fully. Order of [samples] doesn't matter.
     * Empty = zeros.
     */
    fun timeInZones(samples: List<Sample>): FloatArray {
        val out = FloatArray(4)
        if (samples.isEmpty()) return out
        val sorted = samples.sortedBy { it.time }
        fun gap(a: Int, b: Int) = (sorted[b].time.epochSecond - sorted[a].time.epochSecond).coerceIn(0, ZONE_GAP_CAP_SEC)
        var total = 0f
        sorted.forEachIndexed { i, s ->
            val w = when {
                sorted.size == 1 -> 1L
                i < sorted.lastIndex -> gap(i, i + 1)
                else -> gap(i - 1, i)
            }.toFloat()
            out[stressZone(s.value.toInt())] += w
            total += w
        }
        if (total <= 0f) { // all readings at one instant: fall back to counting them
            out.fill(0f)
            sorted.forEach { out[stressZone(it.value.toInt())] += 1f }
            total = sorted.size.toFloat()
        }
        for (i in out.indices) out[i] /= total
        return out
    }

    /** Stage of the segment covering [epochS] (start inclusive, end exclusive), or null outside every segment. */
    fun stageAt(session: SleepSession, epochS: Long): SleepStage? =
        session.segments.firstOrNull { epochS >= it.start.epochSecond && epochS < it.start.epochSecond + it.durationSec }?.stage

    /** Seconds spent in each stage; every stage is present (0 when absent). */
    fun stageTotals(session: SleepSession): Map<SleepStage, Int> =
        SleepStage.entries.associateWith { st -> session.segments.filter { it.stage == st }.sumOf { it.durationSec } }

    /** Linear map of [v] from domain [d0, d1] onto range [r0, r1], clamped. A flat domain maps to the middle. */
    /**
     * Value range for a sparkline: the data's own min..max, widened about its middle to at least [minSpan] so small
     * wobbles stay small (a 6 bpm drift against a 20 bpm span) and a flat series sits as a flat mid line.
     */
    fun sparkRange(values: List<Float>, minSpan: Float): Pair<Float, Float> {
        val lo = values.min()
        val hi = values.max()
        val span = maxOf(hi - lo, minSpan, 1e-3f)
        if (span == hi - lo) return lo to hi
        val mid = (lo + hi) / 2f
        return (mid - span / 2f) to (mid + span / 2f)
    }

    fun scale(v: Float, d0: Float, d1: Float, r0: Float, r1: Float): Float {
        val t = if (d1 == d0) 0.5f else ((v - d0) / (d1 - d0)).coerceIn(0f, 1f)
        return r0 + (r1 - r0) * t
    }

    /**
     * Catmull-Rom spline through [points] as cubic segments (one fewer than points). Control points are clamped
     * to their segment's x span so the curve never doubles back in time.
     */
    fun smoothSegments(points: List<Offset>): List<CubicSegment> {
        if (points.size < 2) return emptyList()
        return (0 until points.lastIndex).map { i ->
            val p0 = points[maxOf(i - 1, 0)]
            val p1 = points[i]
            val p2 = points[i + 1]
            val p3 = points[minOf(i + 2, points.lastIndex)]
            val c1 = Offset((p1.x + (p2.x - p0.x) / 6f).coerceIn(p1.x, p2.x), p1.y + (p2.y - p0.y) / 6f)
            val c2 = Offset((p2.x - (p3.x - p1.x) / 6f).coerceIn(p1.x, p2.x), p2.y - (p3.y - p1.y) / 6f)
            CubicSegment(c1, c2, p2)
        }
    }

    /**
     * Averages samples into at most [buckets] equal time buckets (empty buckets are dropped). Each entry is
     * (x as a 0..1 fraction of the time span, mean value), with x at the mean time of the bucket's samples.
     */
    fun bucketPoints(samples: List<Sample>, buckets: Int): List<Pair<Float, Float>> {
        if (samples.isEmpty() || buckets <= 0) return emptyList()
        val sorted = samples.sortedBy { it.time }
        val t0 = sorted.first().time.epochSecond
        val span = (sorted.last().time.epochSecond - t0).toFloat()
        if (span <= 0f) return listOf(0.5f to sorted.map { it.value }.average().toFloat())
        val n = minOf(buckets, sorted.size)
        val sumV = DoubleArray(n)
        val sumT = DoubleArray(n)
        val count = IntArray(n)
        sorted.forEach { s ->
            val f = (s.time.epochSecond - t0) / span
            val b = (f * n).toInt().coerceIn(0, n - 1)
            sumV[b] += s.value
            sumT[b] += f.toDouble()
            count[b]++
        }
        return (0 until n).filter { count[it] > 0 }.map { (sumT[it] / count[it]).toFloat() to (sumV[it] / count[it]).toFloat() }
    }

    /** Mean values of [bucketPoints]. */
    fun bucketAverages(samples: List<Sample>, buckets: Int): List<Float> = bucketPoints(samples, buckets).map { it.second }

    /**
     * Whole-hour ticks in [startEpochS, endEpochS] whose local hour is a multiple of [stepHours], as
     * (0..1 fraction of the span, local hour of day). Uses the zone's rules, so DST days stay correct.
     */
    fun hourTicks(startEpochS: Long, endEpochS: Long, zone: ZoneId, stepHours: Int): List<Pair<Float, Int>> {
        if (endEpochS <= startEpochS || stepHours <= 0) return emptyList()
        val span = (endEpochS - startEpochS).toFloat()
        var t = Instant.ofEpochSecond(startEpochS).atZone(zone).truncatedTo(ChronoUnit.HOURS)
        if (t.toEpochSecond() < startEpochS) t = t.plusHours(1)
        val out = ArrayList<Pair<Float, Int>>()
        while (t.toEpochSecond() <= endEpochS) {
            if (t.hour % stepHours == 0) out += ((t.toEpochSecond() - startEpochS) / span) to t.hour
            t = t.plusHours(1)
        }
        return out
    }

    /** Least-squares line through [values] against their index: (slope, intercept), or null if fewer than 2. */
    fun linearFit(values: List<Float>): Pair<Float, Float>? {
        if (values.size < 2) return null
        val n = values.size
        val mx = (n - 1) / 2.0
        val my = values.average()
        var num = 0.0
        var den = 0.0
        values.forEachIndexed { i, v -> num += (i - mx) * (v - my); den += (i - mx) * (i - mx) }
        val slope = (num / den).toFloat()
        return slope to (my - slope * mx).toFloat()
    }

    /**
     * Whole percentages for [fractions] that add up to exactly 100 (largest remainder), so a legend of zones never
     * reads 99% or 101%. All zeros stay zeros.
     */
    fun percentages(fractions: FloatArray): IntArray {
        val total = fractions.sum()
        if (total <= 0f) return IntArray(fractions.size)
        val exact = fractions.map { it / total * 100.0 }
        val out = exact.map { kotlin.math.floor(it).toInt() }.toIntArray()
        var left = 100 - out.sum()
        exact.indices.sortedByDescending { exact[it] - out[it] }.forEach { i -> if (left > 0) { out[i]++; left-- } }
        return out
    }

    /**
     * Left edges for axis labels centred at [fractions] (0..1) of [total] px, each kept inside the width; a label that
     * would come within [gap] px of the one before is skipped (null). The last label (usually "Now" or today) always
     * shows: if it collides, the label before it gives way instead, unless that is the first.
     */
    fun axisLayout(fractions: List<Float>, widths: List<Int>, total: Int, gap: Int): List<Int?> {
        val xs = fractions.indices.map { i ->
            (fractions[i] * total - widths[i] / 2f).roundToInt().coerceIn(0, (total - widths[i]).coerceAtLeast(0))
        }
        val out = MutableList<Int?>(xs.size) { null }
        var prev = -1
        for (i in xs.indices) {
            val fits = prev < 0 || xs[i] >= xs[prev] + widths[prev] + gap
            when {
                fits -> { out[i] = xs[i]; prev = i }
                i == xs.lastIndex && prev > 0 -> {
                    out[prev] = null
                    // Re-check against the label before the one that gave way.
                    val before = (prev - 1 downTo 0).firstOrNull { out[it] != null }
                    if (before == null || xs[i] >= xs[before] + widths[before] + gap) out[i] = xs[i]
                }
            }
        }
        return out
    }

    /**
     * Stage runs for drawing (never for totals): a run shorter than [minSec] is folded into the run before it (the
     * first run into the next), then neighbours of the same stage are merged, so a 40-second flicker doesn't draw as a
     * sliver with two risers. Time order and total span are kept.
     */
    fun displaySegments(segments: List<SleepSegment>, minSec: Int): List<SleepSegment> {
        val runs = segments.filter { it.durationSec > 0 }.sortedBy { it.start }.toMutableList()
        if (runs.size < 2 || minSec <= 0) return runs
        var changed = true
        while (changed && runs.size > 1) {
            changed = false
            // The shortest run under the threshold goes first, so a cluster collapses into its dominant stage.
            val i = runs.indices.filter { runs[it].durationSec < minSec }.minByOrNull { runs[it].durationSec } ?: break
            val into = if (i > 0) i - 1 else i + 1
            val a = minOf(i, into)
            val b = maxOf(i, into)
            val keep = runs[into]
            val start = runs[a].start
            val end = runs[b].start.plusSeconds(runs[b].durationSec.toLong())
            runs[a] = SleepSegment(keep.stage, start, (end.epochSecond - start.epochSecond).toInt())
            runs.removeAt(b)
            changed = true
        }
        // Merge same-stage neighbours.
        val merged = ArrayList<SleepSegment>()
        runs.forEach { r ->
            val last = merged.lastOrNull()
            if (last != null && last.stage == r.stage) {
                val end = r.start.plusSeconds(r.durationSec.toLong())
                merged[merged.lastIndex] = last.copy(durationSec = (end.epochSecond - last.start.epochSecond).toInt())
            } else merged += r
        }
        return merged
    }
}
