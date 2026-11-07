package dev.avijit.orbithealth.security

import android.app.KeyguardManager
import android.content.Intent
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.BackHandler
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.invisibleToUser
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import dev.avijit.orbithealth.ui.components.BrandMark
import dev.avijit.orbithealth.ui.components.GhostButton
import dev.avijit.orbithealth.ui.components.HapticEvent
import dev.avijit.orbithealth.ui.components.Haptics
import dev.avijit.orbithealth.ui.components.OrbitSymbol
import dev.avijit.orbithealth.ui.components.Symbol
import dev.avijit.orbithealth.ui.components.ViewHaptics
import dev.avijit.orbithealth.ui.components.WarmButton
import dev.avijit.orbithealth.ui.components.livingEnter
import dev.avijit.orbithealth.ui.onboarding.BrandTitle
import dev.avijit.orbithealth.ui.onboarding.Footer
import dev.avijit.orbithealth.ui.onboarding.OnboardingCard
import dev.avijit.orbithealth.ui.onboarding.OnboardingGutter
import dev.avijit.orbithealth.ui.sky.Sky
import dev.avijit.orbithealth.ui.sky.SkyKind
import dev.avijit.orbithealth.ui.theme.OrbitPalette
import dev.avijit.orbithealth.ui.theme.OrbitTypography
import dev.avijit.orbithealth.ui.theme.lineBox

/** What the lock screen shows under the logo. */
enum class LockStatus {
    /** The system prompt is up (or about to be). */
    Prompting,
    /** The user dismissed the prompt: wait for "Unlock". */
    Cancelled,
    /** Too many failed biometric attempts: suggest the phone PIN. */
    LockedOut,
    /** The phone has no screen lock, so there is nothing to check against. */
    NoScreenLock,
    /** Any other prompt error; "Unlock" retries. */
    Failed,
}

/**
 * Maps a BiometricPrompt error to what the lock screen shows. ERROR_CANCELED is the system dismissing the prompt
 * (e.g. the app paused), so it stays [LockStatus.Prompting] and prompts again on resume; only the user cancelling
 * waits for "Unlock". Android 14 reports the prompt being dismissed because the app went to the background as
 * ERROR_USER_CANCELED (seen on orbit34: "Dismissed! Reason: 3, Error: 10"), so a cancel that arrives while the
 * activity is no longer [resumed] counts as a system cancel too.
 */
fun lockStatusFor(errorCode: Int, deviceSecure: Boolean, resumed: Boolean = true): LockStatus = when (errorCode) {
    BiometricPrompt.ERROR_CANCELED -> LockStatus.Prompting
    BiometricPrompt.ERROR_USER_CANCELED, BiometricPrompt.ERROR_NEGATIVE_BUTTON ->
        if (resumed) LockStatus.Cancelled else LockStatus.Prompting
    BiometricPrompt.ERROR_LOCKOUT, BiometricPrompt.ERROR_LOCKOUT_PERMANENT -> LockStatus.LockedOut
    BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL -> LockStatus.NoScreenLock
    else -> if (deviceSecure) LockStatus.Failed else LockStatus.NoScreenLock
}

/**
 * Drives the system BiometricPrompt for [AppLock], and [confirm]s the owner before turning the lock off. Built in the activity's onCreate (BiometricPrompt must be created
 * there to survive configuration changes). Strong biometrics or the phone's PIN/pattern/password both unlock.
 */
