package dev.avijit.orbithealth.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import dev.avijit.orbithealth.ui.theme.OrbitPalette
import dev.avijit.orbithealth.ui.theme.OrbitTypography
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.avijit.orbithealth.ui.theme.Manrope

/**
 * Honest label for values a ring can only approximate (blood pressure, stress), the Living Skies badge (tokens §5):
 * 20dp tall, Manrope 700 10sp uppercase, Good text on Good @ 8% with a Good @ 45% hairline.
 */
@Composable
fun EstimateBadge(modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier.height(20.dp).background(OrbitPalette.Good.copy(alpha = 0.08f), shape)
            .border(1.dp, OrbitPalette.Good.copy(alpha = 0.45f), shape).padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text("ESTIMATE", style = OrbitTypography.Badge.copy(lineHeight = 12.sp), color = OrbitPalette.Good)
    }
}
