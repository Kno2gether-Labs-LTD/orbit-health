package dev.avijit.orbithealth.ui.sky

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every literal here is copied from .superpowers/brainstorm/living-skies/tokens.md §7. */
class SkyParamsTest {
    private fun c(argb: Long, a: Float = 1f) = Color(argb).copy(alpha = a)
    private val eps = 1e-4f

    private fun assertStops(expected: List<Pair<Float, Long>>, actual: List<GradientStop>) {
        assertEquals(expected.size, actual.size)
        expected.zip(actual).forEach { (e, a) ->
            assertEquals("stop position", e.first, a.position, eps)
            assertEquals("stop colour", Color(e.second), a.color)
        }
    }

    private fun assertBlob(b: SkyBlob, w: Float, h: Float, cx: SkyCoord, cy: SkyCoord, color: Color, drift: Drift?) {
        assertEquals(w, b.width, eps); assertEquals(h, b.height, eps)
        assertEquals(cx, b.cx); assertEquals(cy, b.cy)
        assertEquals(color, b.color)
        assertEquals(drift, b.drift)
    }

    @Test
    fun skyParamsMatchTokens() {
        // First light, hero horizon 66.8%.
        val hz = 0.668f
        val fl = skySpec(SkyKind.FIRST_LIGHT, horizon = hz)
        assertStops(
            listOf(
                0f to 0xFF0A1030, hz - 0.37f to 0xFF122752, hz - 0.17f to 0xFF183F60, hz - 0.09f to 0xFF1F5366,
                hz - 0.033f to 0xFFB08680, hz to 0xFFF0AE8C, hz + 0.002f to 0xFF272C50, hz + 0.12f to 0xFF181D3E,
                1f to 0xFF0F1430,
            ),
            fl.stops,
        )
        assertEquals(1, fl.blobs.size)
        assertBlob(fl.blobs[0], 340f, 260f, SkyCoord(1f, -50f), SkyCoord(0f, 90f), c(0xFF2FB8A6, 0.30f), Drift(DriftKind.A, 28f))
        assertEquals(SkyNoise(opacity = 0.32f, periodS = 80f, flowX = 400f, flowY = 0f), fl.noise)
        assertEquals(NoiseMask(zeroTop = 0.10f, fullTop = 0.40f, fullBottom = hz - 0.01f, zeroBottom = hz), fl.noiseMask)
        assertNotNull(fl.sun)
        val sun = fl.sun!!
        assertEquals(hz, sun.horizon, eps)
        assertEquals(19f, sun.radius, eps)
        assertEquals(48f, sun.risePerScore, eps)
        assertEquals(c(0xFFFFF3E4), sun.core); assertEquals(0.38f, sun.coreStop, eps)
        assertEquals(c(0xFFFFD9B0), sun.mid); assertEquals(0.66f, sun.midStop, eps)
        assertEquals(c(0xFFFFCDA0, 0.40f), sun.halo); assertEquals(18f, sun.haloBlur, eps); assertEquals(4f, sun.haloSpread, eps)
        assertEquals(400f, sun.glowWidth, eps); assertEquals(130f, sun.glowHeight, eps)
        assertEquals(c(0xFFFFC496, 0.55f), sun.glowCenter); assertEquals(c(0xFFFFA078, 0.16f), sun.glowMid)
        assertEquals(12f, sun.breathPeriodS, eps); assertEquals(1.06f, sun.breathScale, eps); assertEquals(0.92f, sun.breathAlphaMin, eps)
        assertEquals(c(0xFFFFD7B5, 0.65f), sun.horizonLine)
        assertEquals(70f, sun.reflectWidth, eps); assertEquals(96f, sun.reflectHeight, eps)
        assertEquals(c(0xFFFFC8A0, 0.42f), sun.reflect)
        assertEquals(7f, sun.glintPeriodS, eps); assertEquals(0.55f, sun.glintMin, eps); assertEquals(0.9f, sun.glintMax, eps)
        assertEquals(0.62f, SkyDefaults.WelcomeHorizon, eps)
        assertEquals(0.668f, SkyDefaults.HeroHorizon, eps)

        // Night.
        val nt = skySpec(SkyKind.NIGHT)
        assertStops(listOf(0f to 0xFF0B0A2A, 0.52f to 0xFF161452, 1f to 0xFF241E74), nt.stops)
        assertBlob(nt.blobs[0], 320f, 280f, SkyCoord(1f, -70f), SkyCoord(0f, 80f), c(0xFF9C86FF, 0.50f), Drift(DriftKind.A, 26f))
        assertBlob(nt.blobs[1], 380f, 320f, SkyCoord(0f, 50f), SkyCoord(1f, -30f), c(0xFF4B3FD0, 0.62f), Drift(DriftKind.B, 30f))
        assertBlob(nt.blobs[2], 240f, 200f, SkyCoord(0.38f, 120f), SkyCoord(0.40f, 100f), c(0xFFB04BD8, 0.22f), Drift(DriftKind.A, 22f, reversed = true))
        assertEquals(SkyNoise(0.42f, 90f, 400f, 0f), nt.noise)
        assertEquals(Scrim(from = 0.5f, color = Color(0xFF0B0A2A), maxAlpha = 0.5f), nt.scrim)
        assertTrue(nt.hasStars)
        assertEquals(4400f, StarTokens.DP2_PER_STAR, eps)
        assertEquals(0.85f, StarTokens.NIGHT_MAX_Y, eps)
        assertEquals(0.35f, StarTokens.FIRST_LIGHT_MAX_Y, eps)
        assertEquals(Color(0xFFFFF8EE), StarTokens.COLOR)
        assertEquals(0.25f, StarTokens.ALPHA_MIN, eps); assertEquals(0.85f, StarTokens.ALPHA_MAX, eps)
        assertEquals(0.18f, StarTokens.TWINKLE_MIN, eps); assertEquals(0.95f, StarTokens.TWINKLE_MAX, eps)
        assertEquals(3f, StarTokens.TWINKLE_PERIOD_MIN_S, eps); assertEquals(7f, StarTokens.TWINKLE_PERIOD_MAX_S, eps)
        assertEquals(c(0xFFFFF0DC, 0.5f), StarTokens.GLOW)

        // Ember.
        val em = skySpec(SkyKind.EMBER)
        assertStops(listOf(0f to 0xFF170B22, 0.48f to 0xFF3A1230, 1f to 0xFF6E2230), em.stops)
        assertBlob(em.blobs[0], 260f, 200f, SkyCoord(0.30f, 130f), SkyCoord(0.46f, 100f), c(0xFFFF5E78, 0.28f), Drift(DriftKind.B, 18f))
        assertBlob(em.blobs[1], 440f, 300f, SkyCoord(0f, 160f), SkyCoord(1f, 65f), c(0xFFFF7759, 0.70f), Drift(DriftKind.A, 20f))
        assertBlob(em.blobs[2], 320f, 250f, SkyCoord(1f, -40f), SkyCoord(1f, 35f), c(0xFFFFC65C, 0.50f), Drift(DriftKind.B, 24f))
        assertEquals(SkyNoise(0.48f, 34f, 60f, -400f), em.noise)

        // Pulse.
        val pu = skySpec(SkyKind.PULSE)
        assertStops(listOf(0f to 0xFF1A0B25, 0.60f to 0xFF2A0D35, 1f to 0xFF331040), pu.stops)
        assertBlob(pu.blobs[0], 300f, 260f, SkyCoord(0f, 30f), SkyCoord(0f, 40f), c(0xFFB04BD8, 0.40f), Drift(DriftKind.A, 16f))
        assertBlob(pu.blobs[1], 460f, 380f, SkyCoord(0.5f, 0f), SkyCoord(0.72f, 0f), c(0xFFFF5E8E, 0.55f), null)
        assertEquals(0.55f, pu.blobs[1].midStop!!, eps)
        assertEquals(c(0xFFFF5E8E, 0.16f), pu.blobs[1].midColor)
        assertEquals(Beat(blobIndex = 1, peakScale = 1.035f, peakAt = 0.18f, defaultBpm = 60f), pu.beat)
        assertEquals(SkyNoise(0.30f, 70f, 400f, 0f), pu.noise)

        // Calm: amber alpha follows stress.
        val ca = skySpec(SkyKind.CALM, stress = 0.32f)
        assertStops(listOf(0f to 0xFF0A1A2A, 1f to 0xFF0F2B36), ca.stops)
        assertBlob(ca.blobs[0], 380f, 300f, SkyCoord(0f, 70f), SkyCoord(1f, -30f), c(0xFF3FC1B0, 0.32f), Drift(DriftKind.A, 24f))
        assertBlob(ca.blobs[1], 300f, 240f, SkyCoord(1f, -50f), SkyCoord(0f, 30f), c(0xFFFFC478, 0.06f + 0.40f * 0.32f), Drift(DriftKind.B, 24f))
        assertEquals(0.06f, skySpec(SkyKind.CALM, stress = 0f).blobs[1].color.alpha, 0.005f)
        assertEquals(SkyNoise(0.30f, 80f, 400f, 0f), ca.noise)

        // Tide.
        val td = skySpec(SkyKind.TIDE)
        assertStops(listOf(0f to 0xFF0A1830, 1f to 0xFF0D2A40), td.stops)
        assertBlob(td.blobs[0], 360f, 260f, SkyCoord(1f, -60f), SkyCoord(1f, -20f), c(0xFF4FC7DB, 0.40f), Drift(DriftKind.A, 22f))
        assertEquals(Shimmer(angleDeg = 110f, color = c(0xFFA9EEF2, 0.10f), widthFactor = 2.6f, from = 1.2f, to = -1.2f, periodS = 18f), td.shimmer)
        assertEquals(SkyNoise(0.26f, 60f, 400f, 0f), td.noise)

        // Only First light has a sun, only Night a scrim, only Pulse a beat, only Tide a shimmer.
        SkyKind.entries.forEach { k ->
            val s = skySpec(k)
            assertEquals(k == SkyKind.FIRST_LIGHT, s.sun != null)
            assertEquals(k == SkyKind.NIGHT, s.scrim != null)
            assertEquals(k == SkyKind.PULSE, s.beat != null)
            assertEquals(k == SkyKind.TIDE, s.shimmer != null)
        }
        assertNull(skySpec(SkyKind.EMBER).noiseMask)
    }

