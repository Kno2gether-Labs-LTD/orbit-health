package dev.avijit.orbithealth.ui.sky

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.CacheDrawScope
import androidx.compose.ui.draw.DrawResult
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.IntSize
import kotlin.math.ceil
import kotlin.math.max

/**
 * Forces the static API 31–32 fallback on any device (debug gallery `--ez fallback true`), to check that the
 * fallback looks intentional without an old device.
 */
val LocalSkyForceFallback = staticCompositionLocalOf { false }

/** The shader renders into an offscreen layer at this fraction of the sky's pixel size, then scales up bilinearly. */
const val SKY_RENDER_SCALE = 0.5f

/**
 * A living sky filling [modifier]'s bounds (clip it to the card shape; see [SkySurface]).
 *
 * - [param]: the readiness fraction 0..1 for [SkyKind.FIRST_LIGHT] (the sun's height: 0.5 sits on the horizon),
 *   beats per minute for [SkyKind.PULSE] (≤ 0 → 60), stress 0..1 for [SkyKind.CALM] (warms the amber). Ignored otherwise.
 * - [intensity]: scales blob and noise alpha (1 = tokens).
 * - [horizon]: First light only, fraction of the height (hero 0.668, Welcome [SkyDefaults.WelcomeHorizon]).
 * - [stars]: star count; null = about one per 4,400 dp² on Night and none on First light (Welcome passes 22, Lock 60).
 * - [animate]: false holds the current frame (pager neighbours: keep to ≤ 2 animated skies per screen).
 *
 * API 33+: an AGSL [RuntimeShader] at half resolution in a [GraphicsLayer], animated by a [SkyClock] at ≤ 30 fps
 * that pauses off-screen and below STARTED and freezes under "Remove animations". API 31–32 (or [LocalSkyForceFallback]):
 * the same fill, blobs and sun drawn with Compose brushes at t = 0, without noise.
 */
@Composable
fun Sky(
    kind: SkyKind,
    modifier: Modifier = Modifier,
    intensity: Float = 1f,
    param: Float = 0f,
    horizon: Float = SkyDefaults.HeroHorizon,
    stars: Int? = null,
    starSeed: Int = 11,
    animate: Boolean = true,
) {
    val spec = remember(kind, horizon, if (kind == SkyKind.CALM) param else 0f) {
        skySpec(kind, horizon = horizon, stress = if (kind == SkyKind.CALM) param else 0f)
    }
    // A shader failure anywhere in the process (read as state) recomposes every sky onto the fallback.
    val fallback = Build.VERSION.SDK_INT < 33 || LocalSkyForceFallback.current || LocalInspectionMode.current ||
        SkyShaderHealth.Shared.failed
    val starMaxY = if (kind == SkyKind.FIRST_LIGHT) StarTokens.FIRST_LIGHT_MAX_Y else StarTokens.NIGHT_MAX_Y
    if (fallback) {
        Spacer(modifier.drawWithCache { buildFallbackDraw(spec, intensity, param, stars, starSeed, starMaxY) })
    } else {
        ShaderSky(spec, modifier, intensity, param, stars, starSeed, starMaxY, animate)
    }
}

private fun CacheDrawScope.buildFallbackDraw(
    spec: SkySpec, intensity: Float, param: Float, stars: Int?, starSeed: Int, starMaxY: Float,
): DrawResult {
    val w = size.width / density
    val h = size.height / density
    val frame = resolveFrame(spec, w, h, SkyClock.STATIC_TIME, param, intensity)
    val field = starsFor(spec, w, h, stars, starSeed, starMaxY)
    val glows = StarGlows(density)
    return onDrawBehind {
        drawSkyFallback(spec, frame)
        drawStars(field, SkyClock.STATIC_TIME, spec.scrim, frame.heightDp, glows)
    }
}

/** A box clipped to [shape] with a [Sky] behind [content] (content sits above the sky layers). */
@Composable
fun SkySurface(
    kind: SkyKind,
    modifier: Modifier = Modifier,
    shape: Shape = RectangleShape,
    param: Float = 0f,
    horizon: Float = SkyDefaults.HeroHorizon,
    stars: Int? = null,
    starSeed: Int = 11,
    content: @Composable BoxScope.() -> Unit = {},
) {
    Box(modifier.clip(shape)) {
        Sky(kind, Modifier.matchParentSize(), param = param, horizon = horizon, stars = stars, starSeed = starSeed)
        content()
    }
}

private fun starsFor(spec: SkySpec, w: Float, h: Float, stars: Int?, seed: Int, maxY: Float): List<Star> {
    val count = stars ?: if (spec.hasStars) defaultStarCount(w, h) else 0
    return starField(w, h, count, seed, maxY)
}

