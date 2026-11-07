package dev.avijit.orbithealth.ui.theme

import android.content.Context
import android.graphics.Typeface
import android.graphics.fonts.Font as PlatformFont
import android.graphics.fonts.FontFamily as PlatformFontFamily
import android.graphics.fonts.FontStyle as PlatformFontStyle
import androidx.annotation.FontRes
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.AndroidFont
import androidx.compose.ui.text.font.FontLoadingStrategy
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import dev.avijit.orbithealth.R

/*
 * Living Skies type (tokens.md §3). Both faces are bundled variable TTFs (SIL OFL 1.1, res/raw/fraunces_ofl.txt, manrope_ofl.txt), so the
 * exact token axes are reachable — Fraunces wght 360/380/420 with opsz and SOFT, Manrope 250 and 650 — which static
 * instances cannot give. One variable file per face is also smaller than the six static Manrope weights.
 */

/**
 * A variable TTF from res/font at fixed axes. Compose's own `Font(resId, variationSettings = …)` applies axes through a
 * shared thread-local Paint whose `setFontVariationSettings` returns early when the string equals the previous one, so
 * the second file loaded with identical axes (Fraunces italic after roman) silently keeps its default instance
 * (wght 900). This font builds its Typeface directly with `android.graphics.fonts.Font.Builder` instead (API 29+).
 */
@OptIn(ExperimentalTextApi::class)
private class VariableResourceFont(
    @FontRes val resId: Int,
    override val weight: FontWeight,
    override val style: FontStyle,
    val axes: String,
) : AndroidFont(FontLoadingStrategy.Blocking, VariableFontLoader, FontVariation.Settings()) {
    override fun toString() = "VariableResourceFont(res=$resId, $weight, $style, $axes)"
}

@OptIn(ExperimentalTextApi::class)
private object VariableFontLoader : AndroidFont.TypefaceLoader {
    override fun loadBlocking(context: Context, font: AndroidFont): Typeface {
        val f = font as VariableResourceFont
        val slant = if (f.style == FontStyle.Italic) PlatformFontStyle.FONT_SLANT_ITALIC else PlatformFontStyle.FONT_SLANT_UPRIGHT
        val pf = PlatformFont.Builder(context.resources, f.resId)
            .setFontVariationSettings(f.axes)
            .setWeight(f.weight.weight)
            .setSlant(slant)
            .build()
        return Typeface.CustomFallbackBuilder(PlatformFontFamily.Builder(pf).build())
            .setStyle(PlatformFontStyle(f.weight.weight, slant))
            .build()
    }

    override suspend fun awaitLoad(context: Context, font: AndroidFont): Typeface = loadBlocking(context, font)
}

/** Manrope variable (wght 200–800): one Font entry per weight the tokens use, all from the same file. */
val Manrope: FontFamily = FontFamily(
    listOf(200, 250, 300, 400, 500, 600, 650, 700).map { w ->
        VariableResourceFont(R.font.manrope, FontWeight(w), FontStyle.Normal, "'wght' $w")
    },
)

private val frauncesCache = HashMap<Triple<Int, Float, Float>, FontFamily>()

/**
 * Fraunces at one weight / optical size / softness, roman plus italic (the italic file renders `em`, e.g. the name in
 * "Good morning, *Sam.*"). A FontFamily matches only on weight and style, so each axis combination is its own family.
 * WONK stays at the font default (1), as in the mockup's CSS.
 */
fun fraunces(weight: Int, opsz: Float, soft: Float): FontFamily = synchronized(frauncesCache) {
    frauncesCache.getOrPut(Triple(weight, opsz, soft)) {
        val axes = "'wght' $weight, 'opsz' $opsz, 'SOFT' $soft"
        FontFamily(
            VariableResourceFont(R.font.fraunces, FontWeight(weight), FontStyle.Normal, axes),
            VariableResourceFont(R.font.fraunces_italic, FontWeight(weight), FontStyle.Italic, axes),
        )
    }
}

private val Fraunces380Display = fraunces(380, 72f, 100f)
private val Fraunces420H2 = fraunces(420, 36f, 100f)
private val Fraunces380Text = fraunces(380, 24f, 60f)
private val Fraunces360Lead = fraunces(360, 36f, 100f)

/** CSS-like leading: extra line height split evenly above and below, never trimmed. */
private val CssLeading = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)

