package dev.avijit.orbithealth.ui.sky

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.ui.graphics.Color
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The six living skies (tokens.md §7). Each metric owns one: Readiness gets [FIRST_LIGHT], Sleep [NIGHT], Activity
 * [EMBER], Heart [PULSE], Stress [CALM], SpO₂ [TIDE].
 */
enum class SkyKind { FIRST_LIGHT, NIGHT, EMBER, PULSE, CALM, TIDE }

object SkyDefaults {
    /** First light horizon on the Today hero (fraction of the sky height). */
    const val HeroHorizon = 0.668f
    /** First light horizon on the full-screen Welcome sky. */
    const val WelcomeHorizon = 0.62f
}

/** A coordinate in dp that scales with the sky: `frac * extent + offset` (e.g. "W − 70" is `SkyCoord(1f, -70f)`). */
data class SkyCoord(val frac: Float, val offset: Float) {
    fun at(extent: Float): Float = frac * extent + offset
}

data class GradientStop(val position: Float, val color: Color)

enum class DriftKind { A, B }

/** Alternating ease-in-out drift of period [periodS] (one leg); [reversed] is CSS `alternate-reverse`. */
data class Drift(val kind: DriftKind, val periodS: Float, val reversed: Boolean = false)

/**
 * An elliptical radial blob [width] × [height] dp centred at ([cx], [cy]). Its alpha falls from [color] at the centre
 * to 0 at the ellipse edge (CSS `closest-side`), optionally through [midColor] at [midStop].
 */
data class SkyBlob(
    val width: Float,
    val height: Float,
    val cx: SkyCoord,
    val cy: SkyCoord,
    val color: Color,
    val drift: Drift?,
    val midStop: Float? = null,
    val midColor: Color? = null,
)

/** fbm noise, soft-light blended white at [opacity]; the pattern flows ([flowX], [flowY]) dp every [periodS]. */
data class SkyNoise(val opacity: Float, val periodS: Float, val flowX: Float, val flowY: Float)

/** First light masks the noise to the sky: 0 at [zeroTop], 1 from [fullTop] to [fullBottom], 0 at [zeroBottom]. */
data class NoiseMask(val zeroTop: Float, val fullTop: Float, val fullBottom: Float, val zeroBottom: Float)

/** Night's bottom scrim: [color] from alpha 0 at [from] (fraction of H) to [maxAlpha] at the bottom. */
data class Scrim(val from: Float, val color: Color, val maxAlpha: Float)

/** Pulse: blob [blobIndex] scales to [peakScale] at [peakAt] of each beat, then eases back. */
data class Beat(val blobIndex: Int, val peakScale: Float, val peakAt: Float, val defaultBpm: Float)

/** Tide: a [angleDeg] linear band in an image [widthFactor] × W wide, sliding from [from] to [to] (CSS background-position). */
data class Shimmer(val angleDeg: Float, val color: Color, val widthFactor: Float, val from: Float, val to: Float, val periodS: Float)

/** First light's sun group. All sizes in dp. */
data class SunSpec(
    val horizon: Float,
    val radius: Float = 19f,
    val risePerScore: Float = 48f,
    val core: Color = Color(0xFFFFF3E4),
    val coreStop: Float = 0.38f,
    val mid: Color = Color(0xFFFFD9B0),
    val midStop: Float = 0.66f,
    val halo: Color = Color(0xFFFFCDA0).copy(alpha = 0.40f),
    val haloBlur: Float = 18f,
    val haloSpread: Float = 4f,
    val glowWidth: Float = 400f,
    val glowHeight: Float = 130f,
    val glowCenter: Color = Color(0xFFFFC496).copy(alpha = 0.55f),
    val glowMid: Color = Color(0xFFFFA078).copy(alpha = 0.16f),
    val breathPeriodS: Float = 12f,
    val breathScale: Float = 1.06f,
    val breathAlphaMin: Float = 0.92f,
    val horizonLine: Color = Color(0xFFFFD7B5).copy(alpha = 0.65f),
    val reflectWidth: Float = 70f,
    val reflectHeight: Float = 96f,
    val reflect: Color = Color(0xFFFFC8A0).copy(alpha = 0.42f),
    val glintPeriodS: Float = 7f,
    val glintMin: Float = 0.55f,
    val glintMax: Float = 0.9f,
)

