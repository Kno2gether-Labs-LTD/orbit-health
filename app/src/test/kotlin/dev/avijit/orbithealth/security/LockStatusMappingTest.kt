package dev.avijit.orbithealth.security

import androidx.biometric.BiometricPrompt
import org.junit.Assert.assertEquals
import org.junit.Test

class LockStatusMappingTest {
    @Test fun systemCancelRepromptsOnResume() {
        // ERROR_CANCELED is the system dismissing the prompt (e.g. the app paused): prompt again on resume.
        assertEquals(LockStatus.Prompting, lockStatusFor(BiometricPrompt.ERROR_CANCELED, deviceSecure = true))
    }

    @Test fun onlyTheUserCancelling_waitsForUnlock() {
        assertEquals(LockStatus.Cancelled, lockStatusFor(BiometricPrompt.ERROR_USER_CANCELED, deviceSecure = true))
        assertEquals(LockStatus.Cancelled, lockStatusFor(BiometricPrompt.ERROR_NEGATIVE_BUTTON, deviceSecure = true))
    }

    @Test fun cancelWhileAppIsLeavingIsASystemCancel() {
        // Android 14 reports "dismissed because the app went to the background" as USER_CANCELED.
        assertEquals(LockStatus.Prompting, lockStatusFor(BiometricPrompt.ERROR_USER_CANCELED, deviceSecure = true, resumed = false))
        assertEquals(LockStatus.Prompting, lockStatusFor(BiometricPrompt.ERROR_CANCELED, deviceSecure = true, resumed = false))
    }

    @Test fun lockoutSuggestsPin() {
        assertEquals(LockStatus.LockedOut, lockStatusFor(BiometricPrompt.ERROR_LOCKOUT, deviceSecure = true))
        assertEquals(LockStatus.LockedOut, lockStatusFor(BiometricPrompt.ERROR_LOCKOUT_PERMANENT, deviceSecure = true))
    }

    @Test fun noCredentialShowsExplanation() {
        assertEquals(LockStatus.NoScreenLock, lockStatusFor(BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL, deviceSecure = true))
        assertEquals(LockStatus.NoScreenLock, lockStatusFor(BiometricPrompt.ERROR_HW_UNAVAILABLE, deviceSecure = false))
        assertEquals(LockStatus.Failed, lockStatusFor(BiometricPrompt.ERROR_HW_UNAVAILABLE, deviceSecure = true))
    }
}