    @Test
    fun driftFollowsTokenFormula() {
        // k = 0.5 - 0.5 cos(2 pi t / 2T): 0 at t=0, 1 at t=T, back to 0 at 2T.
        assertEquals(0f, driftK(0f, 26f, reversed = false), eps)
        assertEquals(1f, driftK(26f, 26f, reversed = false), eps)
        assertEquals(0f, driftK(52f, 26f, reversed = false), eps)
        assertEquals(1f, driftK(0f, 26f, reversed = true), eps)
        // driftA at k=0: offset (-5%, +3%) of the blob size, scale 1; at k=1: (+5%, -3%), scale 1.06.
        val a0 = driftTransform(DriftKind.A, 0f, 320f, 280f)
        assertEquals(-16f, a0.dx, eps); assertEquals(8.4f, a0.dy, eps); assertEquals(1f, a0.scale, eps)
        val a1 = driftTransform(DriftKind.A, 1f, 320f, 280f)
        assertEquals(16f, a1.dx, eps); assertEquals(-8.4f, a1.dy, eps); assertEquals(1.06f, a1.scale, eps)
        // driftB: (+4%, -2%) scale 1.04 -> (-4%, +4%) scale 0.98.
        val b0 = driftTransform(DriftKind.B, 0f, 100f, 100f)
        assertEquals(4f, b0.dx, eps); assertEquals(-2f, b0.dy, eps); assertEquals(1.04f, b0.scale, eps)
        val b1 = driftTransform(DriftKind.B, 1f, 100f, 100f)
        assertEquals(-4f, b1.dx, eps); assertEquals(4f, b1.dy, eps); assertEquals(0.98f, b1.scale, eps)
    }