data class SkySpec(
    val kind: SkyKind,
    val stops: List<GradientStop>,
    val blobs: List<SkyBlob>,
    val noise: SkyNoise,
    val noiseMask: NoiseMask? = null,
    val sun: SunSpec? = null,
    val scrim: Scrim? = null,
    val beat: Beat? = null,
    val shimmer: Shimmer? = null,
    val hasStars: Boolean = false,
)

/** Night star field (tokens §7 Night; Welcome reuses it over First light in the top 35%). */
object StarTokens {
    /** About one star per this many dp² by default (342 × 392 hero → 30). */
    const val DP2_PER_STAR = 4400f
    const val NIGHT_MAX_Y = 0.85f
    const val FIRST_LIGHT_MAX_Y = 0.35f
    val COLOR = Color(0xFFFFF8EE)
    const val ALPHA_MIN = 0.25f
    const val ALPHA_MAX = 0.85f
    const val TWINKLE_MIN = 0.18f
    const val TWINKLE_MAX = 0.95f
    const val TWINKLE_PERIOD_MIN_S = 3f
    const val TWINKLE_PERIOD_MAX_S = 7f
    /** 2dp stars get a 4dp glow. */
    val GLOW = Color(0xFFFFF0DC).copy(alpha = 0.5f)
    const val GLOW_BLUR = 4f
}

private fun c(argb: Long, alpha: Float = 1f) = Color(argb).copy(alpha = alpha)
private fun stops(vararg s: Pair<Float, Long>) = s.map { GradientStop(it.first, Color(it.second)) }

/**
 * The token parameters of [kind]. [horizon] applies to First light (hero 66.8%, Welcome 62%); [stress] (0..1) warms
 * the Calm sky's amber blob.
 */
