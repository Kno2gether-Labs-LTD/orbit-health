package dev.avijit.orbithealth.security

import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import dev.avijit.orbithealth.ui.components.OrbitSheet
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Sheets are separate windows above the lock overlay: they must vanish while locked and come back after. */
@RunWith(RobolectricTestRunner::class)
class SheetLockTest {
    @get:Rule val rule = createComposeRule()

    @Test fun openSheetHidesWhileLockedAndReturnsAfterUnlock() {
        val locked = mutableStateOf(false)
        rule.setContent {
            CompositionLocalProvider(LocalLockPrivacy provides LockPrivacy(locked = locked.value)) {
                OrbitSheet(title = "About you", onDismiss = {}) { Text("Height 175 cm") }
            }
        }
        rule.onAllNodesWithText("Height 175 cm").assertCountEquals(1)

        locked.value = true
        rule.waitForIdle()
        rule.onAllNodesWithText("Height 175 cm").assertCountEquals(0)
        rule.onAllNodesWithText("About you").assertCountEquals(0)

        locked.value = false
        rule.waitForIdle()
        rule.onAllNodesWithText("Height 175 cm").assertCountEquals(1)
    }
}
