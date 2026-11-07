package dev.avijit.orbithealth.ui.charts

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.avijit.orbithealth.ui.theme.Manrope
import dev.avijit.orbithealth.ui.theme.OrbitColors
import dev.avijit.orbithealth.ui.theme.OrbitPalette
import dev.avijit.orbithealth.ui.theme.OrbitTypography
import dev.avijit.orbithealth.ui.theme.lineBox
import androidx.compose.ui.unit.dp

/** Smooth path through [points] (Catmull-Rom via [ChartMath.smoothSegments]); a single point is a dot-path. */
internal fun smoothPath(points: List<Offset>): Path = Path().apply {
    if (points.isEmpty()) return@apply
    moveTo(points[0].x, points[0].y)
    ChartMath.smoothSegments(points).forEach { cubicTo(it.c1.x, it.c1.y, it.c2.x, it.c2.y, it.end.x, it.end.y) }
}

/** Chart caption text style (axis labels, lane names) of the older glass charts. */
internal val AxisStyle = TextStyle(fontFamily = Manrope, fontSize = 11.5.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.2.sp, color = OrbitColors.TextPrimary.copy(alpha = 0.45f))

/** Calm empty state laid over a chart's dim track ([message] empty: the track alone). */
@Composable
internal fun ChartFrame(empty: Boolean, modifier: Modifier, message: String = "No data yet", chart: @Composable BoxScope.() -> Unit) {
    Box(modifier) {
        chart()
        if (empty && message.isNotEmpty()) {
            Text(message, style = OrbitTypography.Caption, modifier = Modifier.align(Alignment.Center).lineBox(OrbitTypography.Caption))
        }
    }
}

/**
 * Axis captions (tokens caption, 11/14) at their x along the chart (0..1): each centred on its point, kept inside the
 * width, so the first sits flush left and "Now" flush right. [highlight] labels are ink (on skies the rest are
 * inkSky2 via [color]).
 */
@Composable
fun ChartAxis(
    labels: List<Pair<Float, String>>,
    modifier: Modifier = Modifier,
    color: Color = OrbitPalette.Ink2,
    highlight: (String) -> Boolean = { false },
) {
    Layout(
        content = {
            labels.forEach { (_, text) ->
                Text(
                    text, style = OrbitTypography.Caption, color = if (highlight(text)) OrbitPalette.Ink else color, maxLines = 1,
                    modifier = Modifier.lineBox(OrbitTypography.Caption),
                )
            }
        },
        modifier = modifier,
    ) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0)) }
        val w = constraints.maxWidth
        val h = placeables.maxOfOrNull { it.height } ?: 0
        // Colliding labels give way (the last one always shows), so short spans never print text over text.
        val xs = ChartMath.axisLayout(labels.map { it.first }, placeables.map { it.width }, w, 6.dp.roundToPx())
        layout(w, h) {
            placeables.forEachIndexed { i, p -> xs[i]?.let { p.place(it, 0) } }
        }
    }
}
