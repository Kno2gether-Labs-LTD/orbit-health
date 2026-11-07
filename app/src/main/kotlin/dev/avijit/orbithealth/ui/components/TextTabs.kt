package dev.avijit.orbithealth.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import dev.avijit.orbithealth.ui.theme.OrbitPalette
import dev.avijit.orbithealth.ui.theme.OrbitTypography
import dev.avijit.orbithealth.ui.theme.lineBox

/** Tokens §5 body tabs: 22dp between labels, 34dp row; the active one ink with a planet dot 10dp under its text. */
object TextTabsDefaults {
    val Gap = 22.dp
    val RowHeight = 34.dp
    /** The tappable height: the 34dp row plus the 12dp that follows it, so each tab is a comfortable target. */
    val TouchHeight = 46.dp
    val Dot = 5.dp
}

/** "SpO₂" with the 2 as a small subscript, as the mockup sets it (the font's ₂ glyph is full size). */
fun subscriptTwo(label: String): AnnotatedString = buildAnnotatedString {
    label.forEach { c ->
        if (c == '₂') withStyle(SpanStyle(fontSize = 0.62.em, baselineShift = BaselineShift(-0.18f))) { append('2') }
        else append(c)
    }
}

/**
 * Text tabs (Body's Heart · Stress & SpO₂ · Activity · Trends): Manrope 600 14/20, inactive ink2, active ink with a
 * 5Ø planet-gradient dot (with its pink glow) centred 10dp below the label. Each tab is tappable over the row and the
 * 12dp gap beneath it ([TextTabsDefaults.TouchHeight]); a change ticks (SegmentTick on API 34+). Scrolls sideways if
 * a large font would overflow.
 */
@Composable
fun TextTabs(labels: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val haptics = rememberHaptics()
    val half = TextTabsDefaults.Gap / 2
    Row(
        modifier.horizontalScroll(rememberScrollState()).selectableGroup().padding(horizontal = 24.dp - half),
    ) {
        labels.forEachIndexed { i, label ->
            val on = i == selected
            val color by animateColorAsState(if (on) OrbitPalette.Ink else OrbitPalette.Ink2, tween(200), label = "tab")
            Box(
                // Laid out at the 46dp row + gap, tappable over 48dp (the extra 2dp hang into the space below).
                Modifier.touchTargetHeight(TextTabsDefaults.TouchHeight, hangBelow = true)
                    .semantics { this.selected = on; role = Role.Tab }
                    .clickable(remember { MutableInteractionSource() }, indication = null) {
                        if (!on) {
                            haptics.perform(HapticEvent.TabChange)
                            onSelect(i)
                        }
                    }
                    .padding(horizontal = half),
            ) {
                Text(subscriptTwo(label), style = OrbitTypography.Tab, color = color, maxLines = 1, modifier = Modifier.lineBox(OrbitTypography.Tab))
                if (on) {
                    // CSS: bottom -10px on the 20dp label box, so the dot's centre sits 27.5dp from the top.
                    PlanetDot(Modifier.align(Alignment.TopCenter).offset(y = 20.dp + 10.dp - TextTabsDefaults.Dot))
                }
            }
        }
    }
}

/** The 5Ø planet-gradient dot with an 8dp pink glow (2dp spread). */
@Composable
private fun PlanetDot(modifier: Modifier) {
    Canvas(modifier.size(TextTabsDefaults.Dot)) {
        val r = size.minDimension / 2
        val glow = r + 10.dp.toPx()
        drawCircle(
            Brush.radialGradient(listOf(OrbitPalette.PlanetB.copy(alpha = 0.5f), Color.Transparent), center, glow),
            glow, center,
        )
        drawCircle(Brush.linearGradient(listOf(OrbitPalette.PlanetA, OrbitPalette.PlanetB), start = androidx.compose.ui.geometry.Offset.Zero, end = androidx.compose.ui.geometry.Offset(size.width, size.height)), r, center)
    }
}
