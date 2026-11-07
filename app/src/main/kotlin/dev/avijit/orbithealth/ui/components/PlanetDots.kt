package dev.avijit.orbithealth.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.avijit.orbithealth.ui.theme.OrbitPalette

/**
 * Pager page dots in the planet style (tokens §5): inactive 6Ø ink 30%; the active page a 16Ø ring (1dp ink 35%)
 * holding an 8Ø warm planet (135° #FFE9D2 → #FFB48A) with an 8dp glow. 16dp tall, 10dp apart; selection crossfades.
 */
@Composable
fun PlanetDots(count: Int, selected: Int, modifier: Modifier = Modifier) {
    Row(
        modifier.height(16.dp).semantics { contentDescription = "Page ${selected + 1} of $count" },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(count) { i ->
            val p by animateFloatAsState(if (i == selected) 1f else 0f, tween(240), label = "dot")
            Canvas(Modifier.width((6f + 10f * p).dp).height(16.dp)) { drawPageDot(p) }
        }
    }
}

private fun DrawScope.drawPageDot(p: Float) {
    val c = center
    if (p < 1f) drawCircle(OrbitPalette.Ink.copy(alpha = 0.30f * (1f - p)), radius = 3.dp.toPx(), center = c)
    if (p <= 0f) return
    drawCircle(OrbitPalette.Ink.copy(alpha = 0.35f * p), radius = 7.5.dp.toPx(), center = c, style = Stroke(1.dp.toPx()))
    drawPlanet(c, 4.dp.toPx(), OrbitPalette.WarmPlanetA, OrbitPalette.WarmPlanetB, OrbitPalette.WarmPlanetGlow, glowBlur = 8.dp.toPx(), alpha = p)
}

/** A small planet: 135° two-colour gradient disc with a soft glow (page dots, nav dot, tab dot). */
internal fun DrawScope.drawPlanet(
    center: Offset, radius: Float,
    a: androidx.compose.ui.graphics.Color, b: androidx.compose.ui.graphics.Color, glow: androidx.compose.ui.graphics.Color,
    glowBlur: Float, glowSpread: Float = 0f, alpha: Float = 1f,
) {
    drawCircleGlow(center.x, center.y, radius, glow.copy(alpha = glow.alpha * alpha), glowBlur, glowSpread)
    val brush = Brush.linearGradient(
        listOf(a.copy(alpha = alpha), b.copy(alpha = alpha)),
        start = Offset(center.x - radius, center.y - radius), end = Offset(center.x + radius, center.y + radius),
    )
    drawCircle(brush, radius = radius, center = center)
}
