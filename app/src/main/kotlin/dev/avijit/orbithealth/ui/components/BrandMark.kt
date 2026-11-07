package dev.avijit.orbithealth.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp

/* Sizes (tokens §1): Welcome 112, Lock 104, Ask 92, Me ring card 68 (no backglow). */

private val RingA = Color(0xFF9A7BFF)
private val RingB = Color(0xFFB67BEA)
private val RingC = Color(0xFFF07BC2)
private val PlanetFill = Color(0xFFFFE4F2)
private val PlanetStroke = Color(0xFFF27BC2)
private val Halo = Color(0xFFFF9BD0)

/**
 * The Living Skies logo (tokens §1, the mockup's `logo()` svg) in a 100-unit box: a gradient ring (centre 48,54, r 29,
 * stroke 15; #9A7BFF → #B67BEA 55% → #F07BC2 top-left to bottom-right) with a planet at (75.5, 25.5), r 8.5, filled
 * #FFE4F2 with a 2.6 #F27BC2 stroke over a pink halo of r 16. [glow] adds the violet backglow (r 52, 35% from 55%).
 * The halo and backglow reach past the box, as the svg's overflow does. [planetAngle] (radians, 0 = the resting
 * 1–2 o'clock spot) turns the planet about the ring centre, for the unlock orbit.
 */
@Composable
fun BrandMark(size: Dp, modifier: Modifier = Modifier, glow: Boolean = true, planetAngle: () -> Float = { 0f }) {
    Canvas(modifier.size(size).clearAndSetSemantics { }) { drawBrandMark(glow, planetAngle()) }
}

internal fun DrawScope.drawBrandMark(glow: Boolean, planetAngle: Float = 0f) {
    val u = size.minDimension / 100f
    fun p(x: Float, y: Float) = Offset(x * u, y * u)
    val ring = p(48f, 54f)
    if (glow) {
        drawCircle(
            Brush.radialGradient(0f to RingA.copy(alpha = 0.35f), 0.55f to RingA.copy(alpha = 0.35f), 1f to RingA.copy(alpha = 0f), center = ring, radius = 52f * u),
            radius = 52f * u, center = ring,
        )
    }
    drawCircle(
        Brush.linearGradient(0f to RingA, 0.55f to RingB, 1f to RingC, start = p(19f, 25f), end = p(77f, 83f)),
        radius = 29f * u, center = ring, style = Stroke(15f * u),
    )
    val rest = p(75.5f, 25.5f) - ring
    val c = kotlin.math.cos(planetAngle)
    val s = kotlin.math.sin(planetAngle)
    val planet = ring + Offset(rest.x * c - rest.y * s, rest.x * s + rest.y * c)
    drawCircle(Brush.radialGradient(0f to Halo.copy(alpha = 0.75f), 1f to Halo.copy(alpha = 0f), center = planet, radius = 16f * u), radius = 16f * u, center = planet)
    drawCircle(PlanetFill, radius = 8.5f * u, center = planet)
    drawCircle(PlanetStroke, radius = 8.5f * u, center = planet, style = Stroke(2.6f * u))
}
