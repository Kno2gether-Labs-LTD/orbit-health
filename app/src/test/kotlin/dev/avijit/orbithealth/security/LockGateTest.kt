package dev.avijit.orbithealth.security

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
class LockGateTest {
    @get:Rule val rule = createComposeRule()

    private val locked = mutableStateOf(false)
    private val behindFocused = BooleanArray(3)
    private var behindClicks = 0
    private var behindKeys = 0
    private val firstBehind = FocusRequester()
    private lateinit var focus: FocusManager

    private fun setUp() {
        rule.setContent {
            focus = LocalFocusManager.current
            LockGate(
                locked = locked.value,
                lockScreen = {
                    Box(Modifier.fillMaxSize()) {
                        Text("Unlock", Modifier.testTag("unlock").clickable { })
                    }
                },
            ) {
                Column(Modifier.onKeyEvent { behindKeys++; false }) {
                    repeat(3) { i ->
                        Text(
                            "Secret $i",
                            Modifier.fillMaxWidth().height(80.dp)
                                .then(if (i == 0) Modifier.focusRequester(firstBehind) else Modifier)
                                .onFocusChanged { behindFocused[i] = it.isFocused }
                                .clickable { behindClicks++ },
                        )
                    }
                }
            }
        }
    }

    @Test fun lockingMovesFocusToOverlayAndTraversalNeverReachesHiddenContent() {
        setUp()
        rule.runOnIdle { firstBehind.requestFocus() }
        rule.runOnIdle { assertTrue(behindFocused[0]) }

        locked.value = true
        rule.waitForIdle()
        assertFalse("hidden content keeps focus", behindFocused.any { it })
        rule.onNodeWithTag("unlock").assertIsFocused()

        listOf(FocusDirection.Next, FocusDirection.Down, FocusDirection.Previous, FocusDirection.Up, FocusDirection.Next)
            .forEach { dir ->
                rule.runOnIdle { focus.moveFocus(dir) }
                rule.runOnIdle { assertFalse("focus reached hidden content via $dir", behindFocused.any { it }) }
            }
        rule.onNodeWithTag("unlock").assertIsFocused()

        // Hardware keyboard / D-pad: Tab, Shift+Tab and arrows never reach the hidden content, nor do other keys.
        val keys = listOf(Key.Tab, Key.DirectionDown, Key.DirectionUp, Key.DirectionLeft, Key.DirectionRight, Key.Enter, Key.A)
        rule.onNodeWithTag("unlock").performKeyInput {
            keys.forEach { pressKey(it) }
            withKeyDown(Key.ShiftLeft) { pressKey(Key.Tab) }
        }
        rule.runOnIdle {
            assertFalse("keyboard focus reached hidden content", behindFocused.any { it })
            assertEquals("key events reached hidden content", 0, behindKeys)
        }
        rule.onNodeWithTag("unlock").assertIsFocused()
    }

    @Test fun keysReachContentAgainAfterUnlock() {
        setUp()
        locked.value = true
        rule.waitForIdle()
        locked.value = false
        rule.waitForIdle()
        rule.runOnIdle { firstBehind.requestFocus() }
        rule.onRoot().performKeyInput { pressKey(Key.A) }
        rule.runOnIdle {
            assertTrue(behindFocused[0])
            assertTrue(behindKeys > 0)
        }
    }

    @Test fun hiddenContentIsNotClickableOrAnnouncedWhileLocked() {
        setUp()
        locked.value = true
        rule.waitForIdle()
        rule.onAllNodesWithText("Secret", substring = true).assertCountEquals(0) // not in the semantics tree
        rule.onAllNodes(hasClickAction()).assertCountEquals(1) // only the overlay's Unlock
        rule.onRoot().performTouchInput { click(centerLeft.copy(x = 40f, y = 40f)) }
        rule.runOnIdle { assertEquals(0, behindClicks) }

        locked.value = false
        rule.waitForIdle()
        rule.onAllNodesWithText("Secret", substring = true).assertCountEquals(3)
    }
}