class BiometricUnlocker(
    private val activity: FragmentActivity,
    private val lock: AppLock,
    /** Unlock success confirms with a tap (tokens §6); the system's touch-feedback setting decides if it plays. */
    private val haptics: Haptics = ViewHaptics(activity.window.decorView),
) : LockAuthenticator {
    var status by mutableStateOf(LockStatus.Prompting)
        private set
    private var showing = false
    /** Set while the prompt is up for [confirm] rather than for unlocking. */
    private var pendingConfirm: CancellableContinuation<Boolean>? = null

    private val prompt = BiometricPrompt(
        activity,
        ContextCompat.getMainExecutor(activity),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                showing = false
                pendingConfirm?.let { pendingConfirm = null; it.resume(true); return }
                status = LockStatus.Prompting // ready for the next time the app locks
                lock.unlocked()
                haptics.perform(HapticEvent.UnlockSuccess)
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                showing = false
                pendingConfirm?.let { pendingConfirm = null; it.resume(false); return }
                val resumed = activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                status = lockStatusFor(errorCode, deviceSecure(), resumed)
            }
            // onAuthenticationFailed (a finger that does not match) keeps the prompt up; nothing to do.
        },
    )

    private val info = BiometricPrompt.PromptInfo.Builder()
        .setTitle("Unlock Orbit Health")
        .setSubtitle("Your health data stays on this phone")
        // DEVICE_CREDENTIAL: the phone PIN/pattern/password is always offered, so no negative button.
        .setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
        .build()

    private val confirmInfo = BiometricPrompt.PromptInfo.Builder()
        .setTitle("Turn off app lock")
        .setSubtitle("Confirm it's you")
        .setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
        .build()

    /**
     * Asks for the same biometric or phone PIN before a sensitive change (turning the lock off). With no screen lock
     * on the phone there is nothing to check against and the lock cannot work anyway, so the change is allowed.
     */
    override suspend fun confirm(): Boolean {
        if (!deviceSecure()) return true
        if (showing) return false
        return suspendCancellableCoroutine { c ->
            pendingConfirm = c
            showing = true
            c.invokeOnCancellation {
                activity.runOnUiThread {
                    if (pendingConfirm === c) {
                        pendingConfirm = null
                        showing = false
                        runCatching { prompt.cancelAuthentication() }
                    }
                }
            }
            runCatching { prompt.authenticate(confirmInfo) }.onFailure {
                pendingConfirm = null
                showing = false
                c.resume(false)
            }
        }
    }

    fun deviceSecure(): Boolean = activity.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true

    /** Shows the system prompt, or the "no screen lock" explanation when the phone has none. */
    fun authenticate() {
        if (showing) return
        if (!deviceSecure()) { status = LockStatus.NoScreenLock; return }
        status = LockStatus.Prompting
        showing = true
        runCatching { prompt.authenticate(info) }.onFailure { showing = false; status = LockStatus.Failed }
    }

    /** On resume while locked: prompt automatically unless the user just dismissed it (then they tap "Unlock"). */
    fun onResumedWhileLocked() {
        when (status) {
            LockStatus.Prompting -> authenticate()
            // Back from Settings with a screen lock now set: go straight to the prompt.
            LockStatus.NoScreenLock -> if (deviceSecure()) authenticate()
            else -> Unit
        }
    }

    fun openSecuritySettings() {
        runCatching { activity.startActivity(Intent(AndroidSettings.ACTION_SECURITY_SETTINGS)) }
    }

    fun continueWithoutLock() {
        status = LockStatus.Prompting
        lock.continueWithoutLock()
    }
}

/** The lock overlay wired to [unlocker]: prompts on resume, swallows Back (it would pop the hidden screens). */
@Composable
fun LockScreen(unlocker: BiometricUnlocker, onBack: () -> Unit) {
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { unlocker.onResumedWhileLocked() }
    BackHandler(onBack = onBack)
    LockContent(
        status = unlocker.status,
        onUnlock = unlocker::authenticate,
        onOpenSecuritySettings = unlocker::openSecuritySettings,
        onContinueWithoutLock = unlocker::continueWithoutLock,
    )
}

/**
 * Living Skies lock (screen-02): a full-screen Night sky with 60 slow stars, the 104dp mark, "Orbit *Health*" and a
 * calm line; at the bottom the fingerprint disc (tapping it also unlocks), the warm Unlock button and how to unlock.
 * When there is something to explain, a card takes the disc's place. [LockGate] keeps the app behind it unreachable.
 * Stateless so it can be screenshotted and previewed.
 */
