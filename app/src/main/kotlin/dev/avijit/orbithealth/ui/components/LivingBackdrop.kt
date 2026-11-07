package dev.avijit.orbithealth.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import dev.avijit.orbithealth.ui.theme.OrbitPalette
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource

/**
 * The screen's blur source, provided by [LivingBackdrop]. The bottom bar reads it to frost what scrolls beneath it;
 * null (no backdrop above, e.g. previews) makes it fall back to its solid fill.
 */
val LocalHazeState = staticCompositionLocalOf<HazeState?> { null }

/**
 * The Living Skies app background: a full-screen vertical gradient base0 → base1 (tokens §1). It is the Haze blur source ([LocalHazeState]) for the bottom bar; screens whose content should
 * frost under the bar also mark their scrolling content with `hazeSource(LocalHazeState.current)`.
 */
@Composable
fun LivingBackdrop(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val haze = remember { HazeState() }
    Box(modifier.fillMaxSize().background(OrbitPalette.Base0)) {
        Spacer(Modifier.fillMaxSize().hazeSource(haze).background(Brush.verticalGradient(listOf(OrbitPalette.Base0, OrbitPalette.Base1))))
        CompositionLocalProvider(LocalHazeState provides haze) { content() }
    }
}
