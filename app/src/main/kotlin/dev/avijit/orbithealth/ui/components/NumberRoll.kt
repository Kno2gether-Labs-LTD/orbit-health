package dev.avijit.orbithealth.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.avijit.orbithealth.ui.theme.lineBox

/**
 * Tokens §6 number roll (data change): each changed digit slides 18dp vertically over 360 ms (FastOutSlowIn),
 * staggered 30 ms starting from the rightmost changed digit. Up when the value rises, down when it falls.
 */
object NumberRoll {
    const val SLIDE_DP = 18
    const val DURATION_MS = 360
    const val STAGGER_MS = 30

    /**
     * Per character of [to] (left to right): the roll delay in ms, or null when that position (aligned from the right)
     * shows the same character as in [from].
     */
    fun delays(from: String, to: String): List<Int?> {
        val out = arrayOfNulls<Int>(to.length)
        var rank = 0
        for (slot in 0 until to.length) {
            val i = to.length - 1 - slot
            val old = from.getOrNull(from.length - 1 - slot)
            if (old != to[i]) out[i] = STAGGER_MS * rank++
        }
        return out.toList()
    }

    /** The new value is at least the old one (a placeholder such as "—" counts as below everything). */
    fun rollsUp(from: String, to: String): Boolean {
        val a = from.filter(Char::isDigit).toLongOrNull() ?: return true
        val b = to.filter(Char::isDigit).toLongOrNull() ?: return false
        return b >= a
    }
}

@Immutable
private data class DigitSlot(val char: Char, val delay: Int, val up: Boolean)

/**
 * A numeral that rolls its changed digits ([NumberRoll]) when [text] changes. [roll] false (an arc reveal counting up)
 * or reduced motion swaps digits instantly with the same layout. Reads to accessibility as the whole [text].
 *
 * Every digit sits in its own fixed-width, clipped cell (the widest digit of [style], tabular figures), and the roll is
 * drawn with graphics-layer offsets only. Layout never depends on an in-flight animation, so rapid changes (a live
 * heart rate ticking 104 → 105 → 104) can never stack digits on top of each other or make the number jitter in width.
 */
@Composable
fun RollingNumber(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    roll: Boolean = true,
) {
    val motion = rememberMotionEnabled()
    val tabular = remember(style) { style.copy(fontFeatureSettings = "tnum") }
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val digitWidth = remember(tabular, density) {
        with(density) { ('0'..'9').maxOf { measurer.measure(it.toString(), tabular).size.width }.toDp() }
    }
    // The previous text, updated as each new one is planned (keyed on text, so recomposition alone never re-plans).
    val last = remember { arrayOf(text) }
    val slots = remember(text) {
        val from = last[0]
        last[0] = text
        val delays = NumberRoll.delays(from, text)
        val up = NumberRoll.rollsUp(from, text)
        text.mapIndexed { i, c -> DigitSlot(c, delays[i] ?: 0, up) }
    }
    val slide = with(density) { NumberRoll.SLIDE_DP.dp.toPx() }
    Row(modifier.clearAndSetSemantics { this.text = AnnotatedString(text) }) {
        slots.forEachIndexed { i, slot ->
            // Keyed from the right, so the units digit keeps its cell when the number gains a digit.
            key(slots.size - 1 - i) {
                DigitCell(slot, tabular, color, digitWidth, animate = motion && roll, slide = slide)
            }
        }
    }
}

@Composable
private fun DigitCell(slot: DigitSlot, style: TextStyle, color: Color, digitWidth: Dp, animate: Boolean, slide: Float) {
    // The character this cell showed before the current one (null when nothing is rolling out).
    val shown = remember { arrayOf(slot.char) }
    val outgoing = remember(slot.char) {
        val from = shown[0]
        shown[0] = slot.char
        from.takeIf { it != slot.char && animate }
    }
    val progress = remember(slot.char) { Animatable(if (outgoing != null) 0f else 1f) }
    LaunchedEffect(slot.char) {
        if (progress.value < 1f) {
            progress.animateTo(1f, tween(NumberRoll.DURATION_MS, slot.delay, FastOutSlowInEasing))
        }
    }
    val width = if (slot.char.isDigit()) Modifier.width(digitWidth) else Modifier
    val dir = if (slot.up) 1f else -1f
    Box(width.clipToBounds(), contentAlignment = Alignment.Center) {
        if (outgoing != null) {
            Text(
                outgoing.toString(), style = style, color = color, maxLines = 1,
                modifier = Modifier.lineBox(style).graphicsLayer {
                    val p = progress.value
                    translationY = -dir * slide * p
                    alpha = 1f - p
                },
            )
        }
        Text(
            slot.char.toString(), style = style, color = color, maxLines = 1,
            modifier = Modifier.lineBox(style).graphicsLayer {
                val p = progress.value
                translationY = dir * slide * (1f - p)
                alpha = p
            },
        )
    }
}
