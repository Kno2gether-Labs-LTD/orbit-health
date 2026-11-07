package dev.avijit.orbithealth.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import dev.avijit.orbithealth.ui.theme.OrbitPalette
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class LivingSkiesKitTest {
    private val eps = 1e-3f

    @Test
    fun orbitArcPlanetPositionAtFraction() {
        val c = Offset(171f, 156f) // hero card: circle bounds top 46, d 220 -> centre (171, 156)
        val r = 110f
        // 0 sits at the start of the arc (160 degrees, lower left).
        val start = OrbitArcGeometry.planetPosition(0f, c, r)
        val a = Math.toRadians(160.0)
        assertEquals(171f + r * cos(a).toFloat(), start.x, eps)
        assertEquals(156f + r * sin(a).toFloat(), start.y, eps)
        // Half way is straight up (270 degrees): the gap is centred at the bottom.
        val mid = OrbitArcGeometry.planetPosition(0.5f, c, r)
        assertEquals(171f, mid.x, eps); assertEquals(46f, mid.y, eps)
        // Full is 380 = 20 degrees, mirror of the start.
        val end = OrbitArcGeometry.planetPosition(1f, c, r)
        assertEquals(171f + (171f - start.x), end.x, eps); assertEquals(start.y, end.y, eps)
        // Readiness 78: sweep 171.6 degrees, planet at 331.6 degrees.
        assertEquals(171.6f, OrbitArcGeometry.sweepFor(0.78f), eps)
        val p78 = OrbitArcGeometry.planetPosition(0.78f, c, r)
        val a78 = Math.toRadians(331.6)
        assertEquals(171f + r * cos(a78).toFloat(), p78.x, eps)
        assertEquals(156f + r * sin(a78).toFloat(), p78.y, eps)
        // Out-of-range fractions clamp onto the arc.
        assertEquals(end, OrbitArcGeometry.planetPosition(1.4f, c, r))
        assertEquals(start, OrbitArcGeometry.planetPosition(-0.2f, c, r))
    }

    @Test
    fun arcVariantsMatchTokens() {
        val hero = OrbitArcDefaults.styleFor(OrbitArcDefaults.HeroSize)
        assertEquals(220f, OrbitArcDefaults.HeroSize.value, 0f)
        assertEquals(3f, hero.stroke.value, 0f); assertEquals(10f, hero.planet.value, 0f); assertEquals(15f, hero.halo.value, 0f)
        assertEquals(OrbitPalette.Ink.copy(alpha = 0.18f), hero.track)
        assertEquals(OrbitPalette.Ink.copy(alpha = 0.85f), hero.progress)
        val detail = OrbitArcDefaults.styleFor(OrbitArcDefaults.DetailSize)
        assertEquals(176f, OrbitArcDefaults.DetailSize.value, 0f)
        assertEquals(9f, detail.planet.value, 0f); assertEquals(13.5f, detail.halo.value, 0f)
        val battery = OrbitArcDefaults.styleFor(OrbitArcDefaults.BatterySize)
        assertEquals(46f, OrbitArcDefaults.BatterySize.value, 0f)
        assertEquals(6f, battery.planet.value, 0f); assertEquals(9f, battery.halo.value, 0f)
        assertEquals(Color(0xFF5ED6C2), battery.progress)
    }

    @Test
    fun tintedCardFillsMatchTokens() {
        fun hex(c: Color) = c.toArgb()
        assertEquals(0xFF171B49.toInt(), hex(cardTint(OrbitPalette.SleepA)))
        assertEquals(0xFF132C43.toInt(), hex(cardTint(OrbitPalette.ReadinessA)))
        assertEquals(0xFF312338.toInt(), hex(cardTint(OrbitPalette.ActivityA)))
        assertEquals(0xFF311F40.toInt(), hex(cardTint(OrbitPalette.HeartA)))
        assertEquals(0xFF162D44.toInt(), hex(cardTint(OrbitPalette.StressA)))
        assertEquals(0xFF182E4B.toInt(), hex(cardTint(OrbitPalette.Spo2A)))
        assertEquals(0xFF1A203C.toInt(), hex(cardTint(OrbitPalette.Ink, TintedCardDefaults.NeutralAmount)))
        assertEquals(0xFF1D2047.toInt(), hex(cardTint(OrbitPalette.SleepB, TintedCardDefaults.ChipAmount)))
        // The named tint constants agree with the formula.
        assertEquals(hex(OrbitPalette.TintSleep), hex(cardTint(OrbitPalette.SleepA)))
        assertEquals(hex(OrbitPalette.TintNeutral), hex(cardTint(OrbitPalette.Ink, 0.05f)))
    }

    @Test
    fun paletteMatchesTokens() {
        assertEquals(Color(0xFF0A0E24), OrbitPalette.Base0)
        assertEquals(Color(0xFF121A3A), OrbitPalette.Base1)
        assertEquals(Color(0xFF0F1533), OrbitPalette.Surface)
        assertEquals(Color(0xFFF4F1EA), OrbitPalette.Ink)
        assertEquals(Color(0xFFF4F1EA).copy(alpha = 0.62f), OrbitPalette.Ink2)
        assertEquals(Color(0xFFF4F1EA).copy(alpha = 0.82f), OrbitPalette.InkSky2)
        assertEquals(Color(0xFFFFE2C4).copy(alpha = 0.10f), OrbitPalette.HairlineWarm)
        assertEquals(Color(0xFFF4F1EA).copy(alpha = 0.58f), OrbitPalette.NavInactive)
        assertEquals(Color(0xFF5ED6C2), OrbitPalette.Optimal)
        assertEquals(Color(0xFFFFD383), OrbitPalette.Good)
        assertEquals(Color(0xFFFF9A7A), OrbitPalette.Attention)
    }

    @Test
    fun calmBreaksFillEndIsAToken() {
        // Tokens §5 goal rows: the stress fill runs #3FC1B0 → #8FE3D3.
        assertEquals(Color(0xFF3FC1B0), OrbitPalette.StressA)
        assertEquals(Color(0xFF8FE3D3), OrbitPalette.StressFillEnd)
    }
}
