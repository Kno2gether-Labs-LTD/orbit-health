package dev.avijit.orbithealth.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.avijit.orbithealth.ui.theme.OrbitPalette
import dev.avijit.orbithealth.ui.theme.OrbitTypography

private val PillShape = RoundedCornerShape(percent = 50)

/**
 * The one filled button style (tokens §5): 52dp warm-white pill, midnight label, and a soft warm glow beneath it
 * (CSS `0 10px 30px -10px rgba(255,214,180,.45)`). Disabled drops the glow and fades to 40%. Presses sink to 97%.
 * [busy] shows a small spinner and [busyText] (e.g. "Connecting…") and ignores taps, without dimming.
 */
@Composable
fun WarmButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    busy: Boolean = false,
    busyText: String? = null,
    leading: (@Composable RowScope.() -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, tween(120), label = "press")
    Row(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (enabled) 1f else 0.4f }
            .then(if (enabled) Modifier.warmGlow() else Modifier)
            .height(52.dp)
            .clip(PillShape)
            .background(OrbitPalette.Ink)
            .clickable(interaction, indication = null, enabled = enabled && !busy, role = Role.Button, onClick = onClick)
            .padding(horizontal = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(16.dp), color = OrbitPalette.OnInk, strokeWidth = 1.8.dp)
        } else {
            leading?.invoke(this)
        }
        Text(if (busy) busyText ?: text else text, style = OrbitTypography.Button, maxLines = 1)
    }
}

/** Secondary action (tokens §5 ghost): 48dp pill, ink 10% fill, 1dp ink 18% border. */
@Composable
fun GhostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: androidx.compose.ui.unit.Dp = 20.dp,
    leading: (@Composable RowScope.() -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, tween(120), label = "press")
    Row(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (enabled) 1f else 0.4f }
            .height(48.dp)
            .clip(PillShape)
            .background(OrbitPalette.Ink.copy(alpha = 0.10f))
            .border(1.dp, OrbitPalette.Ink.copy(alpha = 0.18f), PillShape)
            .clickable(interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = contentPadding),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.invoke(this)
        Text(text, style = OrbitTypography.ButtonGhost, maxLines = 1)
    }
}

/** Two ghost buttons side by side ("Try again" / "Open settings"). */
@Composable
fun GhostButtonPair(first: String, onFirst: () -> Unit, second: String, onSecond: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        GhostButton(first, onFirst, Modifier.weight(1f), contentPadding = 12.dp)
        GhostButton(second, onSecond, Modifier.weight(1f), contentPadding = 12.dp)
    }
}

/** CSS `0 10px 30px -10px rgba(255,214,180,.45)` under a pill. */
private fun Modifier.warmGlow(): Modifier = softShadow(OrbitPalette.ButtonGlow, offsetY = 10.dp, blur = 30.dp, spread = (-10).dp)
