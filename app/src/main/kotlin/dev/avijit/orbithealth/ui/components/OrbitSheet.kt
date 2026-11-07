package dev.avijit.orbithealth.ui.components

import android.view.View
import android.view.WindowManager
import java.util.function.Consumer
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.window.SecureFlagPolicy
import dev.avijit.orbithealth.security.LocalLockPrivacy
import dev.avijit.orbithealth.ui.theme.OrbitPalette
import dev.avijit.orbithealth.ui.theme.OrbitTypography
import dev.avijit.orbithealth.ui.theme.lineBox

private val SheetShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)

/** Haze glass behind the sheet (tokens §5 bottom bar): blur 24dp. */
private val BlurRadius = 24.dp

/**
 * Living Skies bottom sheet in the bottom bar's Haze glass (tokens §5): #141A3A at 72% over the app blurred 24dp
 * behind it, a 1dp ink 10% rim, 28dp top corners and a faint warm light along the top edge; a grab handle, an optional
 * [kicker] (eyebrow) over a Fraunces h2 [title], then [content]. A sheet is its own window, so the blur is the
 * window's blur-behind; where the system has cross-window blur off (battery saver, some GPUs) the glass falls back to
 * the bar's solid 94% fill. Swipe down, tap outside or press back to dismiss.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrbitSheet(
    title: String,
    onDismiss: () -> Unit,
    kicker: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) = OrbitSheet(AnnotatedString(title), onDismiss, kicker, content)

/** [OrbitSheet] with a styled title (e.g. "SpO₂" with a small 2). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrbitSheet(
    title: AnnotatedString,
    onDismiss: () -> Unit,
    kicker: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    // A sheet is its own window, above the app lock's overlay: never show one while locked (it comes back, as it
    // was, after unlocking). On API 31–32 its window needs its own FLAG_SECURE to stay out of recents.
    val privacy = LocalLockPrivacy.current
    if (privacy.locked) return
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        properties = ModalBottomSheetProperties(
            securePolicy = if (privacy.secureWindows) SecureFlagPolicy.SecureOn else SecureFlagPolicy.Inherit,
        ),
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = SheetShape,
        containerColor = Color.Transparent,
        contentColor = OrbitPalette.Ink,
        tonalElevation = 0.dp,
        scrimColor = OrbitPalette.Base0.copy(alpha = 0.55f),
        dragHandle = null,
    ) {
        val blurred = rememberWindowBlur()
        Column(
            Modifier
                .fillMaxWidth()
                .clip(SheetShape)
                .background(if (blurred) OrbitPalette.BarFill else OrbitPalette.BarFillOpaque)
                .background(Brush.verticalGradient(0f to OrbitPalette.Ink.copy(alpha = 0.05f), 0.25f to Color.Transparent))
                .border(1.dp, OrbitPalette.BarBorder, SheetShape)
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
        ) {
            Box(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 18.dp), contentAlignment = Alignment.Center) {
                Spacer(Modifier.size(40.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(OrbitPalette.Ink.copy(alpha = 0.30f)))
            }
            if (kicker != null) {
                Text(kicker, style = OrbitTypography.Eyebrow, modifier = Modifier.lineBox(OrbitTypography.Eyebrow))
                Spacer(Modifier.height(2.dp))
            }
            Text(title, style = OrbitTypography.H2, modifier = Modifier.semantics { heading() })
            Spacer(Modifier.height(14.dp))
            content()
        }
    }
}

/**
 * Turns on the sheet window's blur-behind (minSdk 31 has the API) and returns whether the sheet is blurred right now. The
 * system can switch cross-window blur off and on at any time (battery saver, tunneled video, developer options), so this
 * listens to it and the sheet falls back to its opaque fill while blur is off. The window is found through the sheet's
 * [DialogWindowProvider] host view.
 */
@Composable
private fun rememberWindowBlur(): Boolean {
    val view = LocalView.current
    val radiusPx = with(LocalDensity.current) { BlurRadius.roundToPx() }
    val window = remember(view) {
        generateSequence<Any>(view) { (it as? View)?.parent }.firstNotNullOfOrNull { (it as? DialogWindowProvider)?.window }
    }
    val blurred = remember(window) { mutableStateOf(window?.windowManager?.isCrossWindowBlurEnabled == true) }
    DisposableEffect(window, radiusPx) {
        if (window == null) return@DisposableEffect onDispose {}
        val wm = window.windowManager
        val flagged = runCatching {
            window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            window.attributes = window.attributes.apply { blurBehindRadius = radiusPx }
        }.isSuccess
        // Called once now with the current state, then on every change, on the main thread.
        val listener = Consumer<Boolean> { enabled -> blurred.value = flagged && enabled }
        wm.addCrossWindowBlurEnabledListener(listener)
        onDispose { wm.removeCrossWindowBlurEnabledListener(listener) }
    }
    return blurred.value
}
