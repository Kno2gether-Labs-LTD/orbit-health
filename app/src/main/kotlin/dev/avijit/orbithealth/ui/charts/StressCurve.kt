package dev.avijit.orbithealth.ui.charts

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import dev.avijit.orbithealth.ring.Sample
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import dev.avijit.orbithealth.ui.theme.OrbitPalette
import dev.avijit.orbithealth.ui.theme.OrbitTypography
import dev.avijit.orbithealth.ui.theme.lineBox
import java.time.ZoneId
import kotlin.math.ceil

/**
 * Stress through the day, the Living Skies curve (tokens §5 Charts) as on the Calm sky: 0–80 (higher if needed) over
 * [height], the area #F2B65E 42% → #3FC1B0 4%, the 2dp stroke graded warmer towards the top (#F2B65E, #9ED0A0 at 55%,
 * #3FC1B0), the now marker on the last point, and the caption axis in [axisColor] 6dp below. [axis] replaces the hour
 * axis with custom labels at 0..1 positions (e.g. weekdays for a week of daily means). Fewer than two readings show a
 * calm empty state.
 */
@Composable
fun StressCurve(
    samples: List<Sample>,
    modifier: Modifier = Modifier,
    axis: List<Pair<Float, String>>? = null,
    height: Dp = 86.dp,
    axisColor: Color = OrbitPalette.InkSky2,
) {
    LivingStressCurve(samples, modifier, axis, height, axisColor)
}

/** Data prep for [StressCurve], done once per samples list rather than per frame. */
private class StressData(val points: List<Pair<Float, Float>>, val peakIndex: Int, val ticks: List<Pair<Float, String>>) {
    fun withTicks(t: List<Pair<Float, String>>) = StressData(points, peakIndex, t)

    companion object {
        fun of(samples: List<Sample>, zone: ZoneId): StressData {
            val pts = ChartMath.bucketPoints(samples, 36)
            if (pts.size < 2) return StressData(pts, 0, emptyList())
            val first = samples.minOf { it.time.epochSecond }
            val last = samples.maxOf { it.time.epochSecond }
            val hours = (last - first) / 3600f
            val step = when {
                hours <= 6 -> 1
                hours <= 12 -> 2
                else -> ceil(hours / 6f).toInt().coerceAtLeast(3)
            }
            val ticks = ChartMath.hourTicks(first, last, zone, step).map { (f, h) -> f to h.toString().padStart(2, '0') }
            return StressData(pts, pts.indices.maxBy { pts[it].second }, ticks)
        }
    }
}

/**
 * Time in each stress zone, the Living Skies split (tokens §5): one 8dp piece per zone with 3dp gaps and radius 4, then
 * a four-column legend (dot, caption name in [legendColor], the share in 14/18 semibold), the shares adding up to 100.
 */
@Composable
fun StressZoneBar(samples: List<Sample>, modifier: Modifier = Modifier, legendColor: Color = OrbitPalette.InkSky2) {
    val z = remember(samples) { ChartMath.timeInZones(samples) }
    LivingZones(z, samples.isNotEmpty(), modifier, legendColor)
}

@Composable
private fun LivingStressCurve(samples: List<Sample>, modifier: Modifier, axis: List<Pair<Float, String>>?, height: Dp, axisColor: Color) {
    val zone = ZoneId.systemDefault()
    val data = remember(samples, zone, axis) { StressData.of(samples, zone).let { d -> if (axis != null) d.withTicks(axis) else d } }
    val empty = data.points.size < 2
    Column(modifier.fillMaxWidth()) {
        ChartFrame(empty, Modifier.fillMaxWidth().height(height), message = "") {
            Spacer(
                Modifier.fillMaxSize().drawWithCache {
                    val top = 6.dp.toPx()
                    val bottom = size.height - 2.dp.toPx()
                    val maxV = maxOf(80f, (data.points.maxOfOrNull { it.second } ?: 0f) + 4f)
                    val base = size.height - 1.dp.toPx()
                    val dash = PathEffect.dashPathEffect(floatArrayOf(1.dp.toPx(), 5.dp.toPx()))
                    val dotted = OrbitPalette.Ink.copy(alpha = 0.22f)
                    if (empty) {
                        return@drawWithCache onDrawBehind {
                            drawLine(dotted, Offset(0f, base), Offset(size.width, base), 1.4.dp.toPx(), StrokeCap.Round, dash)
                        }
                    }
                    val xy = data.points.map { (f, v) -> Offset(f * size.width, ChartMath.scale(v, 0f, maxV, bottom, top)) }
                    val line = smoothPath(xy)
                    val area = smoothPath(xy).apply { lineTo(xy.last().x, size.height); lineTo(xy.first().x, size.height); close() }
                    val fill = Brush.verticalGradient(
                        listOf(OrbitPalette.StressB.copy(alpha = 0.42f), OrbitPalette.StressA.copy(alpha = 0.04f)),
                        startY = xy.minOf { it.y }, endY = size.height,
                    )
                    val grade = Brush.verticalGradient(
                        0f to OrbitPalette.StressB, 0.55f to OrbitPalette.StressNormal, 1f to OrbitPalette.StressA, startY = 0f, endY = size.height,
                    )
                    val last = xy.last()
                    onDrawBehind {
                        drawPath(area, fill)
                        drawPath(line, grade, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                        drawCircle(OrbitPalette.Optimal.copy(alpha = 0.25f), 7.dp.toPx(), last)
                        drawCircle(OrbitPalette.PlanetCore, 3.5.dp.toPx(), last)
                    }
                },
            )
        }
        if (data.ticks.isNotEmpty()) ChartAxis(data.ticks, Modifier.fillMaxWidth().padding(top = 6.dp), color = axisColor)
    }
}

@Composable
private fun LivingZones(z: FloatArray, hasData: Boolean, modifier: Modifier, legendColor: Color) {
    val names = listOf("Relaxed", "Normal", "Medium", "High")
    val colors = listOf(OrbitPalette.StressRelaxed, OrbitPalette.StressNormal, OrbitPalette.StressMedium, OrbitPalette.StressHigh)
    val pct = remember(z) { ChartMath.percentages(z) }
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().height(8.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            if (!hasData) Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(4.dp)).background(OrbitPalette.Ink.copy(alpha = 0.10f)))
            z.forEachIndexed { i, f ->
                if (hasData && f > 0f) Box(Modifier.weight(f).fillMaxHeight().clip(RoundedCornerShape(4.dp)).background(colors[i]))
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            names.forEachIndexed { i, n ->
                Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).background(colors[i], CircleShape))
                        Text(
                            n, style = OrbitTypography.Caption, color = legendColor, maxLines = 1,
                            modifier = Modifier.padding(start = 5.dp).lineBox(OrbitTypography.Caption),
                        )
                    }
                    Text(
                        if (hasData) "${pct[i]}%" else "—", style = LegendValue, maxLines = 1,
                        modifier = Modifier.padding(top = 3.dp).lineBox(LegendValue),
                    )
                }
            }
        }
    }
}

/** Legend share: Manrope 600 14/18, tabular. */
private val LegendValue = OrbitTypography.BodyStrong.copy(fontSize = 14.sp, lineHeight = 18.sp, fontFeatureSettings = "tnum")
