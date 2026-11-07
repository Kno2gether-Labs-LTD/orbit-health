package dev.avijit.orbithealth.ui.charts

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import dev.avijit.orbithealth.ring.Sample
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import dev.avijit.orbithealth.ui.theme.OrbitPalette
import dev.avijit.orbithealth.ui.theme.OrbitTypography
import kotlin.math.floor

/** One dot: centre, colour, core radius, and halo radius (0 = no halo). */
private class Dot(val c: Offset, val color: Color, val r: Float, val halo: Float)

/**
 * Overnight SpO₂, the Living Skies Tide chart (tokens §5 SpO₂ dots): 90–100% over the height (lower if a reading is),
 * the healthy band 95–100% shaded #A9EEF2 @ 8% with radius 8, a dashed ink 18% line at 94% labelled "94%" at the right,
 * readings as 2.6 r #A9EEF2 @ 90% dots and those below 94% as 3.6 r #FFB48A. Up to 44 dots, edge to edge.
 */
@Composable
fun Spo2Dots(samples: List<Sample>, modifier: Modifier = Modifier, height: Dp = 80.dp) {
    LivingSpo2Dots(samples, modifier, height)
}

@Composable
private fun LivingSpo2Dots(samples: List<Sample>, modifier: Modifier, height: Dp) {
    val tm = rememberTextMeasurer()
    val pts = remember(samples) { ChartMath.bucketPoints(samples, 44) }
    val lo = remember(pts) { minOf(90f, floor((pts.minOfOrNull { it.second } ?: 90f) / 2f) * 2f) }
    val label = OrbitTypography.Caption.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp, color = OrbitPalette.Ink.copy(alpha = 0.66f))
    Spacer(
        modifier.then(Modifier.fillMaxWidth().height(height)).drawWithCache {
            fun y(v: Float) = ChartMath.scale(v, lo, 100f, size.height, 0f)
            val band = Size(size.width, y(95f))
            val line = y(DIP_LINE)
            val text = tm.measure("94%", label)
            val textAt = Offset(size.width - text.size.width, line - 4.dp.toPx() - text.firstBaseline)
            val dots = pts.map { (f, v) ->
                val low = v < DIP_LINE
                Triple(
                    Offset(if (pts.size == 1) size.width / 2 else f * size.width, y(v)),
                    if (low) OrbitPalette.ReadinessB else OrbitPalette.Spo2B.copy(alpha = 0.9f),
                    (if (low) 3.6f else 2.6f).dp.toPx(),
                )
            }
            val dash = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 4.dp.toPx()))
            onDrawBehind {
                drawRoundRect(OrbitPalette.Spo2B.copy(alpha = 0.08f), Offset.Zero, band, CornerRadius(8.dp.toPx()))
                drawLine(OrbitPalette.Ink.copy(alpha = 0.18f), Offset(0f, line), Offset(size.width, line), 1.dp.toPx(), pathEffect = dash)
                drawText(text, topLeft = textAt)
                dots.forEach { (c, color, r) -> drawCircle(color, r, c) }
            }
        },
    )
}

/** Readings below this are dips (drawn warm and larger). */
private const val DIP_LINE = 94f
