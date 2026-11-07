package dev.avijit.orbithealth.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import dev.avijit.orbithealth.ui.theme.OrbitPalette
import kotlin.math.ceil

object TintedCardDefaults {
    /** Metric hue `a` at 14% over the surface (tokens §1). */
    const val Amount = 0.14f
    /** Neutral cards: ink at 5%. */
    const val NeutralAmount = 0.05f
    /** Ask chips: #9C86FF at 10%. */
    const val ChipAmount = 0.10f

    val Shape = RoundedCornerShape(24.dp)
    val Padding = PaddingValues(horizontal = 20.dp, vertical = 18.dp)
    /** Mini cards (Today's Heart / HRV / SpO₂ row): radius 20, padding 14/14/13. */
    val MiniShape = RoundedCornerShape(20.dp)
    val MiniPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 13.dp)
}

/**
 * A card fill: [hue] mixed into [OrbitPalette.Surface] by [amount] in sRGB, exactly CSS `color-mix(in srgb, hue N%,
 * surface)`, so the card is a solid colour (no alpha over the background).
 */
fun cardTint(hue: Color, amount: Float = TintedCardDefaults.Amount): Color {
    val s = OrbitPalette.Surface
    // 8-bit channels, exact halves rounded down as Chrome's color-mix lands them (stress: 68.5 -> 0x44).
    fun mix(a: Float, b: Float): Int = ceil((a + (b - a) * amount) * 255f - 0.5f - 1e-3f).toInt().coerceIn(0, 255)
    return Color(mix(s.red, hue.red), mix(s.green, hue.green), mix(s.blue, hue.blue))
}

/**
 * The Living Skies secondary surface: a solid tint of the metric [hue], 24dp corners and a 1dp warm hairline, no
 * elevation (depth comes from tint, hairline and sky). Use `amount = TintedCardDefaults.NeutralAmount` with
 * [OrbitPalette.Ink] for neutral cards, and the Mini shape/padding for the three-up row.
 */
@Composable
fun TintedCard(
    hue: Color,
    modifier: Modifier = Modifier,
    amount: Float = TintedCardDefaults.Amount,
    shape: Shape = TintedCardDefaults.Shape,
    contentPadding: PaddingValues = TintedCardDefaults.Padding,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .clip(shape)
            .background(cardTint(hue, amount))
            .border(1.dp, OrbitPalette.HairlineWarm, shape)
            .padding(contentPadding),
        content = content,
    )
}
