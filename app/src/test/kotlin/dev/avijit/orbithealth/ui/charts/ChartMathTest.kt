package dev.avijit.orbithealth.ui.charts

import dev.avijit.orbithealth.ring.Metric
import dev.avijit.orbithealth.ring.Sample
import dev.avijit.orbithealth.ring.SampleSource
import dev.avijit.orbithealth.ring.SleepSegment
import dev.avijit.orbithealth.ring.SleepSession
import dev.avijit.orbithealth.ring.SleepStage
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ChartMathTest {
    private val t0 = Instant.parse("2026-11-04T23:41:00Z")
    private val lanes = mapOf(
        SleepStage.AWAKE to 10f, SleepStage.REM to 40f, SleepStage.LIGHT to 70f, SleepStage.DEEP to 100f,
    )

    private fun seg(stage: SleepStage, startMin: Long, durMin: Int) =
        SleepSegment(stage, t0.plusSeconds(startMin * 60), durMin * 60)

    private fun session(vararg segs: SleepSegment): SleepSession {
        val end = segs.maxOf { it.start.plusSeconds(it.durationSec.toLong()) }
        return SleepSession(t0, end, segs.toList())
    }

    private fun stress(vararg v: Int) = v.mapIndexed { i, x ->
        Sample(Metric.STRESS, t0.plusSeconds(i * 600L), x.toDouble(), SampleSource.HISTORY)
    }

    @Test
    fun hypnogramStepsAreMonotonicInX() {
        // Deliberately out of order, with a gap between LIGHT and REM.
        val s = session(
            seg(SleepStage.DEEP, 20, 40),
            seg(SleepStage.AWAKE, 0, 5),
            seg(SleepStage.LIGHT, 5, 15),
            seg(SleepStage.REM, 70, 30),
            seg(SleepStage.LIGHT, 60, 10),
        )
        val pts = ChartMath.hypnogramSteps(s, 400f, lanes)
        assertTrue(pts.isNotEmpty())
        pts.zipWithNext().forEach { (a, b) -> assertTrue("x went backwards: $a -> $b", b.x >= a.x) }
        assertEquals(0f, pts.first().x, 0.001f)
        assertEquals(400f, pts.last().x, 0.001f)
        assertEquals(10f, pts.first().y, 0.001f) // starts in the AWAKE lane
        assertEquals(40f, pts.last().y, 0.001f) // ends in the REM lane
        // Every horizontal run sits on a lane, every vertical run shares x.
        pts.forEach { assertTrue(it.y in lanes.values) }
    }

    @Test
    fun hypnogramStepsHandleEmptySession() {
        val empty = SleepSession(t0, t0.plusSeconds(3600), emptyList())
        assertTrue(ChartMath.hypnogramSteps(empty, 300f, lanes).isEmpty())
    }

    @Test
    fun stressZoneBoundaries() {
        assertEquals(0, ChartMath.stressZone(0))
        assertEquals(0, ChartMath.stressZone(25))
        assertEquals(1, ChartMath.stressZone(26))
        assertEquals(1, ChartMath.stressZone(50))
        assertEquals(2, ChartMath.stressZone(51))
        assertEquals(2, ChartMath.stressZone(75))
        assertEquals(3, ChartMath.stressZone(76))
        assertEquals(3, ChartMath.stressZone(100))
    }

    @Test
    fun timeInZonesSumsToOne() {
        val z = ChartMath.timeInZones(stress(10, 20, 30, 40, 60, 80, 90, 12))
        assertEquals(4, z.size)
        assertEquals(1f, z.sum(), 0.0001f)
        assertArrayEquals(floatArrayOf(3 / 8f, 2 / 8f, 1 / 8f, 2 / 8f), z, 0.0001f)
    }

    @Test
    fun timeInZonesWeightsReadingsByTimeToTheNextCappedAt30Min() {
        // Relaxed 10:00 (10 min to the next), high 10:10 then a 3 h gap (capped at 30 min), relaxed 13:10 (last: its
        // previous gap, capped at 30 min). Counting readings would say 2/3 relaxed; by time it is 40/70.
        fun at(min: Long, v: Int) = Sample(Metric.STRESS, t0.plusSeconds(min * 60), v.toDouble(), SampleSource.HISTORY)
        val z = ChartMath.timeInZones(listOf(at(0, 10), at(10, 90), at(190, 12)))
        assertArrayEquals(floatArrayOf(40 / 70f, 0f, 0f, 30 / 70f), z, 0.0001f)
        // Order does not matter, and one reading counts fully.
        assertArrayEquals(z, ChartMath.timeInZones(listOf(at(190, 12), at(0, 10), at(10, 90))), 0.0001f)
        assertArrayEquals(floatArrayOf(0f, 0f, 1f, 0f), ChartMath.timeInZones(listOf(at(0, 60))), 0.0001f)
    }

    @Test
    fun stageAtFindsTheSegmentUnderATime() {
        val s = session(seg(SleepStage.AWAKE, 0, 5), seg(SleepStage.LIGHT, 5, 15), seg(SleepStage.DEEP, 20, 40))
        assertEquals(SleepStage.LIGHT, ChartMath.stageAt(s, t0.epochSecond + 6 * 60))
        assertEquals(SleepStage.DEEP, ChartMath.stageAt(s, t0.epochSecond + 59 * 60))
        assertEquals(null, ChartMath.stageAt(s, t0.epochSecond + 61 * 60))
    }

    @Test
    fun timeInZonesEmptyIsAllZero() {
        assertArrayEquals(FloatArray(4), ChartMath.timeInZones(emptyList()), 0f)
    }

    @Test
    fun stageTotalsSumDurationsPerStage() {
        val s = session(seg(SleepStage.LIGHT, 0, 30), seg(SleepStage.DEEP, 30, 20), seg(SleepStage.LIGHT, 50, 10))
        val t = ChartMath.stageTotals(s)
        assertEquals(40 * 60, t[SleepStage.LIGHT])
        assertEquals(20 * 60, t[SleepStage.DEEP])
        assertEquals(0, t[SleepStage.REM])
        assertEquals(0, t[SleepStage.AWAKE])
    }

    @Test
    fun scaleMapsDomainOntoRangeAndClamps() {
        assertEquals(100f, ChartMath.scale(0f, 0f, 100f, 100f, 0f), 0.001f)
        assertEquals(0f, ChartMath.scale(100f, 0f, 100f, 100f, 0f), 0.001f)
        assertEquals(50f, ChartMath.scale(50f, 0f, 100f, 100f, 0f), 0.001f)
        assertEquals(0f, ChartMath.scale(150f, 0f, 100f, 100f, 0f), 0.001f) // clamped
        assertEquals(50f, ChartMath.scale(7f, 7f, 7f, 100f, 0f), 0.001f) // flat domain -> middle
    }

    @Test
    fun smoothSegmentsPassThroughEveryPoint() {
        val pts = listOf(0f to 10f, 10f to 0f, 20f to 10f, 30f to 5f)
        val segs = ChartMath.smoothSegments(pts.map { androidx.compose.ui.geometry.Offset(it.first, it.second) })
        assertEquals(pts.size - 1, segs.size)
        segs.forEachIndexed { i, s ->
            assertEquals(pts[i + 1].first, s.end.x, 0.001f)
            assertEquals(pts[i + 1].second, s.end.y, 0.001f)
            // Control points stay inside their segment's x span, so the curve never loops back.
            assertTrue(s.c1.x in pts[i].first..pts[i + 1].first)
            assertTrue(s.c2.x in pts[i].first..pts[i + 1].first)
        }
    }

    @Test
    fun bucketAveragesSpreadSamplesAcrossBuckets() {
        val b = ChartMath.bucketAverages(stress(10, 20, 30, 40), buckets = 2)
        assertEquals(listOf(15f, 35f), b)
        assertTrue(ChartMath.bucketAverages(emptyList(), 10).isEmpty())
        assertEquals(3, ChartMath.bucketAverages(stress(1, 2, 3), buckets = 10).size) // never more than samples
    }

    @Test
    fun hourTicksFallOnStepMultiplesInLocalTime() {
        val zone = java.time.ZoneId.of("Europe/London")
        val start = java.time.LocalDateTime.parse("2026-11-05T05:40:00").atZone(zone).toEpochSecond()
        val end = java.time.LocalDateTime.parse("2026-11-05T21:00:00").atZone(zone).toEpochSecond()
        val ticks = ChartMath.hourTicks(start, end, zone, 3)
        assertEquals(listOf(6, 9, 12, 15, 18, 21), ticks.map { it.second })
        assertEquals(1f, ticks.last().first, 0.0001f)
        assertTrue(ticks.first().first > 0f)
        assertTrue(ChartMath.hourTicks(end, start, zone, 3).isEmpty())
    }

    @Test
    fun linearFitFindsSlopeAndIntercept() {
        val (m, b) = ChartMath.linearFit(listOf(1f, 2f, 3f, 4f))!!
        assertEquals(1f, m, 0.0001f)
        assertEquals(1f, b, 0.0001f)
        assertEquals(null, ChartMath.linearFit(listOf(5f)))
    }

    @Test
    fun hypnogramUsesSharedWindowWhenSegmentsSpillOutside() {
        // Session says 00:10..01:00 but the ring reports segments from 00:00 to 01:10.
        val segs = listOf(seg(SleepStage.LIGHT, 0, 30), seg(SleepStage.DEEP, 30, 20), seg(SleepStage.REM, 50, 20))
        val s = SleepSession(t0.plusSeconds(600), t0.plusSeconds(3600), segs)
        val w = ChartMath.hypnogramWindow(s)
        assertEquals(t0.epochSecond, w.first)
        assertEquals(t0.epochSecond + 70 * 60, w.second)
        val width = 700f
        val pts = ChartMath.hypnogramSteps(s, width, lanes)
        // The DEEP run starts at minute 30; the overlay/labels mapping must put the same instant at the same x.
        val deepStartX = pts.first { it.y == 100f }.x
        assertEquals(ChartMath.timeX(t0.epochSecond + 30 * 60, w, width), deepStartX, 0.001f)
        assertEquals(300f, deepStartX, 0.001f)
        // Session end (minute 60) lands inside the plot, not at its right edge.
        assertEquals(600f, ChartMath.timeX(s.end.epochSecond, w, width), 0.001f)
    }

    @Test
    fun sparkRangeKeepsSmallVariationsLowAmplitude() {
        // Heart 70..76 bpm with a 20 bpm minimum span: the 6 bpm wobble uses under a third of the height, centred.
        val (lo, hi) = ChartMath.sparkRange(listOf(71f, 72f, 70f, 75f, 73f, 76f, 74f), minSpan = 20f)
        assertEquals(20f, hi - lo, 0.001f)
        assertEquals(73f, (lo + hi) / 2f, 0.001f)
        // A flat series stays a flat line in the middle, never stretched to the edges.
        val (flo, fhi) = ChartMath.sparkRange(listOf(61f, 61f, 61f), minSpan = 20f)
        assertEquals(51f, flo, 0.001f)
        assertEquals(71f, fhi, 0.001f)
        // A series wider than the minimum span uses its own range.
        assertEquals(40f to 100f, ChartMath.sparkRange(listOf(40f, 100f, 70f), minSpan = 20f))
        // Without a minimum a flat series still gets a non-zero span (no divide by zero).
        val (zlo, zhi) = ChartMath.sparkRange(listOf(5f, 5f), minSpan = 0f)
        assertTrue(zhi > zlo)
    }

    @Test
    fun zonePercentagesSumToAHundredByLargestRemainder() {
        assertArrayEquals(intArrayOf(46, 31, 17, 6), ChartMath.percentages(floatArrayOf(0.464f, 0.312f, 0.168f, 0.056f)))
        assertArrayEquals(intArrayOf(34, 33, 33, 0), ChartMath.percentages(floatArrayOf(1f / 3, 1f / 3, 1f / 3, 0f)))
        assertArrayEquals(intArrayOf(0, 0, 0, 0), ChartMath.percentages(FloatArray(4)))
    }

    @Test fun axisLabelsThatWouldCollideGiveWayAndTheLastAlwaysShows() {
        // 300px, labels 20px wide, 6px gap.
        val w = listOf(20, 20, 20, 20)
        assertEquals(listOf(0, 80, 170, 280), ChartMath.axisLayout(listOf(0f, 0.3f, 0.6f, 1f), w, 300, 6))
        // The third would overlap the second: skipped.
        assertEquals(listOf(0, 80, null, 280), ChartMath.axisLayout(listOf(0f, 0.3f, 0.33f, 1f), w, 300, 6))
        // "Now" right next to the tick before it: the tick gives way.
        assertEquals(listOf(0, 140, null, 170), ChartMath.axisLayout(listOf(0f, 0.5f, 0.55f, 0.6f), w, 300, 6))
    }

    @Test fun displaySegmentsFoldFlickersIntoTheirNeighbours() {
        val t0 = Instant.parse("2026-11-06T00:00:00Z")
        var t = t0
        fun seg(st: SleepStage, min: Int) = SleepSegment(st, t, min * 60).also { t = t.plusSeconds(min * 60L) }
        val segs = listOf(
            seg(SleepStage.LIGHT, 40), seg(SleepStage.REM, 1), seg(SleepStage.LIGHT, 30),
            seg(SleepStage.DEEP, 50), seg(SleepStage.AWAKE, 2), seg(SleepStage.DEEP, 20),
        )
        val out = ChartMath.displaySegments(segs, 5 * 60)
        assertEquals(listOf(SleepStage.LIGHT, SleepStage.DEEP), out.map { it.stage })
        // The span is kept: 143 minutes from the first start.
        assertEquals(t0, out.first().start)
        assertEquals(143 * 60, out.sumOf { it.durationSec })
        // Nothing under the threshold: unchanged.
        assertEquals(segs, ChartMath.displaySegments(segs, 30))
    }
}