fun skySpec(kind: SkyKind, horizon: Float = SkyDefaults.HeroHorizon, stress: Float = 0f): SkySpec = when (kind) {
    SkyKind.FIRST_LIGHT -> {
        val hz = horizon
        SkySpec(
            kind,
            stops(
                0f to 0xFF0A1030, hz - 0.37f to 0xFF122752, hz - 0.17f to 0xFF183F60, hz - 0.09f to 0xFF1F5366,
                hz - 0.033f to 0xFFB08680, hz to 0xFFF0AE8C, hz + 0.002f to 0xFF272C50, hz + 0.12f to 0xFF181D3E,
                1f to 0xFF0F1430,
            ),
            blobs = listOf(SkyBlob(340f, 260f, SkyCoord(1f, -50f), SkyCoord(0f, 90f), c(0xFF2FB8A6, 0.30f), Drift(DriftKind.A, 28f))),
            noise = SkyNoise(0.32f, 80f, 400f, 0f),
            noiseMask = NoiseMask(0.10f, 0.40f, hz - 0.01f, hz),
            sun = SunSpec(horizon = hz),
        )
    }
    SkyKind.NIGHT -> SkySpec(
        kind,
        stops(0f to 0xFF0B0A2A, 0.52f to 0xFF161452, 1f to 0xFF241E74),
        blobs = listOf(
            SkyBlob(320f, 280f, SkyCoord(1f, -70f), SkyCoord(0f, 80f), c(0xFF9C86FF, 0.50f), Drift(DriftKind.A, 26f)),
            SkyBlob(380f, 320f, SkyCoord(0f, 50f), SkyCoord(1f, -30f), c(0xFF4B3FD0, 0.62f), Drift(DriftKind.B, 30f)),
            SkyBlob(240f, 200f, SkyCoord(0.38f, 120f), SkyCoord(0.40f, 100f), c(0xFFB04BD8, 0.22f), Drift(DriftKind.A, 22f, reversed = true)),
        ),
        noise = SkyNoise(0.42f, 90f, 400f, 0f),
        scrim = Scrim(0.5f, Color(0xFF0B0A2A), 0.5f),
        hasStars = true,
    )
    SkyKind.EMBER -> SkySpec(
        kind,
        stops(0f to 0xFF170B22, 0.48f to 0xFF3A1230, 1f to 0xFF6E2230),
        blobs = listOf(
            SkyBlob(260f, 200f, SkyCoord(0.30f, 130f), SkyCoord(0.46f, 100f), c(0xFFFF5E78, 0.28f), Drift(DriftKind.B, 18f)),
            SkyBlob(440f, 300f, SkyCoord(0f, 160f), SkyCoord(1f, 65f), c(0xFFFF7759, 0.70f), Drift(DriftKind.A, 20f)),
            SkyBlob(320f, 250f, SkyCoord(1f, -40f), SkyCoord(1f, 35f), c(0xFFFFC65C, 0.50f), Drift(DriftKind.B, 24f)),
        ),
        noise = SkyNoise(0.48f, 34f, 60f, -400f),
    )
    SkyKind.PULSE -> SkySpec(
        kind,
        stops(0f to 0xFF1A0B25, 0.60f to 0xFF2A0D35, 1f to 0xFF331040),
        blobs = listOf(
            SkyBlob(300f, 260f, SkyCoord(0f, 30f), SkyCoord(0f, 40f), c(0xFFB04BD8, 0.40f), Drift(DriftKind.A, 16f)),
            SkyBlob(
                460f, 380f, SkyCoord(0.5f, 0f), SkyCoord(0.72f, 0f), c(0xFFFF5E8E, 0.55f), drift = null,
                midStop = 0.55f, midColor = c(0xFFFF5E8E, 0.16f),
            ),
        ),
        noise = SkyNoise(0.30f, 70f, 400f, 0f),
        beat = Beat(blobIndex = 1, peakScale = 1.035f, peakAt = 0.18f, defaultBpm = 60f),
    )
    SkyKind.CALM -> SkySpec(
        kind,
        stops(0f to 0xFF0A1A2A, 1f to 0xFF0F2B36),
        blobs = listOf(
            SkyBlob(380f, 300f, SkyCoord(0f, 70f), SkyCoord(1f, -30f), c(0xFF3FC1B0, 0.32f), Drift(DriftKind.A, 24f)),
            SkyBlob(
                300f, 240f, SkyCoord(1f, -50f), SkyCoord(0f, 30f),
                c(0xFFFFC478, 0.06f + 0.40f * stress.coerceIn(0f, 1f)), Drift(DriftKind.B, 24f),
            ),
        ),
        noise = SkyNoise(0.30f, 80f, 400f, 0f),
    )
    SkyKind.TIDE -> SkySpec(
        kind,
        stops(0f to 0xFF0A1830, 1f to 0xFF0D2A40),
        blobs = listOf(SkyBlob(360f, 260f, SkyCoord(1f, -60f), SkyCoord(1f, -20f), c(0xFF4FC7DB, 0.40f), Drift(DriftKind.A, 22f))),
        noise = SkyNoise(0.26f, 60f, 400f, 0f),
        shimmer = Shimmer(110f, c(0xFFA9EEF2, 0.10f), 2.6f, 1.2f, -1.2f, 18f),
    )
}

// ---------------------------------------------------------------------------------------------------------------
// Motion maths (CSS keyframes ported to closed form).
// ---------------------------------------------------------------------------------------------------------------

private val EaseInOut = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)
private val EaseOut = CubicBezierEasing(0f, 0f, 0.58f, 1f)

private fun fract(x: Float): Float = x - floor(x)
private fun mix(a: Float, b: Float, k: Float) = a + (b - a) * k

/** Drift progress: k = 0.5 − 0.5·cos(2π·t / 2T) (tokens §7), mirrored for `alternate-reverse`. */
fun driftK(t: Float, periodS: Float, reversed: Boolean): Float {
    val k = (0.5 - 0.5 * cos(2.0 * PI * t / (2.0 * periodS))).toFloat()
    return if (reversed) 1f - k else k
}

data class DriftTransform(val dx: Float, val dy: Float, val scale: Float)

/** Offset (dp) and scale of a [width] × [height] blob at drift progress [k]. */
fun driftTransform(kind: DriftKind, k: Float, width: Float, height: Float): DriftTransform = when (kind) {
    DriftKind.A -> DriftTransform(mix(-0.05f, 0.05f, k) * width, mix(0.03f, -0.03f, k) * height, mix(1f, 1.06f, k))
    DriftKind.B -> DriftTransform(mix(0.04f, -0.04f, k) * width, mix(-0.02f, 0.04f, k) * height, mix(1.04f, 0.98f, k))
}