@Composable
private fun ShaderSky(
    spec: SkySpec, modifier: Modifier, intensity: Float, param: Float, stars: Int?, starSeed: Int, starMaxY: Float,
    animate: Boolean,
) {
    // Guarded by the caller (API 33+); the lint check can't see through the composable boundary.
    if (Build.VERSION.SDK_INT < 33) return
    val clock = rememberSkyClock()
    SideEffect { clock.active = animate }
    val layer = rememberGraphicsLayer()
    // Compiling can throw (SkSL errors on an older platform Skia): the guard turns that into the fallback.
    val guard = remember { SkyShaderGuard(AgslProgramFactory) }
    if (!guard.usable) {
        Spacer(modifier.drawWithCache { buildFallbackDraw(spec, intensity, param, stars, starSeed, starMaxY) })
        return
    }
    Spacer(
        modifier
            .onGloballyPositioned { coords ->
                val b = coords.boundsInWindow()
                clock.visible = coords.isAttached && b.width > 0f && b.height > 0f
            }
            .drawWithCache { buildShaderDraw(guard, layer, clock, spec, intensity, param, stars, starSeed, starMaxY) },
    )
}

@RequiresApi(33)
private fun CacheDrawScope.buildShaderDraw(
    guard: SkyShaderGuard, layer: GraphicsLayer, clock: SkyClock, spec: SkySpec,
    intensity: Float, param: Float, stars: Int?, starSeed: Int, starMaxY: Float,
): DrawResult {
    val w = size.width / density
    val h = size.height / density
    val lw = max(1, ceil(size.width * SKY_RENDER_SCALE).toInt())
    val lh = max(1, ceil(size.height * SKY_RENDER_SCALE).toInt())
    val pxPerDp = density * lw / size.width.coerceAtLeast(1f)
    if (!guard.run("bindStatic") { it.bindStatic(spec, w, h, pxPerDp) }) {
        return buildFallbackDraw(spec, intensity, param, stars, starSeed, starMaxY)
    }
    val brush = guard.program!!.brush
    val field = starsFor(spec, w, h, stars, starSeed, starMaxY)
    val glows = StarGlows(density)
    val staticFrame = resolveFrame(spec, w, h, SkyClock.STATIC_TIME, param, intensity)
    layer.compositingStrategy = CompositingStrategy.Offscreen
    return onDrawBehind {
        if (size.width < 1f || size.height < 1f) return@onDrawBehind
        val t = clock.time // snapshot read: each clock tick redraws (no recomposition)
        val frame = resolveFrame(spec, w, h, t, param, intensity)
        if (!guard.run("bindFrame") { it.bindFrame(spec, frame) }) {
            // This frame draws the fallback; the health flag recomposes the sky onto it for good.
            drawSkyFallback(spec, staticFrame)
            drawStars(field, SkyClock.STATIC_TIME, spec.scrim, h, glows)
            return@onDrawBehind
        }
        layer.record(IntSize(lw, lh)) { drawRect(brush) }
        scale(size.width / lw, size.height / lh, pivot = Offset.Zero) { drawLayer(layer) }
        drawStars(field, t, spec.scrim, h, glows)
    }
}

@RequiresApi(33)
private object AgslProgramFactory : SkyProgramFactory {
    override fun create(): SkyShaderProgram = AgslSkyProgram()
}

/** The [RuntimeShader] and its uniform plumbing. */
@RequiresApi(33)
private class AgslSkyProgram : SkyShaderProgram {
    val shader = RuntimeShader(SKY_SHADER)
    override val brush = ShaderBrush(shader)
    private val stopPos = FloatArray(10)
    private val stopCol = FloatArray(40)
    private val geo = FloatArray(20)
    private val c0 = FloatArray(20)
    private val cm = FloatArray(20)
    private val meta = FloatArray(20)

    override fun bindStatic(spec: SkySpec, w: Float, h: Float, pxPerDp: Float) {
        shader.setFloatUniform("uPxPerDp", pxPerDp)
        shader.setFloatUniform("uSkyDp", w, h)
        // Pad with the last stop so the fixed 10-stop loop is a no-op past the real ones.
        for (i in 0 until 10) {
            val s = spec.stops[minOf(i, spec.stops.lastIndex)]
            stopPos[i] = if (i <= spec.stops.lastIndex) s.position else 2f + i
            stopCol[i * 4] = s.color.red; stopCol[i * 4 + 1] = s.color.green; stopCol[i * 4 + 2] = s.color.blue; stopCol[i * 4 + 3] = 1f
        }
        shader.setFloatUniform("uStopPos", stopPos)
        shader.setFloatUniform("uStopCol", stopCol)

        val sh = spec.shimmer
        shader.setFloatUniform("uShimCol", sh?.color.rgba())
        val mask = spec.noiseMask
        if (mask != null) shader.setFloatUniform("uMask", mask.zeroTop * h, mask.fullTop * h, mask.fullBottom * h, mask.zeroBottom * h)
        else shader.setFloatUniform("uMask", 0f, 0f, 0f, -1f)

        val sun = spec.sun
        shader.setFloatUniform("uSunCore", sun?.core.rgba())
        shader.setFloatUniform("uSunMid", sun?.mid.rgba())
        shader.setFloatUniform("uSunHalo", sun?.halo.rgba())
        shader.setFloatUniform("uHzLine", sun?.horizonLine.rgba())
        shader.setFloatUniform("uReflect", sun?.reflect.rgba())
        shader.setFloatUniform("uSunStops", sun?.coreStop ?: 0.38f, sun?.midStop ?: 0.66f, 0f, 0f)
        shader.setFloatUniform("uReflectGeo", (sun?.reflectWidth ?: 70f) / 2f, sun?.reflectHeight ?: 96f, 0f, 0f)

        val scrim = spec.scrim
        if (scrim != null) shader.setFloatUniform("uScrim", 1f, scrim.from * h, h, scrim.maxAlpha)
        else shader.setFloatUniform("uScrim", 0f, 0f, 1f, 0f)
        shader.setFloatUniform("uScrimCol", scrim?.color.rgba())
    }