private fun style(
    family: FontFamily, weight: Int, size: Float, line: Float, tracking: Float, color: Color,
    features: String? = null,
): TextStyle = TextStyle(
    fontFamily = family, fontWeight = FontWeight(weight), fontSize = size.sp,
    lineHeight = if (line > 0f) line.sp else TextUnit.Unspecified,
    letterSpacing = tracking.sp, color = color, fontFeatureSettings = features, lineHeightStyle = CssLeading,
)

/**
 * Living Skies text roles, 1:1 with tokens.md §3 (sizes in sp = mockup px). Colours are the token defaults; pass a
 * colour to Text to override (status words, inkSky2 on skies). Numerals use tabular figures. Use [FontStyle.Italic]
 * spans on the Fraunces styles for the mockup's `<em>`.
 */
object OrbitTypography {
    private val ink = OrbitPalette.Ink
    private val ink2 = OrbitPalette.Ink2
    private const val TNUM = "tnum"

    val Greeting = style(Fraunces380Display, 380, 32f, 38f, -0.5f, ink)
    val Title = style(Fraunces380Display, 380, 34f, 40f, -0.6f, ink)
    val TitleBrand = style(Fraunces380Display, 380, 40f, 46f, -0.6f, ink)
    val H2 = style(Fraunces420H2, 420, 22f, 28f, -0.3f, ink)
    val Insight = style(Fraunces380Text, 380, 18f, 26f, -0.1f, ink)
    val InsightSm = style(Fraunces380Text, 380, 16f, 23f, -0.1f, ink)
    val InsightLg = style(Fraunces360Lead, 360, 21f, 29f, -0.2f, ink)

    val NumXl = style(Manrope, 200, 92f, 92f, -4f, ink, TNUM)
    val NumLg = style(Manrope, 200, 72f, 72f, -3f, ink, TNUM)
    val NumMd = style(Manrope, 250, 44f, 48f, -1.6f, ink, TNUM)
    val Value = style(Manrope, 300, 28f, 32f, -0.8f, ink, TNUM)
    val Unit = style(Manrope, 500, 13f, 16f, 0f, ink2)
    val Word = style(Manrope, 500, 15f, 20f, 0.3f, OrbitPalette.Good)
    val Lead = style(Manrope, 400, 15f, 22f, 0f, ink2)
    val Body = style(Manrope, 500, 15f, 20f, 0f, ink)
    val BodyStrong = style(Manrope, 600, 15f, 20f, 0f, ink)
    val Meta = style(Manrope, 500, 13f, 18f, 0f, ink2)
    val Eyebrow = style(Manrope, 600, 13f, 18f, 0.2f, ink2)
    val Label = style(Manrope, 600, 12f, 16f, 0.3f, ink2)
    val Caption = style(Manrope, 500, 11f, 14f, 0.3f, ink2, TNUM)
    /** Uppercase: pass the text already upper-cased ("READINESS"). */
    val Overline = style(Manrope, 700, 11f, 14f, 1.6f, ink)
    val Tab = style(Manrope, 600, 14f, 20f, 0f, ink)
    val NavLabel = style(Manrope, 600, 11f, 14f, 0.2f, ink)
    val Button = style(Manrope, 650, 16f, 20f, 0.1f, OrbitPalette.OnInk)
    val ButtonGhost = style(Manrope, 600, 15f, 20f, 0f, ink)
    val Segment = style(Manrope, 600, 13f, 0f, 0f, ink)
    /** Uppercase ESTIMATE badge. */
    val Badge = style(Manrope, 700, 10f, 0f, 1.0f, OrbitPalette.Good)
    val RingName = style(Manrope, 650, 19f, 24f, 0f, ink)
}

/**
 * Puts single-line text in its CSS line box of [lineHeight]: the text's natural box (ascent + descent) centred in a
 * box exactly [lineHeight] tall, as CSS half-leading does. Needed whenever the token line height is below the font's
 * natural height (Manrope numerals: 92 / 92 against a 126 sp natural box), because Compose never shrinks a line.
 */
fun Modifier.lineBox(lineHeight: TextUnit): Modifier = layout { measurable, constraints ->
    val h = lineHeight.roundToPx()
    val p = measurable.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
    layout(p.width, h) { p.place(0, (h - p.height) / 2) }
}

/** [lineBox] at [style]'s line height. */
fun Modifier.lineBox(style: TextStyle): Modifier = lineBox(style.lineHeight)
