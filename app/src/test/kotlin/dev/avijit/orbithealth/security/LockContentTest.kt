package dev.avijit.orbithealth.security

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithText
import dev.avijit.orbithealth.ui.sky.LocalSkyForceFallback
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** TalkBack meets one Unlock control on the lock screen: the fingerprint disc is a touch-only shortcut. */
@Config(qualifiers = "w390dp-h844dp-xxhdpi")
@RunWith(RobolectricTestRunner::class)
class LockContentTest {
    @get:Rule val rule = createComposeRule()

    @Test fun fingerprintDiscIsHiddenFromAccessibilityAndUnlockIsTheOnlyAction() {
        var unlocks = 0
        rule.setContent {
            CompositionLocalProvider(LocalSkyForceFallback provides true) {
                LockContent(LockStatus.Cancelled, onUnlock = { unlocks++ }, onOpenSecuritySettings = {}, onContinueWithoutLock = {})
            }
        }
        rule.onAllNodesWithContentDescription("fingerprint", substring = true, ignoreCase = true).assertCountEquals(0)
        rule.onAllNodes(hasClickAction()).assertCountEquals(1)
        rule.onAllNodesWithText("Unlock").assertCountEquals(1)
        rule.onNodeWithText("Unlock").performClick()
        assertEquals(1, unlocks)
    }
}
