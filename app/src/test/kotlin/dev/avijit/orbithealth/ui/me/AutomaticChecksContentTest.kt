package dev.avijit.orbithealth.ui.me

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dev.avijit.orbithealth.ring.MonitorKind
import dev.avijit.orbithealth.ui.sky.LocalSkyForceFallback
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(qualifiers = "w390dp-h844dp-xxhdpi")
@RunWith(RobolectricTestRunner::class)
class AutomaticChecksContentTest {
    @get:Rule val rule = createComposeRule()

    private fun row(kind: MonitorKind, title: String, interval: Int, intervals: List<Int>, ringLine: String? = null) = CheckRowUi(
        kind = kind, title = title, enabled = true, showSwitch = true, intervalMin = interval, intervals = intervals,
        showOvernight = false, overnightOnly = false, line = "Every $interval minutes, day and night", ringLine = ringLine,
    )

    private val tk5 = AutomaticChecksUiState(
        loaded = true,
        rows = listOf(
            row(MonitorKind.HEART_RATE, "Heart rate", 10, listOf(10, 15, 20, 30, 60)),
            row(MonitorKind.SPO2, "SpO₂", 30, listOf(30, 60), ringLine = "On your ring now: every hour"),
        ),
        source = ScheduleSource.LAST_SET,
        caption = "Last set from Orbit Health · 2 min ago",
    )

    private fun show(state: AutomaticChecksUiState, actions: AutomaticChecksActions = AutomaticChecksActions()) = rule.setContent {
        CompositionLocalProvider(LocalSkyForceFallback provides true) { AutomaticChecksContent(state, actions) }
    }

    @Test fun onlyAllowedIntervalsAreOfferedAndAPickSendsIt() {
        val picked = mutableListOf<Pair<MonitorKind, Int>>()
        show(tk5, AutomaticChecksActions(setInterval = { k, m -> picked += k to m }))
        // Heart rate's five, SpO₂'s two: "Every 30 minutes" and "Every hour" appear once per row that allows them.
        rule.onAllNodesWithContentDescription("Every 15 minutes").assertCountEquals(1)
        rule.onAllNodesWithContentDescription("Every 30 minutes").assertCountEquals(2)
        rule.onAllNodesWithContentDescription("Every hour").assertCountEquals(2)
        rule.onAllNodesWithContentDescription("Every 5 minutes").assertCountEquals(0)
        rule.onAllNodesWithContentDescription("Every 10 minutes")[0].assertIsSelected()
        rule.onNodeWithContentDescription("Every 15 minutes").performClick()
        assertEquals(listOf(MonitorKind.HEART_RATE to 15), picked)
    }

    @Test fun captionBatteryNoteAndWhatTheRingHas() {
        show(tk5.copy(status = ApplyStatus.Done("Your ring is now checking heart rate every 15 minutes", ok = true)))
        rule.onNodeWithText("Last set from Orbit Health · 2 min ago").assertExists()
        rule.onNodeWithText("Your ring is now checking heart rate every 15 minutes").assertExists()
        // Said once for the screen, not repeated on every card.
        rule.onAllNodesWithText(AutomaticChecksCopy.BATTERY_NOTE).assertCountEquals(1)
        rule.onNodeWithText("On your ring now: every hour").performScrollTo()
    }

    @Test fun sendingIsQuiet() {
        show(tk5.copy(status = ApplyStatus.Sending))
        rule.onNodeWithText(AutomaticChecksCopy.SENDING).assertExists()
    }

    @Test fun noRingShowsTheCalmEmptyState() {
        show(AutomaticChecksUiState(loaded = true, empty = AutomaticChecksCopy.NO_RING))
        rule.onNodeWithText("Connect your ring to set automatic checks.").assertExists()
        rule.onAllNodesWithText(AutomaticChecksCopy.BATTERY_NOTE).assertCountEquals(0)
    }
}
