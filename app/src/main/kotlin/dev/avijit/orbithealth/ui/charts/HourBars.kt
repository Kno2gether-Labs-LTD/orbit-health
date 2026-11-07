package dev.avijit.orbithealth.ui.charts

import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.drawscope.translate
import dev.avijit.orbithealth.ui.theme.OrbitPalette

/**
 * The Living Skies "By hour" chart (tokens §5 Bars), index = hour 0..23: 24 bars 8dp wide spread over the width, 4dp
 * rounded, filled with [brush] (activity #FFC65C → #FF7759 top to bottom; [color] when null); hours without data and
 * the hours after [nowHour] are 3dp ink 12% stubs; the tallest bar reaches 4dp below the top; caption axis 8dp below
 * when [showAxis]. [height] is the bar area's height; all-zero input shows [emptyMessage].
 */
@Composable
fun HourBars(
    perHour: IntArray,
    color: Color,
    modifier: Modifier = Modifier,
    showAxis: Boolean = true,
    nowHour: Int? = null,
    height: Dp = 56.dp,
    emptyMessage: String = "No data yet",
    brush: Brush? = null,
) {
    LivingHourBars(perHour, brush ?: SolidColor(color), modifier, nowHour, height, emptyMessage, showAxis)
}

@Composable
private fun LivingHourBars(perHour: IntArray, brush: Brush, modifier: Modifier, nowHour: Int?, height: Dp, emptyMessage: String, showAxis: Boolean) {
    val max = perHour.maxOrNull() ?: 0
    Column(modifier.fillMaxWidth()) {
        ChartFrame(empty = max <= 0, message = emptyMessage, modifier = Modifier.fillMaxWidth().height(height)) {
            Spacer(
                Modifier.fillMaxWidth().height(height).drawWithCache {
                    val n = 24
                    val bw = 8.dp.toPx()
                    val gap = (size.width - n * bw) / (n - 1)
                    val stub = 3.dp.toPx()
                    val last = nowHour?.coerceIn(0, n - 1) ?: (n - 1)
                    val stubs = mutableListOf<Offset>()
                    // Hours already gone with no steps read a touch firmer than the hours still to come.
                    val pastStubs = mutableListOf<Offset>()
                    val bars = mutableListOf<Pair<Offset, Size>>()
                    for (h in 0 until n) {
                        val x = h * (bw + gap)
                        val v = perHour.getOrElse(h) { 0 }
                        if (h > last || max <= 0) stubs += Offset(x, size.height - stub)
                        else if (v <= 0) pastStubs += Offset(x, size.height - stub)
                        else {
                            val bh = maxOf(stub, v.toFloat() / max * (size.height - 4.dp.toPx()))
                            bars += Offset(x, size.height - bh) to Size(bw, bh)
                        }
                    }
                    val dim = OrbitPalette.Ink.copy(alpha = 0.12f)
                    val pastDim = OrbitPalette.Ink.copy(alpha = 0.22f)
                    val stubR = CornerRadius(stub / 2)
                    val r = CornerRadius(4.dp.toPx())
                    onDrawBehind {
                        stubs.forEach { drawRoundRect(dim, it, Size(bw, stub), stubR) }
                        pastStubs.forEach { drawRoundRect(pastDim, it, Size(bw, stub), stubR) }
                        // The gradient runs over each bar, top to bottom, as an SVG objectBoundingBox gradient does.
                        bars.forEach { (o, sz) -> translate(o.x, o.y) { drawRoundRect(brush, Offset.Zero, sz, r) } }
                    }
                },
            )
        }
        if (showAxis) {
            ChartAxis(
                listOf(0f to "00", 0.25f to "06", 0.5f to "12", 0.75f to "18", 1f to "24"),
                Modifier.fillMaxWidth().padding(top = 8.dp),
            )
        }
    }
}