/** A 0 → 1 → 0 keyframe cycle with ease-in-out on each half (CSS `0%,100% {a} 50% {b}`). */
fun pingPong(t: Float, periodS: Float, phaseS: Float = 0f): Float {
    val p = fract((t + phaseS) / periodS)
    return if (p < 0.5f) EaseInOut.transform(p * 2f) else EaseInOut.transform((1f - p) * 2f)
}

/** Pulse rose scale at [t]: 1 → 1.035 at 18% of the beat (ease-out), then eases back. [bpm] ≤ 0 means unknown (60). */
fun pulseScale(t: Float, bpm: Float, beat: Beat = PulseBeat): Float {
    val rate = if (bpm > 0f) bpm else beat.defaultBpm
    val period = 60f / rate
    val p = fract(t / period)
    val amp = beat.peakScale - 1f
    return if (p < beat.peakAt) 1f + amp * EaseOut.transform(p / beat.peakAt)
    else beat.peakScale - amp * EaseOut.transform((p - beat.peakAt) / (1f - beat.peakAt))
}

private val PulseBeat = Beat(1, 1.035f, 0.18f, 60f)

// ---------------------------------------------------------------------------------------------------------------
// Per-frame resolution: everything time-dependent is computed here on the CPU, so the shader stays a pure function
// of its uniforms and the fallback draws the same geometry.
// ---------------------------------------------------------------------------------------------------------------

/** A blob placed for one frame: centre and radii in dp, colours with straight alpha. */
data class ResolvedBlob(
    val cx: Float, val cy: Float, val rx: Float, val ry: Float,
    val color: Color, val midStop: Float?, val midColor: Color?,
    /** Drawn after the noise layer (First light's sun glow). */
    val afterNoise: Boolean = false,
)

data class SkyFrame(
    val widthDp: Float,
    val heightDp: Float,
    val blobs: List<ResolvedBlob>,
    /** Noise flow offset in dp, wrapped into [0, 400): the noise tiles every 400 dp. */
    val noiseX: Float,
    val noiseY: Float,
    val noiseOpacity: Float,
    val horizonY: Float = 0f,
    val sunX: Float = 0f,
    val sunY: Float = 0f,
    val reflectAlpha: Float = 0f,
    /** Shimmer image offset in dp (CSS background-position resolved) and image width. */
    val shimmerX: Float = 0f,
    val shimmerWidth: Float = 0f,
)

const val NOISE_TILE_DP = 400f

private fun wrap(v: Float, period: Float): Float = v - period * floor(v / period)

/**
 * Resolves [spec] on a [w] × [h] dp sky at time [t] seconds. [param] is the readiness fraction (0..1, the sun's
 * height) for First light and the heart rate in bpm for Pulse. [intensity] scales blob and noise alpha.
 */
fun resolveFrame(spec: SkySpec, w: Float, h: Float, t: Float, param: Float, intensity: Float = 1f): SkyFrame {
    val blobs = spec.blobs.mapIndexed { i, b ->
        val d = b.drift?.let { driftTransform(it.kind, driftK(t, it.periodS, it.reversed), b.width, b.height) }
            ?: DriftTransform(0f, 0f, 1f)
        val beatScale = if (spec.beat?.blobIndex == i) pulseScale(t, param, spec.beat) else 1f
        val s = d.scale * beatScale
        ResolvedBlob(
            cx = b.cx.at(w) + d.dx, cy = b.cy.at(h) + d.dy, rx = b.width * s / 2f, ry = b.height * s / 2f,
            color = b.color.scaleAlpha(intensity), midStop = b.midStop, midColor = b.midColor?.scaleAlpha(intensity),
        )
    }.toMutableList()

    val n = spec.noise
    val cycles = t / n.periodS
    var frame = SkyFrame(
        widthDp = w, heightDp = h, blobs = blobs,
        noiseX = wrap(n.flowX * cycles, NOISE_TILE_DP), noiseY = wrap(n.flowY * cycles, NOISE_TILE_DP),
        noiseOpacity = n.opacity * intensity,
    )

    spec.sun?.let { sun ->
        val hzY = h * sun.horizon
        val sunY = hzY - (param.coerceIn(0f, 1f) - 0.5f) * sun.risePerScore
        val breath = pingPong(t, sun.breathPeriodS)
        val gs = mix(1f, sun.breathScale, breath)
        val ga = mix(sun.breathAlphaMin, 1f, breath)
        blobs += ResolvedBlob(
            cx = w / 2f, cy = sunY, rx = sun.glowWidth * gs / 2f, ry = sun.glowHeight * gs / 2f,
            color = sun.glowCenter.scaleAlpha(ga), midStop = 0.5f, midColor = sun.glowMid.scaleAlpha(ga), afterNoise = true,
        )
        frame = frame.copy(
            horizonY = hzY, sunX = w / 2f, sunY = sunY,
            reflectAlpha = mix(sun.glintMin, sun.glintMax, pingPong(t, sun.glintPeriodS)),
        )
    }
    spec.shimmer?.let { sh ->
        val p = fract(t / sh.periodS)
        val pct = mix(sh.from, sh.to, EaseInOut.transform(p))
        val imageW = sh.widthFactor * w
        frame = frame.copy(shimmerX = (w - imageW) * pct, shimmerWidth = imageW)
    }
    return frame
}

