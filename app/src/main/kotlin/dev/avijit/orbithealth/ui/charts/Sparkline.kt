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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.avijit.orbithealth.ui.theme.OrbitColors

/**
 * Tiny trend for a tile, as in the mockup: a thin 2.2dp polyline (`stroke-width 1.6` on a 290 px phone), no fill,
 * full width x 25dp (the mockup's 18 px). Values are placed on [ChartMath.sparkRange], so pass [minSpan] in the
 * metric's units (about 20 for heart rate) to keep small variations low; 0 stretches the data to the full height.
 * [fill] adds a faint area under the line for larger chart cards; [height] sizes it for a hero card. Fewer than two values draws a dim flat line.
 */
@Composable
fun Sparkline(
    values: List<Float>,
    color: Color,
    modifier: Modifier = Modifier,
    minSpan: Float = 0f,
    fill: Boolean = false,
    height: Dp = 25.dp,
) {
    Spacer(
        modifier.then(Modifier.fillMaxWidth().height(height)).drawWithCache {
            if (values.size < 2) {
                val w = 2.dp.toPx()
                return@drawWithCache onDrawBehind {
                    drawLine(OrbitColors.Track, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), w, StrokeCap.Round)
                }
            }
            val strokeW = 2.2.dp.toPx()
            val pad = strokeW / 2f
            val (lo, hi) = ChartMath.sparkRange(values, minSpan)
            val pts = values.mapIndexed { i, v ->
                Offset(pad + i * (size.width - 2 * pad) / values.lastIndex, ChartMath.scale(v, lo, hi, size.height - pad, pad))
            }
            val line = Path().apply {
                moveTo(pts[0].x, pts[0].y)
                for (p in pts.drop(1)) lineTo(p.x, p.y)
            }
            val area = if (fill) Path().apply {
                addPath(line); lineTo(pts.last().x, size.height); lineTo(pts.first().x, size.height); close()
            } else null
            val areaBrush = Brush.verticalGradient(listOf(color.copy(alpha = 0.18f), Color.Transparent))
            val stroke = Stroke(strokeW, cap = StrokeCap.Round, join = StrokeJoin.Round)
            onDrawBehind {
                if (area != null) drawPath(area, areaBrush)
                drawPath(line, color, style = stroke)
            }
        },
    )
}
