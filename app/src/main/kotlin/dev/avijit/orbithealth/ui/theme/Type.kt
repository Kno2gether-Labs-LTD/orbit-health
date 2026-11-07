package dev.avijit.orbithealth.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle

/**
 * Material roles in Manrope, so stock components (Text's default style, dialogs, the system's text selection UI)
 * share the Living Skies voice. Screens use the token roles in [OrbitTypography]; these only cover what Material
 * draws on its own.
 */
val OrbitMaterialTypography: Typography = Typography().run {
    fun TextStyle.manrope() = copy(fontFamily = Manrope, color = OrbitPalette.Ink)
    copy(
        displayLarge = displayLarge.manrope(), displayMedium = displayMedium.manrope(), displaySmall = displaySmall.manrope(),
        headlineLarge = headlineLarge.manrope(), headlineMedium = headlineMedium.manrope(), headlineSmall = headlineSmall.manrope(),
        titleLarge = titleLarge.manrope(), titleMedium = titleMedium.manrope(), titleSmall = titleSmall.manrope(),
        bodyLarge = bodyLarge.manrope(), bodyMedium = OrbitTypography.Body, bodySmall = bodySmall.manrope(),
        labelLarge = labelLarge.manrope(), labelMedium = labelMedium.manrope(), labelSmall = labelSmall.manrope(),
    )
}