@Composable
fun LockContent(
    status: LockStatus,
    onUnlock: () -> Unit,
    onOpenSecuritySettings: () -> Unit,
    onContinueWithoutLock: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxSize().background(OrbitPalette.Base0)) {
        Sky(SkyKind.NIGHT, Modifier.fillMaxSize(), stars = 60, starSeed = 23)
        val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        Column(
            // At least the screen's height, so the weighted gap pins the unlock block to the bottom.
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight).padding(horizontal = OnboardingGutter),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Mockup: the mark 176dp under the 36dp status bar.
            Spacer(Modifier.height(statusTop + 176.dp))
            BrandMark(104.dp, Modifier.livingEnter(0))
            Text(
                BrandTitle, style = OrbitTypography.Title, textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 28.dp).lineBox(OrbitTypography.Title).livingEnter(60).semantics { heading() },
            )
            Text(
                "Locked to keep your data private.",
                style = OrbitTypography.Lead.copy(color = OrbitPalette.InkSky2), textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp).livingEnter(120),
            )
            Spacer(Modifier.weight(1f).heightIn(min = 32.dp))
            AnimatedContent(status, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "lockStatus") { s ->
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    val message = message(s)
                    if (message != null) {
                        LockCard(message.first, message.second)
                    } else {
                        FingerprintDisc(onUnlock)
                    }
                    Spacer(Modifier.height(28.dp))
                    if (s == LockStatus.NoScreenLock) {
                        WarmButton("Open security settings", onClick = onOpenSecuritySettings, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(10.dp))
                        GhostButton("Continue without lock", onClick = onContinueWithoutLock, modifier = Modifier.fillMaxWidth())
                    } else {
                        WarmButton("Unlock", onClick = onUnlock, modifier = Modifier.fillMaxWidth().livingEnter(180))
                    }
                }
            }
            Footer(
                if (status == LockStatus.NoScreenLock) "App lock stays on and works once you set one." else "Fingerprint, face or your phone PIN",
                color = OrbitPalette.InkSky2,
            )
            // Mockup: the unlock block ends 58dp above the screen bottom.
            Spacer(Modifier.height(max(58.dp, navBottom + 16.dp)))
        }
    }
}

/**
 * The mockup's 72Ø fingerprint disc: ink 6% fill, ink 22% rim, a 34dp finger glyph at stroke 1.4. A touch shortcut only:
 * hidden from TalkBack (it would be a second "Unlock" right above the real one), the Unlock button is the accessible path.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun FingerprintDisc(onUnlock: () -> Unit) {
    Box(
        Modifier
            .size(72.dp)
            .clip(CircleShape)
            .background(OrbitPalette.Ink.copy(alpha = 0.06f))
            .border(1.dp, OrbitPalette.Ink.copy(alpha = 0.22f), CircleShape)
            // Clears the click semantics below too, so the disc is not a focusable accessibility node.
            .clearAndSetSemantics { invisibleToUser() }
            .clickable(onClick = onUnlock),
        contentAlignment = Alignment.Center,
    ) {
        Symbol(OrbitSymbol.Finger, size = 34.dp, strokeWidth = 1.4f)
    }
}

private fun message(s: LockStatus): Pair<String, String>? = when (s) {
    LockStatus.Prompting, LockStatus.Cancelled -> null
    LockStatus.LockedOut -> "Too many attempts" to "Fingerprint unlock is paused for now. Tap Unlock and use your phone PIN instead."
    LockStatus.NoScreenLock -> "No screen lock" to "Set a screen lock in Settings to protect your health data."
    LockStatus.Failed -> "Couldn't unlock" to "Tap Unlock to try again, or use your phone PIN."
}

@Composable
private fun LockCard(title: String, body: String) {
    OnboardingCard {
        Text(title, style = OrbitTypography.BodyStrong)
        Text(body, style = OrbitTypography.Meta, modifier = Modifier.padding(top = 4.dp))
    }
}
