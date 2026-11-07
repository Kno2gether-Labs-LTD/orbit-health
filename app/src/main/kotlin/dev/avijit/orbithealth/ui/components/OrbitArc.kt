package dev.avijit.orbithealth.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.avijit.orbithealth.ui.theme.OrbitPalette
import kotlin.math.cos
import kotlin.math.sin

/** The orbit arc's maths (tokens §4): a 220° sweep starting at 160°, so the 140° gap is centred at the bottom. */
object OrbitArcGeometry {
    const val START_DEG = 160f
    const val SWEEP_DEG = 220f

    fun sweepFor(fraction: Float): Float = SWEEP_DEG * fraction.coerceIn(0f, 1f)

    /** Where the planet sits for [fraction] (clamped 0..1) on a circle of [radius] around [center] (y down). */
    fun planetPosition(fraction: Float, center: Offset, radius: Float): Offset {
        val a = Math.toRadians((START_DEG + sweepFor(fraction)).toDouble())
        return Offset(center.x + radius * cos(a).toFloat(), center.y + radius * sin(a).toFloat())
    }
}

/** Stroke, planet core diameter, planet halo radius and colours of one arc variant. */
@Immutable
data class OrbitArcStyle(
    val stroke: Dp,
    val planet: Dp,
    val halo: Dp,
    val track: Color,
    val progress: Color,
)

object OrbitArcDefaults {
    /** Diameter to the stroke centre: Today hero, Sleep/Activity detail, Me battery. */
    val HeroSize = 220.dp
    val DetailSize = 176.dp
    val BatterySize = 46.dp

    val Hero = OrbitArcStyle(3.dp, 10.dp, 15.dp, OrbitPalette.Ink.copy(alpha = 0.18f), OrbitPalette.Ink.copy(alpha = 0.85f))
    val Detail = Hero.copy(planet = 9.dp, halo = 13.5.dp)
    val Battery = OrbitArcStyle(3.dp, 6.dp, 9.dp, OrbitPalette.Ink.copy(alpha = 0.16f), OrbitPalette.Optimal)

    fun styleFor(size: Dp): OrbitArcStyle = when {
        size >= 200.dp -> Hero
        size >= 100.dp -> Detail
        else -> Battery
    }

    /** Arc reveal: settles in about 1.1 s. */
    val RevealSpring = spring<Float>(dampingRatio = 0.85f, stiffness = 60f)
}

/**
 * The arc's shown fraction: springs from 0 to [target] on first show (and between values after), or snaps when
 * [animateIn] is false or motion is off. With [start] false the reveal waits (held at 0) until it turns true. Screens that sync a numeral to the arc (`value.roundToInt()`) read this and
 * pass it to [OrbitArc] with `animateIn = false`. Null [target] reads 0.
 */
@Composable
fun rememberArcReveal(target: Float?, animateIn: Boolean = true, start: Boolean = true): State<Float> {
    val motion = rememberMotionEnabled() && animateIn
    val goal = target?.coerceIn(0f, 1f) ?: 0f
    val anim = remember { Animatable(if (motion) 0f else goal) }
    LaunchedEffect(goal, motion, start) {
        // [start] false holds the reveal at its current value (a pager page that is not current yet).
        if (!motion) anim.snapTo(goal) else if (start) anim.animateTo(goal, OrbitArcDefaults.RevealSpring)
    }
    return anim.asState()
}

/**
 * The signature open orbit arc with its glowing planet at the progress end. The layout box is the circle's bounds
 * ([size] = diameter to the stroke centre, as in tokens §4); the stroke and planet halo draw past it, unclipped.
 * [fraction] null is the empty state: the track alone, no planet.
 */
@Composable
fun OrbitArc(
    fraction: Float?,
    modifier: Modifier = Modifier,
    size: Dp = OrbitArcDefaults.HeroSize,
    animateIn: Boolean = true,
    style: OrbitArcStyle = OrbitArcDefaults.styleFor(size),
) {
    val shown = rememberArcReveal(fraction, animateIn)
    Canvas(modifier.size(size)) {
        val stroke = style.stroke.toPx()
        val r = this.size.minDimension / 2f
        val c = center
        val topLeft = Offset(c.x - r, c.y - r)
        val box = Size(r * 2, r * 2)
        drawArc(
            style.track, OrbitArcGeometry.START_DEG, OrbitArcGeometry.SWEEP_DEG, useCenter = false,
            topLeft = topLeft, size = box, style = Stroke(stroke, cap = StrokeCap.Round),
        )
        if (fraction == null) return@Canvas
        val f = shown.value
        val sweep = OrbitArcGeometry.sweepFor(f)
        if (sweep > 0.01f) {
            drawArc(
                style.progress, OrbitArcGeometry.START_DEG, sweep, useCenter = false,
                topLeft = topLeft, size = box, style = Stroke(stroke, cap = StrokeCap.Round),
            )
        }
        val p = OrbitArcGeometry.planetPosition(f, c, r)
        val halo = style.halo.toPx()
        drawCircle(
            Brush.radialGradient(listOf(OrbitPalette.PlanetHalo, OrbitPalette.PlanetHalo.copy(alpha = 0f)), center = p, radius = halo),
            radius = halo, center = p,
        )
        drawCircle(OrbitPalette.PlanetCore, radius = style.planet.toPx() / 2f, center = p)
    }
}
