package dev.avijit.orbithealth.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.avijit.orbithealth.ui.theme.OrbitPalette

/**
 * Tokens §5 icon button: 36Ø (40Ø on detail headers), ink 8% fill, ink 14% border, 18dp glyph. The visual is [size];
 * the layout and touch target are at least 48dp ([OrbitIconButtonDefaults.TouchTarget]), with the circle centred, so
 * callers place it by its centre (pad by `(48 - size) / 2` less than the mockup's edge offsets).
 */
@Composable
fun OrbitIconButton(symbol: OrbitSymbol, onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 36.dp, contentDescription: String? = null) {
    Box(
        modifier
            // Clickable (and its semantics node) outside the minimum size, so the whole 48dp target is the button.
            .clickable(role = Role.Button, onClick = onClick)
            .then(if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier)
            .minimumInteractiveComponentSize(),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(size)
                .clip(CircleShape)
                .background(OrbitPalette.Ink.copy(alpha = 0.08f))
                .border(1.dp, OrbitPalette.Ink.copy(alpha = 0.14f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Symbol(symbol, size = if (size >= 40.dp) 20.dp else 18.dp, strokeWidth = 1.6f)
        }
    }
}

object OrbitIconButtonDefaults {
    val TouchTarget = 48.dp

    /** How far the touch target reaches past a [size] circle on each side (the padding callers give up). */
    fun spill(size: Dp): Dp = ((TouchTarget - size) / 2).coerceAtLeast(0.dp)
}