private fun Color.scaleAlpha(k: Float) = copy(alpha = (alpha * k).coerceIn(0f, 1f))

// ---------------------------------------------------------------------------------------------------------------
// Stars from a hashed grid.
// ---------------------------------------------------------------------------------------------------------------

/** One star: position and size in dp, base alpha; twinkling stars swing 0.18 ↔ 0.95 over [periodS]. */
data class Star(
    val x: Float, val y: Float, val size: Float, val alpha: Float,
    val twinkle: Boolean, val periodS: Float, val phaseS: Float,
) {
    fun alphaAt(t: Float): Float =
        if (twinkle) mix(StarTokens.TWINKLE_MIN, StarTokens.TWINKLE_MAX, pingPong(t, periodS, phaseS)) else alpha
}

fun defaultStarCount(w: Float, h: Float): Int = (w * h / StarTokens.DP2_PER_STAR).roundToInt()

/** Integer hash (lowbias32) → [0, 1). */
private fun hash01(vararg k: Int): Float {
    var x = 0x9E3779B9.toInt()
    for (v in k) {
        x = x xor v
        x = x xor (x ushr 16); x *= 0x7FEB352D
        x = x xor (x ushr 15); x *= 0x846CA68B.toInt()
        x = x xor (x ushr 16)
    }
    return (x ushr 8) / 16777216f
}

/**
 * [count] stars in the top [maxY] of a [w] × [h] dp sky. The band is cut into a grid of about 2.5 cells per star and
 * the [count] cells with the lowest hash get one star each, jittered inside the cell: an even, stratified scatter that
 * is the same on every frame and every device. Sizes, alphas and twinkle follow the mockup's distribution (15% 2dp,
 * then half 1.5dp, the rest 1dp; every 4th star twinkles at 3–7 s).
 */
fun starField(w: Float, h: Float, count: Int, seed: Int, maxY: Float): List<Star> {
    if (count <= 0 || w <= 0f || h <= 0f) return emptyList()
    val bandH = h * maxY
    val cell = sqrt(w * bandH / (count * 2.5f)).coerceAtLeast(4f)
    val cols = (w / cell).toInt().coerceAtLeast(1)
    val rows = (bandH / cell).toInt().coerceAtLeast(1)
    val cw = w / cols
    val ch = bandH / rows
    val cells = (0 until cols * rows).sortedBy { hash01(seed, it, 0) }.take(count)
    return cells.mapIndexed { n, idx ->
        val i = idx % cols
        val j = idx / cols
        val r1 = hash01(seed, idx, 1)
        val r2 = hash01(seed, idx, 2)
        val size = if (r1 < 0.15f) 2f else if (r2 < 0.5f) 1.5f else 1f
        val margin = 1.5f
        val x = i * cw + margin + hash01(seed, idx, 3) * (cw - 2 * margin).coerceAtLeast(0f)
        val y = j * ch + margin + hash01(seed, idx, 4) * (ch - 2 * margin).coerceAtLeast(0f)
        val alpha = StarTokens.ALPHA_MIN + hash01(seed, idx, 5) * (StarTokens.ALPHA_MAX - StarTokens.ALPHA_MIN)
        val period = mix(StarTokens.TWINKLE_PERIOD_MIN_S, StarTokens.TWINKLE_PERIOD_MAX_S, hash01(seed, idx, 6))
        Star(x, y, size, alpha, twinkle = n % 4 == 0, periodS = period, phaseS = hash01(seed, idx, 7) * 6f)
    }
}