    override fun bindFrame(spec: SkySpec, frame: SkyFrame) {
        val f = frame
        for (i in 0 until 5) {
            val b = f.blobs.getOrNull(i)
            if (b == null) {
                geo.put(i, -1e5f, -1e5f, 1f, 1f); c0.put(i, 0f, 0f, 0f, 0f); cm.put(i, 0f, 0f, 0f, 0f); meta.put(i, 2f, 0f, 0f, 0f)
                continue
            }
            geo.put(i, b.cx, b.cy, b.rx.coerceAtLeast(0.01f), b.ry.coerceAtLeast(0.01f))
            c0.putPremul(i, b.color)
            cm.putPremul(i, b.midColor ?: Color.Transparent)
            meta.put(i, b.midStop ?: 2f, if (b.afterNoise) 1f else 0f, 0f, 0f)
        }
        shader.setFloatUniform("uBlobGeo", geo)
        shader.setFloatUniform("uBlobC0", c0)
        shader.setFloatUniform("uBlobCm", cm)
        shader.setFloatUniform("uBlobMeta", meta)
        shader.setFloatUniform("uNoise", f.noiseX, f.noiseY, f.noiseOpacity, 0f)
        if (spec.shimmer != null) shader.setFloatUniform("uShim", 1f, f.shimmerX, f.shimmerWidth.coerceAtLeast(1f), 0f)
        else shader.setFloatUniform("uShim", 0f, 0f, 1f, 0f)
        val sun = spec.sun
        if (sun != null) {
            shader.setFloatUniform("uSun", f.sunX, f.sunY, f.horizonY, 1f)
            shader.setFloatUniform("uSunGeo", sun.radius, sun.radius + sun.haloSpread, sun.haloBlur, f.reflectAlpha)
        } else {
            shader.setFloatUniform("uSun", 0f, 0f, 0f, 0f)
            shader.setFloatUniform("uSunGeo", 1f, 1f, 1f, 0f)
        }
    }

    private fun FloatArray.put(i: Int, a: Float, b: Float, c: Float, d: Float) {
        this[i * 4] = a; this[i * 4 + 1] = b; this[i * 4 + 2] = c; this[i * 4 + 3] = d
    }

    private fun FloatArray.putPremul(i: Int, c: Color) = put(i, c.red * c.alpha, c.green * c.alpha, c.blue * c.alpha, c.alpha)

    /** Straight-alpha RGBA (the shader premultiplies where it needs to). */
    private fun Color?.rgba(): FloatArray = if (this == null) FloatArray(4) else floatArrayOf(red, green, blue, alpha)
}

/**
 * One glow brush per star size (the field has two or three sizes), built once per draw cache: a radial gradient
 * centred on the origin, drawn translated to each star with the twinkle as paint alpha, so no brush or shader is
 * allocated per frame.
 */
internal class StarGlows(private val density: Float) {
    private val brushes = HashMap<Float, Pair<Brush, Float>>(4)

    /** (brush, radius px) for a star of [size] dp: CSS box-shadow 0 0 4px 1px, a soft 1dp-spread glow. */
    fun forSize(size: Float): Pair<Brush, Float> = brushes.getOrPut(size) {
        val r = (size / 2f + 1f + StarTokens.GLOW_BLUR / 2f) * density
        Brush.radialGradient(listOf(StarTokens.GLOW, Color.Transparent), center = Offset.Zero, radius = r) to r
    }
}

/**
 * Stars at full resolution over the sky. Night's bottom scrim is applied to their alpha so they sit under it exactly
 * as in the mockup's layer order (stars, then scrim).
 */
internal fun DrawScope.drawStars(stars: List<Star>, t: Float, scrim: Scrim?, heightDp: Float, glows: StarGlows) {
    if (stars.isEmpty()) return
    val px = density
    stars.forEach { s ->
        var a = s.alphaAt(t)
        if (scrim != null) {
            val k = ((s.y / heightDp - scrim.from) / (1f - scrim.from)).coerceIn(0f, 1f)
            a *= 1f - k * scrim.maxAlpha
        }
        val a1 = a.coerceIn(0f, 1f)
        val x = s.x * px
        val y = s.y * px
        if (s.size >= 2f) {
            val (brush, r) = glows.forSize(s.size)
            translate(x, y) { drawCircle(brush, radius = r, center = Offset.Zero, alpha = a1) }
        }
        drawCircle(StarTokens.COLOR, radius = s.size / 2f * px, center = Offset(x, y), alpha = a1)
    }
}
