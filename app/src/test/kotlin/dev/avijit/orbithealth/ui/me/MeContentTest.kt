package dev.avijit.orbithealth.ui.me

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import dev.avijit.orbithealth.ui.sky.LocalSkyForceFallback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Me in Living Skies: existing users can set the greeting name from a "Name" row, and the step goal row is clearly the
 * base goal (Today shows the adaptive smart goal), so the two numbers never look like a contradiction.
 */
@Config(qualifiers = "w390dp-h844dp-xxhdpi")
@RunWith(RobolectricTestRunner::class)
class MeContentTest {
    @get:Rule val rule = createComposeRule()

    private val loaded = MeUiState(loaded = true, ringName = "TK5 A1B2", ringStatus = "Firmware 1.18 · connected", batteryPct = 54, hasProfile = true)

    private fun show(state: MeUiState, actions: MeActions = MeActions()) = rule.setContent {
        CompositionLocalProvider(LocalSkyForceFallback provides true) { MeContent(state, actions) }
    }

    @Test fun nameRowOffersToAddANameAndSavesIt() {
        var saved: String? = "unset"
        show(loaded, MeActions(setName = { saved = it }))
        rule.onNodeWithText("Name").performScrollTo()
        rule.onNodeWithText("Add").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNode(hasSetTextAction()).performTextReplacement("Sam")
        // The sheet is its own window; invoke the button's click action rather than injecting a touch into it.
        rule.onNodeWithText("Save").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals("Sam", saved)
    }

    @Test fun nameRowShowsTheSavedName() {
        show(loaded.copy(name = "Sam"))
        rule.onNodeWithText("Sam").performScrollTo()
        rule.onAllNodesWithText("Add").assertCountEquals(0)
    }

    @Test fun stepGoalIsLabelledAsTheBaseGoal() {
        show(loaded.copy(stepGoal = 8000))
        rule.onNodeWithText("Base step goal").performScrollTo()
        rule.onNodeWithText("8,000", substring = true).performScrollTo()
        rule.onAllNodesWithText("Step goal").assertCountEquals(0)
    }

    @Test fun ringCardAndGroupsFollowTheMockup() {
        show(loaded)
        rule.onNodeWithText("TK5 A1B2").assertExists()
        rule.onNodeWithText("Firmware\u00A01.18 · connected").assertExists()
        rule.onNodeWithText("Sync").assertExists()
        rule.onNodeWithText("Goals & profile").performScrollTo()
        rule.onNodeWithText("Open-source licences").performScrollTo()
    }

    @Test fun ringStatusWrapsOnlyAtTheDot() {
        assertEquals("Firmware\u00A01.18 · last\u00A0synced\u00A01\u00A0hr\u00A0ago", MeMath.unbreakablePhrases("Firmware 1.18 · last synced 1 hr ago"))
    }

    @Test fun automaticChecksIsOneRowThatOpensItsScreen() {
        var opened = 0
        show(loaded.copy(autoChecks = "Heart rate every 10 min · SpO₂ every 30 min"), MeActions(openAutomaticChecks = { opened++ }))
        rule.onNodeWithText("Automatic checks", substring = true).performScrollTo().performClick()
        assertEquals(1, opened)
        // The old inline controls are gone from Me.
        rule.onAllNodesWithText("Heart rate every").assertCountEquals(0)
        rule.onAllNodesWithText("SpO₂ overnight").assertCountEquals(0)
    }

    private class FakeTools(on: Boolean = false) : DeveloperTools {
        override val useMacBridge = kotlinx.coroutines.flow.MutableStateFlow(on)
        override suspend fun setUseMacBridge(on: Boolean) { useMacBridge.value = on }
        override suspend fun seedSampleData() = "ok"
        override suspend fun reset() = Unit
    }

    private fun showWithTools(tools: DeveloperTools, revealed: Boolean = false) = rule.setContent {
        CompositionLocalProvider(LocalSkyForceFallback provides true) {
            MeContent(loaded, MeActions(), developer = tools, developerRevealed = revealed)
        }
    }

    @Test fun developerOptionsStayHiddenUntilShaken() {
        showWithTools(FakeTools())
        rule.onAllNodesWithText("Use Mac bridge").assertCountEquals(0)
    }

    @Test fun developerOptionsShowAfterAShake() {
        showWithTools(FakeTools(), revealed = true)
        rule.onNodeWithText("Use Mac bridge").performScrollTo()
    }

    @Test fun developerOptionsStayVisibleWhileTheBridgeIsOn() {
        showWithTools(FakeTools(on = true))
        rule.onNodeWithText("Use Mac bridge").performScrollTo()
    }
}
