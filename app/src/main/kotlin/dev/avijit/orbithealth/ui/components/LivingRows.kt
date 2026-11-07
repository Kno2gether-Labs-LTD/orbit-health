package dev.avijit.orbithealth.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.unit.em
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.avijit.orbithealth.ui.theme.OrbitPalette
import dev.avijit.orbithealth.ui.theme.OrbitTypography
import dev.avijit.orbithealth.ui.theme.lineBox

/**
 * Mini card (tokens §5, one of the three-up row): mini tint of [hue], a 14dp glyph in the hue beside the label, the
 * value with its unit at +10, then a caption at +6 in [footColor].
 */
@Composable
fun MiniMetricCard(
    hue: Color,
    symbol: OrbitSymbol?,
    label: String,
    value: String,
    unit: String,
    foot: String,
    modifier: Modifier = Modifier,
    footColor: Color = OrbitPalette.Ink2,
    /** Ink for a live value; [OrbitPalette.Ink2] for a neutral placeholder (a stale or missing reading). */
    valueColor: Color = OrbitPalette.Ink,
    /** 2 lets a long label wrap as the mockup's flex label does ("Lowest / HR" on Sleep), never truncating it. */
    labelMaxLines: Int = 1,
    /** Body's two-up Heart minis use 16 all round with the value at +8 and no glyph. */
    contentPadding: PaddingValues = TintedCardDefaults.MiniPadding,
    valueTop: Dp = 10.dp,
    /** Activity's glyphs are in hue b (#FFC65C) on the hue-a tint. */
    glyphTint: Color = hue,
) {
    TintedCard(
        hue, modifier.semantics(mergeDescendants = true) {}, shape = TintedCardDefaults.MiniShape,
        contentPadding = contentPadding,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (symbol != null) Symbol(symbol, size = 14.dp, strokeWidth = 1.8f, tint = glyphTint)
            // "SpO₂": the mockup sets the 2 as a small subscript, not the font's full-size ₂ glyph.
            val text = buildAnnotatedString {
                label.forEach { c ->
                    if (c == '₂') withStyle(SpanStyle(fontSize = 0.62.em, baselineShift = BaselineShift(-0.18f))) { append('2') }
                    else append(c)
                }
            }
            Text(
                text, style = OrbitTypography.Label, maxLines = labelMaxLines,
                // One line sits in its CSS line box; wrapped lines keep the 16sp line height of the token.
                modifier = if (labelMaxLines == 1) Modifier.lineBox(OrbitTypography.Label) else Modifier,
            )
        }
        // Shrinks to fit rather than clipping: "1,240 kcal" or "104 bpm" in a 107dp mini at the mockup width or below.
        FitText(
            buildAnnotatedString {
                append(value)
                if (unit.isNotEmpty()) withStyle(OrbitTypography.Unit.toSpanStyle()) { append(" $unit") }
            },
            style = OrbitTypography.Value, color = valueColor,
            modifier = Modifier.padding(top = valueTop).lineBox(OrbitTypography.Value),
        )
        Text(
            foot, style = OrbitTypography.Caption, color = footColor, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp).lineBox(OrbitTypography.Caption),
        )
    }
}

/**
 * Goal row (tokens §5): a 36Ø disc of [discHue] @ 16% holding an 18dp glyph, 14dp gap, the title row ([trailing] at
 * the right: strong when there is progress, meta otherwise), a meta line, and a 4dp progress track filled with
 * [fill] when [progress] is set. Rows after the first sit under a hairline with 14dp vertical padding.
 */
@Composable
fun GoalProgressRow(
    symbol: OrbitSymbol,
    discHue: Color,
    glyph: Color,
    title: String,
    trailing: String,
    meta: String,
    progress: Float?,
    fill: Brush?,
    modifier: Modifier = Modifier,
    first: Boolean = false,
    last: Boolean = false,
) {
    Column(modifier.semantics(mergeDescendants = true) {}) {
        if (!first) Spacer(Modifier.fillMaxWidth().height(1.dp).background(OrbitPalette.Hairline))
        Row(Modifier.padding(top = if (first) 4.dp else 14.dp, bottom = if (last) 0.dp else 14.dp)) {
            Box(Modifier.size(36.dp).background(discHue.copy(alpha = 0.16f), CircleShape), contentAlignment = Alignment.Center) {
                Symbol(symbol, size = 18.dp, strokeWidth = 1.7f, tint = glyph)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = OrbitTypography.BodyStrong, maxLines = 1, modifier = Modifier.weight(1f).lineBox(OrbitTypography.BodyStrong))
                    Text(
                        trailing, style = if (progress != null) OrbitTypography.BodyStrong.copy(fontFeatureSettings = "tnum") else OrbitTypography.Meta,
                        maxLines = 1, modifier = Modifier.lineBox(OrbitTypography.BodyStrong),
                    )
                }
                Text(meta, style = OrbitTypography.Meta, modifier = Modifier.padding(top = 2.dp))
                if (progress != null && fill != null) {
                    val p = progress.coerceIn(0f, 1f)
                    Canvas(
                        Modifier.padding(top = 10.dp).fillMaxWidth().height(4.dp)
                            .clearAndSetSemantics { contentDescription = "${(p * 100).toInt()}% done" },
                    ) {
                        val r = CornerRadius(2.dp.toPx())
                        drawRoundRect(OrbitPalette.Ink.copy(alpha = 0.10f), cornerRadius = r)
                        if (p > 0f) drawRoundRect(fill, size = Size(maxOf(size.width * p, size.height), size.height), cornerRadius = r)
                    }
                }
            }
        }
    }
}
