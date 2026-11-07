package dev.avijit.orbithealth.security

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.clearAndSetSemantics

/**
 * What windows drawn by the app (sheets, dialogs) need to know about the lock: hide while [locked], and on API 31–32
 * mark their own window FLAG_SECURE when [secureWindows] (the activity's FLAG_SECURE does not cover other windows).
 */
data class LockPrivacy(val locked: Boolean = false, val secureWindows: Boolean = false)

val LocalLockPrivacy = compositionLocalOf { LockPrivacy() }

/** Re-checks that the owner is present (biometric or phone PIN) before a sensitive change, e.g. turning the lock off. */
fun interface LockAuthenticator {
    /** True once the user has authenticated; false if they cancel or fail. */
    suspend fun confirm(): Boolean
}

/** Provided by MainActivity; null (previews, tests) means sensitive changes are refused. */
val LocalLockAuthenticator = staticCompositionLocalOf<LockAuthenticator?> { null }

/**
 * Draws [content] with [lockScreen] over it while [locked]. The content stays composed (deep state survives) but is
 * not drawn, not in the semantics tree, unreachable by touch (the lock screen is the top hit target), and unreachable
 * by keyboard/D-pad: on lock its focus is cleared, traversal cannot enter it, key events inside it are consumed, and
 * focus moves to the lock screen, which traversal cannot leave.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun LockGate(locked: Boolean, lockScreen: @Composable () -> Unit, content: @Composable () -> Unit) {
    val focusManager = LocalFocusManager.current
    val overlay = remember { FocusRequester() }
    Box(
        Modifier
            .fillMaxSize()
            .focusProperties {
                canFocus = false // a group, never a focus stop itself
                enter = { if (locked) FocusRequester.Cancel else FocusRequester.Default }
            }
            .focusGroup()
            // Belt and braces: no key event is handled inside the hidden content while locked.
            .onPreviewKeyEvent { locked }
            .then(if (locked) Modifier.graphicsLayer { alpha = 0f }.clearAndSetSemantics { } else Modifier),
    ) { content() }
    if (locked) {
        Box(
            Modifier
                .fillMaxSize()
                // The top hit target everywhere: touches never reach the hidden content beneath, whatever the
                // lock screen draws.
                .pointerInput(Unit) { detectTapGestures { } }
                .focusRequester(overlay)
                .focusProperties { exit = { FocusRequester.Cancel } }
                .focusGroup(),
        ) { lockScreen() }
        LaunchedEffect(Unit) {
            focusManager.clearFocus(force = true)
            runCatching { overlay.requestFocus() }
        }
    }
}