    @Test
    fun pulseBeatsAtHeartRateWithSmallAmplitude() {
        val period73 = 60f / 73f
        assertEquals(0.822f, period73, 0.001f)
        assertEquals(1f, pulseScale(0f, 73f), eps)
        assertEquals(1.035f, pulseScale(period73 * 0.18f, 73f), eps)
        assertEquals(1f, pulseScale(period73, 73f), 1e-3f)
        // Unknown HR beats at 60 bpm: peak at 0.18 s.
        assertEquals(1.035f, pulseScale(0.18f, 0f), eps)
        val max = (0..1000).maxOf { pulseScale(it / 1000f * 3f, 73f) }
        assertTrue("amplitude stays within 3.5%", max <= 1.035f + eps)
    }

    @Test
    fun sunRisesWithScore() {
        val spec = skySpec(SkyKind.FIRST_LIGHT)
        // Hero card 342 x 392: horizon at 66.8% = 261.856 dp; score 78 lifts the sun 0.28 * 48 dp above it.
        val f = resolveFrame(spec, 342f, 392f, t = 0f, param = 0.78f)
        assertEquals(261.856f, f.horizonY, 1e-3f)
        assertEquals(261.856f - 0.28f * 48f, f.sunY, 1e-3f)
        assertEquals(171f, f.sunX, eps)
        val low = resolveFrame(spec, 342f, 392f, t = 0f, param = 0.2f)
        assertTrue("a low score sets the sun below the horizon", low.sunY > low.horizonY)
    }

