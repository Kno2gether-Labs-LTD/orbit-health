package dev.avijit.orbithealth.security

import android.os.SystemClock
import dev.avijit.orbithealth.prefs.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** The settings the lock depends on. [onboarded] = a ring has been saved (onboarding is never behind the lock). */
data class LockPrefs(val enabled: Boolean, val lockAfterSec: Int, val onboarded: Boolean)

/**
 * Local app lock, in case the phone is lost or shared. No account, no login: unlocking is the phone's own biometric
 * or screen-lock check (see [LockScreen]).
 *
 * The lock gates the UI only. The database key is NOT bound to user authentication: it stays usable while the app is
 * locked so background sync keeps storing ring data with the screen off (the AndroidKeyStore wrapping key in
 * [dev.avijit.orbithealth.data.DbKey] has no setUserAuthenticationRequired). The tradeoff, which the user was told
 * about: the lock stops someone opening the app on a lost or shared phone; it is not extra encryption of the data.
 *
 * Rules:
 * - Cold start of an onboarded app with the lock on starts locked (fail closed until the settings are read).
 * - Back from the background after at least `lockAfterSec` seconds: locked again; shorter trips do nothing.
 * - Settings changes never lock: switching the lock on applies from the next trip to the background, and finishing
 *   onboarding counts as an unlock (the user just set it up). Switching it off, or resetting, unlocks.
 * - [continueWithoutLock] (the phone has no screen lock) lets the user in for the rest of this process; it does not
 *   change the setting.
 *
 * [onBackgrounded] / [onForegrounded] are driven by ProcessLifecycleOwner in [dev.avijit.orbithealth.AppGraph].
 * Time away is measured on a monotonic clock ([elapsedMs], SystemClock.elapsedRealtime in the app), so changing the
 * wall clock cannot skip the lock; a negative duration (impossible for a monotonic source) is treated as "lock".
 */
class AppLock(private val elapsedMs: () -> Long, prefs: Flow<LockPrefs>, scope: CoroutineScope) {
    constructor(settings: Settings, scope: CoroutineScope, elapsedMs: () -> Long = SystemClock::elapsedRealtime) : this(
        elapsedMs,
        settings.values.map { LockPrefs(it.appLockEnabled, it.lockAfterSec, onboarded = it.ringAddress != null) },
        scope,
    )

    private val _locked = MutableStateFlow(true)
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    private val ready = MutableStateFlow(false)
    @Volatile private var current: LockPrefs? = null
    @Volatile private var backgroundedAtMs: Long? = null
    @Volatile private var sessionBypass = false

    init {
        scope.launch {
            prefs.distinctUntilChanged().collect { p ->
                current = p
                // The initial `true` is the cold-start lock; settings changes can only unlock, never lock.
                if (!p.enabled || !p.onboarded) _locked.value = false
                ready.value = true
            }
        }
    }

    /** Suspends until the settings have been read once, so callers can avoid flashing the lock screen when it is off. */
    suspend fun awaitReady() { ready.first { it } }

    @Synchronized fun onBackgrounded() {
        backgroundedAtMs = elapsedMs()
    }

    @Synchronized fun onForegrounded() {
        val since = backgroundedAtMs ?: return
        backgroundedAtMs = null
        val p = current ?: return
        if (!p.enabled || !p.onboarded || sessionBypass) return
        val awayMs = elapsedMs() - since
        if (awayMs < 0 || awayMs >= p.lockAfterSec * 1000L) _locked.value = true
    }

    /** The user authenticated (or just finished onboarding). */
    @Synchronized fun unlocked() {
        backgroundedAtMs = null
        _locked.value = false
    }

    /** The phone has no screen lock and the user chose to go on: let them in for this session only. */
    @Synchronized fun continueWithoutLock() {
        sessionBypass = true
        unlocked()
    }
}
