package dev.avijit.orbithealth.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Alignment
import dev.avijit.orbithealth.ui.theme.OrbitPalette
import dev.avijit.orbithealth.ui.theme.OrbitTypography
import androidx.compose.foundation.clickable
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The Living Skies segmented control (tokens §5), e.g. Day / Week: one ink 6% capsule with a hairline and 3dp padding,
 * 28dp segments inside 48dp touch targets, the selected one ink 14%.
 */
@Composable
fun SegmentedPill(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    LivingSegmented(options, selected, onSelect, modifier)
}

@Composable
private fun LivingSegmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier) {
    val outer = RoundedCornerShape(18.dp)
    val inner = RoundedCornerShape(14.dp)
    // No clip on the capsule: each segment's 48dp touch target overflows its 34dp look (a clip would cut the touches).
    Row(
        modifier.selectableGroup().background(OrbitPalette.Ink.copy(alpha = 0.06f), outer)
            .border(1.dp, OrbitPalette.Hairline, outer).padding(3.dp),
    ) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            val bg by animateColorAsState(if (on) OrbitPalette.Ink.copy(alpha = 0.14f) else Color.Transparent, tween(200), label = "seg")
            val interaction = remember { MutableInteractionSource() }
            Box(
                Modifier.touchTargetHeight(28.dp)
                    .semantics { this.selected = on; role = Role.Tab }
                    .clickable(interaction, indication = null) { onSelect(i) },
                contentAlignment = Alignment.Center,
            ) {
                // The visible 28dp segment (and its press ripple) inside the taller target.
                Box(
                    Modifier.height(28.dp).clip(inner).background(bg).indication(interaction, LocalIndication.current)
                        .padding(horizontal = 13.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label, style = OrbitTypography.Segment.copy(lineHeight = 16.sp), color = if (on) OrbitPalette.Ink else OrbitPalette.Ink2, maxLines = 1)
                }
            }
        }
    }
}
