package dev.avijit.orbithealth.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.avijit.orbithealth.ui.theme.OrbitPalette

private val TrackWidth = 44.dp
private val TrackHeight = 26.dp
private val Knob = 20.dp
private val KnobInset = 3.dp

private val OnTrack = OrbitPalette.SleepB
private val OffTrack = OrbitPalette.Ink.copy(alpha = 0.18f)

/**
 * Tokens §5 toggle: a 44×26 track in #9C86FF (ink 18% when off) with a 20Ø ink thumb inset 3 and a soft (0, 1) 3dp
 * shadow at 30%. The thumb glides over 180 ms. With [onCheckedChange] null it is a read-only indicator; [enabled] =
 * false dims it to 40%.
 */
@Composable
fun OrbitToggle(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val track by animateColorAsState(if (checked) OnTrack else OffTrack, tween(180), label = "track")
    val x by animateDpAsState(if (checked) TrackWidth - Knob - KnobInset else KnobInset, tween(180), label = "knob")
    val interactive = onCheckedChange != null && enabled
    Box(
        modifier
            .size(TrackWidth, TrackHeight)
            .alpha(if (enabled) 1f else 0.4f)
            .clip(RoundedCornerShape(TrackHeight / 2))
            .background(track)
            .then(
                if (interactive) {
                    Modifier.toggleable(checked, remember { MutableInteractionSource() }, indication = null, role = Role.Switch) {
                        onCheckedChange?.invoke(it)
                    }
                } else Modifier,
            ),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier.offset { IntOffset(x.roundToPx(), 0) }.size(Knob)
                .softShadow(Color.Black.copy(alpha = 0.30f), offsetY = 1.dp, blur = 3.dp, cornerRadius = Knob / 2)
                .clip(CircleShape).background(OrbitPalette.Ink),
        )
    }
}
