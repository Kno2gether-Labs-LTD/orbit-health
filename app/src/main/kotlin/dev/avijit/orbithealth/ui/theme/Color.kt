package dev.avijit.orbithealth.ui.theme

import androidx.compose.ui.graphics.Color

object OrbitColors {
    val ReadinessStart = Color(0xFF8B7BFF)
    val ReadinessEnd = Color(0xFFC58BFF)
    val Sleep = Color(0xFF5EE7C4)
    val Activity = Color(0xFFFF9F6B)
    val Heart = Color(0xFFFF6B8B)

    /** Low-to-high stress: calm teal, amber, orange, red. */
    val StressScale: List<Color> = listOf(
        Color(0xFF5EE7C4), Color(0xFFFFD36B), Color(0xFFFF9F6B), Color(0xFFFF5C7A),
    )

    val BackgroundCenter = Color(0xFF3B2A8A)
    val BackgroundMid = Color(0xFF120F2E)
    val BackgroundEdge = Color(0xFF05060F)

    val GlassFill = Color.White.copy(alpha = 0.07f)
    val GlassBorder = Color.White.copy(alpha = 0.12f)

    val TextPrimary = Color(0xFFF2F0FF)
    val TextSecondary = Color(0xFFB4AFD6)

    /** Live-from-the-ring value green. */
    val Live = Color(0xFF7DFFB0)

    /** Sleep stage lanes (hypnogram and split bar). */
    val StageAwake = Color(0xFFFFD36B)
    val StageRem = Color(0xFF5EE7C4)
    val StageLight = Color(0xFF8B7BFF)
    val StageDeep = Color(0xFF4B3BD6)

    /** SpO2 dot colours: normal, slight dip, real dip. */
    val Spo2Normal = Color(0xFF7DF5D6)
    val Spo2Dip = Color(0xFFFFD36B)
    val Spo2Low = Color(0xFFFF9F6B)

    /** ESTIMATE pill. */
    val EstimateFill = Color(0x22FFB86B)
    val EstimateBorder = Color(0x44FFB86B)
    val EstimateText = Color(0xFFFFC98B)

    /** Neutral tracks behind progress (rings, bars) and hairlines. */
    val Track = Color.White.copy(alpha = 0.08f)
    val Hairline = Color.White.copy(alpha = 0.06f)
    val Accent = Color(0xFFC5B8FF)
}

/**
 * Living Skies palette (tokens.md §1, binding). Warm white ink on midnight; metric hues as a → b pairs; status words in
 * warm hues (never pure red or green). Supersedes the Glass Orbit [OrbitColors], which stays until every screen migrates.
 */
object OrbitPalette {
    val Base0 = Color(0xFF0A0E24)
    val Base1 = Color(0xFF121A3A)
    /** What card tints are composited over. */
    val Surface = Color(0xFF0F1533)

    val Ink = Color(0xFFF4F1EA)
    val Ink2 = Ink.copy(alpha = 0.62f)
    /** Secondary text on skies (skies are brighter than the base). */
    val InkSky2 = Ink.copy(alpha = 0.82f)
    val Hairline = Ink.copy(alpha = 0.10f)
    /** 1dp border on every card. */
    val HairlineWarm = Color(0xFFFFE2C4).copy(alpha = 0.10f)
    val NavInactive = Ink.copy(alpha = 0.58f)
    /** Label colour on the warm primary button. */
    val OnInk = Base0

    val SleepA = Color(0xFF4B3FD0); val SleepB = Color(0xFF9C86FF)
    val ReadinessA = Color(0xFF2FB8A6); val ReadinessB = Color(0xFFFFB48A)
    val ActivityA = Color(0xFFFF7759); val ActivityB = Color(0xFFFFC65C)
    val HeartA = Color(0xFFFF5E8E); val HeartB = Color(0xFFB04BD8)
    val StressA = Color(0xFF3FC1B0); val StressB = Color(0xFFF2B65E)
    /** Calm-breaks goal fill end (tokens §5 goal rows: stress #3FC1B0 → #8FE3D3). */
    val StressFillEnd = Color(0xFF8FE3D3)
    val Spo2A = Color(0xFF4FC7DB); val Spo2B = Color(0xFFA9EEF2)

    val Optimal = Color(0xFF5ED6C2)
    val Good = Color(0xFFFFD383)
    val Attention = Color(0xFFFF9A7A)

    val StageAwake = Color(0xFFF7C873)
    val StageRem = Color(0xFFB9A8FF)
    val StageLight = Color(0xFF8E9BFF)
    val StageDeep = Color(0xFF6A5CF5)

    val StressRelaxed = Color(0xFF3FC1B0)
    val StressNormal = Color(0xFF9ED0A0)
    val StressMedium = Color(0xFFF2B65E)
    val StressHigh = Color(0xFFFF9A7A)

    /** Tinted card fills: hue a @ 14% over [Surface], pre-composited (see `cardTint`). */
    val TintSleep = Color(0xFF171B49)
    val TintReadiness = Color(0xFF132C43)
    val TintActivity = Color(0xFF312338)
    val TintHeart = Color(0xFF311F40)
    val TintStress = Color(0xFF162D44)
    val TintSpo2 = Color(0xFF182E4B)
    /** Ink @ 5%. */
    val TintNeutral = Color(0xFF1A203C)
    /** #9C86FF @ 10%. */
    val TintAskChip = Color(0xFF1D2047)

    /** Planet accent (nav dot, tab dot): 135° gradient with a pink glow. */
    val PlanetA = Color(0xFFC2A9FF)
    val PlanetB = Color(0xFFFF93C9)
    val PlanetGlow = Color(0xFFFF93C9).copy(alpha = 0.55f)
    /** Warm planet (page dots, arc). */
    val WarmPlanetA = Color(0xFFFFE9D2)
    val WarmPlanetB = Color(0xFFFFB48A)
    val WarmPlanetGlow = Color(0xFFFFBE8C).copy(alpha = 0.70f)
    val PlanetCore = Color(0xFFFFF7EE)
    val PlanetHalo = Color(0xFFFFE6CC).copy(alpha = 0.80f)

    /** Primary button glow and bottom bar. */
    val ButtonGlow = Color(0xFFFFD6B4).copy(alpha = 0.45f)
    val BarFill = Color(0xFF141A3A).copy(alpha = 0.72f)
    val BarFillOpaque = Color(0xFF141A3A).copy(alpha = 0.94f)
    val BarBorder = Ink.copy(alpha = 0.10f)
    val BarShadow = Color(0xFF030512).copy(alpha = 0.60f)
}
