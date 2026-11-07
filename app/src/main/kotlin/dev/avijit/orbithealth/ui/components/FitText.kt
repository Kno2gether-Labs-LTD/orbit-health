package dev.avijit.orbithealth.ui.components

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.sp

/** Shrink-to-fit sizing for one-line values, kept pure so the rule is unit-tested. */
object TextFit {
    /**
     * The largest font size from [from] down to [min] (in [step] steps) at which [widthAt] fits [maxWidth]; [min] when
     * nothing fits (the text then ellipsizes rather than clipping mid-glyph).
     */
    fun fitFontSize(from: Float, min: Float, step: Float, maxWidth: Float, widthAt: (Float) -> Float): Float {
        var s = from
        while (s > min && widthAt(s) > maxWidth) s = maxOf(min, s - step)
        return s
    }
}

/**
 * A one-line value that shrinks to fit its width, from [style]'s size down to [minFontSize], instead of clipping
 * ("1,240 kcal" in a 107dp mini card, "104 bpm" on a 360dp phone). Spans with their own size (the unit) keep it, and
 * letter spacing scales with the size so tight numerals stay tight.
 *
 * Plain Text underneath (no SubcomposeLayout), so it works inside intrinsic-height rows: the first layout that
 * overflows works out the fitting size in one go and the text recomposes once at that size.
 */
@Composable
fun FitText(
    text: AnnotatedString,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    minFontSize: TextUnit = 20.sp,
) {
    val measurer = rememberTextMeasurer()
    var size by remember(text, style) { mutableStateOf(style.fontSize.value) }
    Text(
        text, style = scaled(style, size), color = color, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
        modifier = modifier,
        onTextLayout = { r ->
            val maxW = r.layoutInput.constraints.maxWidth
            if (r.layoutInput.constraints.hasBoundedWidth && size > minFontSize.value) {
                fun widthAt(sp: Float) = measurer.measure(text, scaled(style, sp), maxLines = 1, softWrap = false).size.width.toFloat()
                if (widthAt(size) > maxW) size = TextFit.fitFontSize(size, minFontSize.value, 1f, maxW.toFloat(), ::widthAt)
            }
        },
    )
}

/**
 * [style] shrunk (by 2sp steps, to no less than [minSp]) so [sample] fits [maxWidth] on one line, measured once per
 * value rather than per frame: the big arc numerals use it with their final value, so "10,000" steps sit inside the
 * arc instead of running over its stroke, while the count-up keeps one size. The line height is kept, so the numeral
 * stays centred in its token line box.
 */
@Composable
fun rememberFittedStyle(sample: String, style: TextStyle, maxWidth: Dp, minSp: Float): TextStyle {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(sample, style, maxWidth, density) {
        val maxPx = with(density) { maxWidth.toPx() }
        val sp = TextFit.fitFontSize(style.fontSize.value, minSp, 2f, maxPx) { s ->
            measurer.measure(sample, scaled(style, s), maxLines = 1, softWrap = false).size.width.toFloat()
        }
        scaled(style, sp)
    }
}

internal fun scaled(style: TextStyle, sp: Float): TextStyle {
    if (sp == style.fontSize.value) return style
    val k = sp / style.fontSize.value
    return style.copy(
        fontSize = sp.sp,
        letterSpacing = if (style.letterSpacing.isSp) (style.letterSpacing.value * k).sp else style.letterSpacing,
    )
}
