package dev.avijit.orbithealth.ui.today

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.avijit.orbithealth.ring.Sample
import dev.avijit.orbithealth.ui.charts.ChartAxis
import dev.avijit.orbithealth.ui.charts.ChartMath
import dev.avijit.orbithealth.ui.theme.OrbitPalette
import dev.avijit.orbithealth.ui.theme.OrbitTypography
import dev.avijit.orbithealth.ui.theme.lineBox
import java.time.ZoneId
import kotlin.math.abs

/** A point of the ribbon: minute of the local day and the stress value there. */
internal data class RibbonPoint(val minute: Float, val value: Float)

internal object StressRibbonMath {
    const val WINDOW_MIN = 12 * 60
    const val BUCKET_MIN = 10

    /** Today's readings in the window [startHour, now], as 10-minute means (empty buckets dropped). */
    fun points(stress: List<Sample>, zone: ZoneId, startHour: Int, nowMinute: Int): List<RibbonPoint> {
        val from = startHour * 60
        return stress.mapNotNull { s ->
            val z = s.time.atZone(zone)
            val m = z.hour * 60 + z.minute
            if (m < from || m > nowMinute) null else m to s.value
        }
            .groupBy { (m, _) -> m / BUCKET_MIN }
            .toSortedMap()
            .map { (_, l) -> RibbonPoint(l.map { it.first }.average().toFloat(), l.map { it.second }.average().toFloat()) }
    }

    /** Ticks this close to now give way to the "Now" label. */
    const val NOW_CLEAR_MIN = 60

    /**
     * Axis labels as (0..1 position, text): the window's 3-hour ticks ("06", "09", ...) and "Now" at its true place,
     * any tick within [NOW_CLEAR_MIN] of now left out. At 10:24 in 06–18: 06, 09, Now, 12, 15, 18 (Now at 0.37).
     */
    fun labels(startHour: Int, nowMinute: Int): List<Pair<Float, String>> {
        val from = startHour * 60
        val nowF = ((nowMinute - from).toFloat() / WINDOW_MIN).coerceIn(0f, 1f)
        val ticks = (0..4).map { startHour + it * 3 }
            .filter { abs(it * 60 - nowMinute) >= NOW_CLEAR_MIN }
            .map { h -> (h * 60 - from).toFloat() / WINDOW_MIN to "%02d".format(h % 24) }
        return (ticks + (nowF to NOW)).sortedBy { it.first }
    }

    const val NOW = "Now"

    /** Where the dotted "rest of the window" starts: just after the last reading (or the left edge with none). */
    fun dotsFrom(lastX: Float?, gapPx: Float): Float = if (lastX == null) 0f else lastX + gapPx
}

/**
 * Today's stress ribbon (tokens §5 Charts): a soft area under a Catmull-Rom curve over a 12-hour window, the fill
 * running from medium amber at the top to relaxed teal, the stroke warmer where it climbs; a now dot, then a dotted
 * baseline for the rest of the window, and the axis below (the tick nearest now reads "Now").
 */
@Composable
internal fun StressRibbon(
    stress: List<Sample>,
    nowMinute: Int,
    modifier: Modifier = Modifier,
    /** Body's Calm sky draws it 86dp tall with inkSky2 labels 6dp below; Today 78dp, ink2, 8dp. */
    chartHeight: Dp = 78.dp,
    axisColor: Color = OrbitPalette.Ink2,
    axisTop: Dp = 8.dp,
) {
    val zone = remember { ZoneId.systemDefault() }
    val start = TodayCopy.stressWindowStart(nowMinute)
    val pts = remember(stress, start, nowMinute) { StressRibbonMath.points(stress, zone, start, nowMinute) }
    Column(modifier.clearAndSetSemantics {}) {
        Canvas(Modifier.fillMaxWidth().height(chartHeight)) {
            val w = size.width
            val h = size.height
            val padT = 6.dp.toPx()
            val padB = 2.dp.toPx()
            val maxV = maxOf(80f, (pts.maxOfOrNull { it.value } ?: 0f) + 4f)
            fun x(minute: Float) = (minute - start * 60) / StressRibbonMath.WINDOW_MIN * w
            fun y(v: Float) = padT + (1f - v / maxV) * (h - padT - padB)
            val offsets = pts.map { Offset(x(it.minute), y(it.value)) }
            if (offsets.size >= 2) {
                val curve = Path().apply {
                    moveTo(offsets[0].x, offsets[0].y)
                    ChartMath.smoothSegments(offsets).forEach { cubicTo(it.c1.x, it.c1.y, it.c2.x, it.c2.y, it.end.x, it.end.y) }
                }
                val area = Path().apply {
                    addPath(curve)
                    lineTo(offsets.last().x, h)
                    lineTo(offsets.first().x, h)
                    close()
                }
                drawPath(area, Brush.verticalGradient(listOf(OrbitPalette.StressB.copy(alpha = 0.42f), OrbitPalette.StressA.copy(alpha = 0.04f)), 0f, h))
                drawPath(
                    curve,
                    Brush.verticalGradient(0f to OrbitPalette.StressB, 0.55f to OrbitPalette.StressNormal, 1f to OrbitPalette.StressA, startY = 0f, endY = h),
                    style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
            }
            val last = offsets.lastOrNull()
            if (last != null) {
                drawCircle(OrbitPalette.Optimal.copy(alpha = 0.25f), 7.dp.toPx(), last)
                drawCircle(OrbitPalette.PlanetCore, 3.5.dp.toPx(), last)
            }
            // The rest of the window: a dotted baseline from just after the last reading, so a quiet spell before now
            // reads as "no readings" rather than as an unexplained gap.
            val dotsFrom = StressRibbonMath.dotsFrom(last?.x, 10.dp.toPx())
            if (dotsFrom < w) {
                drawLine(
                    OrbitPalette.Ink.copy(alpha = 0.22f), Offset(dotsFrom, h - 1.dp.toPx()), Offset(w, h - 1.dp.toPx()),
                    strokeWidth = 1.4.dp.toPx(), cap = StrokeCap.Round,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(1.dp.toPx(), 5.dp.toPx())),
                )
            }
        }
        // Each label at its time, "Now" where now is (tokens §5: the now label is ink, the hours ink2 / inkSky2).
        ChartAxis(
            StressRibbonMath.labels(start, nowMinute), Modifier.padding(top = axisTop).fillMaxWidth(),
            color = axisColor, highlight = { it == StressRibbonMath.NOW },
        )
    }
}
