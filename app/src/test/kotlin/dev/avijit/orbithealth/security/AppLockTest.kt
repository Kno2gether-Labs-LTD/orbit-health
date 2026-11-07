package dev.avijit.orbithealth.security

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AppLockTest {
    /** A monotonic clock (like SystemClock.elapsedRealtime) the test moves by hand. */
    private class FakeElapsed(var nowMs: Long = 5_000_000L) {
        fun advance(seconds: Long) { nowMs += seconds * 1000 }
    }

    private val clock = FakeElapsed()
    private val prefs = MutableStateFlow(LockPrefs(enabled = true, lockAfterSec = 60, onboarded = true))

    private fun TestScope.lock(): AppLock = AppLock({ clock.nowMs }, prefs, backgroundScope).also { runCurrent() }

    @Test fun locksOnColdStartWhenEnabled() = runTest {
        val lock = lock()
        lock.onForegrounded() // ProcessLifecycleOwner's first ON_START
        assertTrue(lock.locked.value)
        lock.unlocked()
        assertFalse(lock.locked.value)
    }

    @Test fun staysUnlockedIfBackgroundedUnder60s() = runTest {
        val lock = lock()
        lock.unlocked()
        lock.onBackgrounded()
        clock.advance(59)
        lock.onForegrounded()
        assertFalse(lock.locked.value)
    }

    @Test fun locksAfter60sInBackground() = runTest {
        val lock = lock()
        lock.unlocked()
        lock.onBackgrounded()
        clock.advance(60)
        lock.onForegrounded()
        assertTrue(lock.locked.value)
    }

    @Test fun disabledNeverLocks() = runTest {
        prefs.value = prefs.value.copy(enabled = false)
        val lock = lock()
        assertFalse(lock.locked.value)
        lock.onBackgrounded()
        clock.advance(3600)
        lock.onForegrounded()
        assertFalse(lock.locked.value)
    }

    @Test fun neverLocksBeforeOnboardingAndFinishingOnboardingCountsAsUnlock() = runTest {
        prefs.value = prefs.value.copy(onboarded = false)
        val lock = lock()
        assertFalse(lock.locked.value) // Welcome is never behind the lock
        prefs.value = prefs.value.copy(onboarded = true)
        runCurrent()
        assertFalse(lock.locked.value) // the user just set it up
        lock.onBackgrounded()
        clock.advance(61)
        lock.onForegrounded()
        assertTrue(lock.locked.value) // from now on it locks as usual
    }

    @Test fun turningLockOnDoesNotLockAtOnceAndTurningItOffUnlocks() = runTest {
        prefs.value = prefs.value.copy(enabled = false)
        val lock = lock()
        prefs.value = prefs.value.copy(enabled = true)
        runCurrent()
        assertFalse(lock.locked.value)
        lock.onBackgrounded()
        clock.advance(120)
        lock.onForegrounded()
        assertTrue(lock.locked.value)
        prefs.value = prefs.value.copy(enabled = false)
        runCurrent()
        assertFalse(lock.locked.value)
    }

    @Test fun usesConfiguredDelay() = runTest {
        prefs.value = prefs.value.copy(lockAfterSec = 300)
        val lock = lock()
        lock.unlocked()
        lock.onBackgrounded()
        clock.advance(200)
        lock.onForegrounded()
        assertFalse(lock.locked.value)
    }

    @Test fun continueWithoutLockLetsUserInForTheSession() = runTest {
        val lock = lock()
        lock.continueWithoutLock()
        assertFalse(lock.locked.value)
        lock.onBackgrounded()
        clock.advance(600)
        lock.onForegrounded()
        assertFalse(lock.locked.value)
    }

    @Test fun backwardsClockStillLocks() = runTest {
        val lock = lock()
        lock.unlocked()
        lock.onBackgrounded()
        clock.nowMs -= 30_000 // impossible for a monotonic source: treat as "lock"
        lock.onForegrounded()
        assertTrue(lock.locked.value)
    }
}
