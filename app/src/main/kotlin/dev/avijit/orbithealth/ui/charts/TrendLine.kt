package dev.avijit.orbithealth.ui.charts

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.unit.dp
import dev.avijit.orbithealth.ui.theme.OrbitPalette

/**
 * Long-term trend in the Living Skies style (tokens §5 Charts): the curve runs edge to edge with 6dp above and 2dp
 * below, a 2dp [stroke] over an area filling [color] at [fillAlpha] down to nothing ([fill] off: the plain line), and
 * (with [endDot]) the now marker: a 3.5 r warm-white dot in a 7 r halo of [color] at 25%. [xs] places points along the
 * width (0..1, e.g. on the clock) instead of evenly; [range] fixes the value scale, else [minSpan] (metric units) keeps
 * small wobble small. [bars] draws rounded 3dp bars instead, a slot per value less 3dp, from a 6dp floor at [range]'s
 * low to full height at its high, coloured by [barColor]; with [barSlots] the width is split into that many slots and
 * the bars sit right-aligned (today at the right edge), so a short history is a few slim bars, not one wide slab.
 * A single value is its dot at the right edge over a dotted baseline; a line that starts after the left edge is led in
 * by the same dotted baseline and its area fades in over 24dp, so it never ends in a hard vertical edge. Default 64dp.
 */
@Composable
fun TrendLine(
    values: List<Float>,
    color: Color,
    modifier: Modifier = Modifier,
    bars: Boolean = false,
    height: Dp = 64.dp,
    minSpan: Float = 0f,
    fill: Boolean = true,
    endDot: Boolean = true,
    stroke: Color = color,
    fillAlpha: Float = 0.28f,
    xs: List<Float>? = null,
    range: Pair<Float, Float>? = null,
    barColor: ((Float) -> Color)? = null,
    emptyMessage: String = "No data yet",
    barSlots: Int = 0,
) {
    LivingTrend(values, color, modifier, bars, height, minSpan, fill, endDot, stroke, fillAlpha, xs, range, barColor, emptyMessage, barSlots)
}

@Composable
private fun LivingTrend(
    values: List<Float>,
    color: Color,
    modifier: Modifier,
    bars: Boolean,
    height: Dp,
    minSpan: Float,
    fill: Boolean,
    endDot: Boolean,
    stroke: Color,
    fillAlpha: Float,
    xs: List<Float>?,
    range: Pair<Float, Float>?,
    barColor: ((Float) -> Color)?,
    emptyMessage: String,
    barSlots: Int,
) {
    val empty = values.isEmpty()
    ChartFrame(empty = empty, message = emptyMessage, modifier = modifier.then(Modifier.fillMaxWidth().height(height))) {
        Spacer(
            Modifier.fillMaxWidth().height(height).drawWithCache {
                if (empty) {
                    val y = size.height - 1.dp.toPx()
                    return@drawWithCache onDrawBehind {
                        drawLine(OrbitPalette.Ink.copy(alpha = 0.10f), Offset(0f, y), Offset(size.width, y), 2.dp.toPx(), StrokeCap.Round)
                    }
                }
                if (bars) {
                    val n = values.size
                    val slots = maxOf(n, barSlots)
                    val slot = size.width / slots
                    val lead = (slots - n) * slot
                    val inset = 1.5.dp.toPx()
                    val w = (slot - 2 * inset).coerceAtLeast(1f)
                    val (lo, hi) = range ?: (values.min() to values.max())
                    val floor = 6.dp.toPx()
                    val r = CornerRadius(minOf(3.dp.toPx(), w / 2f))
                    val rects = values.mapIndexed { i, v ->
                        val f = if (hi > lo) ((v - lo) / (hi - lo)).coerceIn(0f, 1f) else 1f
                        val h = f * (size.height - floor) + floor
                        Triple(barColor?.invoke(v) ?: color, Offset(lead + i * slot + inset, size.height - h), Size(w, h))
                    }
                    return@drawWithCache onDrawBehind { rects.forEach { (c, o, sz) -> drawRoundRect(c, o, sz, r) } }
                }
                val top = 6.dp.toPx()
                val bottom = size.height - 2.dp.toPx()
                val (lo, hi) = range ?: ChartMath.sparkRange(values, minSpan)
                val pts = values.mapIndexed { i, v ->
                    // A lone value sits at the right edge (it is the latest).
                    val fx = xs?.getOrNull(i) ?: if (values.size == 1) 1f else i.toFloat() / values.lastIndex
                    Offset(fx * size.width, ChartMath.scale(v, lo, hi, bottom, top))
                }
                val dash = PathEffect.dashPathEffect(floatArrayOf(1.dp.toPx(), 5.dp.toPx()))
                val baseY = size.height - 1.dp.toPx()
                // Nothing before the first point: the dotted "no data" baseline leads in, stopping short of it.
                val leadEnd = pts.first().x - 8.dp.toPx()
                val fadeW = 24.dp.toPx()
                val fadesIn = pts.first().x > 1f
                val line = smoothPath(pts)
                val area = smoothPath(pts).apply { lineTo(pts.last().x, size.height); lineTo(pts.first().x, size.height); close() }
                val areaBrush = Brush.verticalGradient(
                    listOf(color.copy(alpha = fillAlpha), color.copy(alpha = 0f)), startY = pts.minOf { it.y }, endY = size.height,
                )
                val strokeStyle = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                val last = pts.last()
                onDrawBehind {
                    if (leadEnd > 0f) {
                        drawLine(OrbitPalette.Ink.copy(alpha = 0.22f), Offset(0f, baseY), Offset(leadEnd, baseY), 1.4.dp.toPx(), StrokeCap.Round, dash)
                    }
                    if (fill && pts.size >= 2) {
                        if (fadesIn) {
                            // Fade the area in from the first point (DstIn mask in its own layer).
                            drawIntoCanvas { it.saveLayer(Rect(Offset.Zero, size), Paint()) }
                            drawPath(area, areaBrush)
                            drawRect(
                                Brush.horizontalGradient(listOf(Color.Transparent, Color.Black), startX = pts.first().x, endX = pts.first().x + fadeW),
                                blendMode = BlendMode.DstIn,
                            )
                            drawIntoCanvas { it.restore() }
                        } else drawPath(area, areaBrush)
                    }
                    if (pts.size >= 2) drawPath(line, stroke, style = strokeStyle)
                    if (endDot) {
                        drawCircle(color.copy(alpha = 0.25f), 7.dp.toPx(), last)
                        drawCircle(OrbitPalette.PlanetCore, 3.5.dp.toPx(), last)
                    }
                }
            },
        )
    }
}