    @Test
    fun blobsResolveAtTokenPositions() {
        val f = resolveFrame(skySpec(SkyKind.NIGHT), 342f, 392f, t = 0f, param = 0f)
        // b1 at (W - 70, 80), driftA at k=0 shifts by (-5%, +3%) of 320 x 280, scale 1: radii 160 x 140.
        val b1 = f.blobs[0]
        assertEquals(342f - 70f - 16f, b1.cx, eps); assertEquals(80f + 8.4f, b1.cy, eps)
        assertEquals(160f, b1.rx, eps); assertEquals(140f, b1.ry, eps)
        // Intensity scales blob alpha.
        val half = resolveFrame(skySpec(SkyKind.NIGHT), 342f, 392f, t = 0f, param = 0f, intensity = 0.5f)
        assertEquals(0.25f, half.blobs[0].color.alpha, 0.005f)
    }

    @Test
    fun noiseFlowWrapsOnTheTilePeriod() {
        val spec = skySpec(SkyKind.NIGHT) // 400 dp per 90 s along +x
        assertEquals(200f, resolveFrame(spec, 100f, 100f, 45f, 0f).noiseX, 1e-2f)
        assertEquals(0f, resolveFrame(spec, 100f, 100f, 90f, 0f).noiseX, 1e-2f)
        val ember = skySpec(SkyKind.EMBER)
        val e = resolveFrame(ember, 100f, 100f, 17f, 0f)
        assertEquals(30f, e.noiseX, 1e-2f)
        assertEquals(200f, e.noiseY, 1e-2f) // -200 wrapped into [0, 400)
    }

    @Test
    fun starFieldIsDeterministicAndStaysInItsBand() {
        val a = starField(342f, 392f, count = 30, seed = 5, maxY = 0.85f)
        val b = starField(342f, 392f, count = 30, seed = 5, maxY = 0.85f)
        assertEquals(a, b)
        assertEquals(30, a.size)
        assertTrue(a.all { it.x in 0f..342f && it.y in 0f..(392f * 0.85f) })
        assertTrue(a.all { it.size == 1f || it.size == 1.5f || it.size == 2f })
        assertTrue(a.all { it.alpha in 0.25f..0.85f })
        assertTrue("some stars twinkle", a.any { it.twinkle })
        assertTrue(a.filter { it.twinkle }.all { it.periodS in 3f..7f })
        // Default density: about one star per 4,400 dp2, so the 342 x 392 hero gets 30.
        assertEquals(30, defaultStarCount(342f, 392f))
        // Twinkling stars swing between 0.18 and 0.95.
        val tw = a.first { it.twinkle }
        val alphas = (0..700).map { tw.alphaAt(it / 100f) }
        assertEquals(0.18f, alphas.min(), 0.01f)
        assertEquals(0.95f, alphas.max(), 0.01f)
    }
}
