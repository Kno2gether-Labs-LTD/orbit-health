package dev.avijit.orbithealth.ui.components

import android.view.HapticFeedbackConstants
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dev.avijit.orbithealth.ui.nav.Routes
import dev.avijit.orbithealth.ui.sky.LocalSkyForceFallback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The haptics seam (tokens §6): each event maps to the platform constant (which honours the system's touch-feedback
 * setting), and the UI fires exactly one event per trigger: tab change, Measure start, Measure done, pager settle.
 */
@Config(qualifiers = "w390dp-h844dp-xxhdpi")
@RunWith(RobolectricTestRunner::class)
class HapticsTest {
    @get:Rule val rule = createComposeRule()

    private val fired = mutableListOf<HapticEvent>()
    private val recorder = Haptics { fired += it }

    private fun show(content: @Composable () -> Unit) = rule.setContent {
        CompositionLocalProvider(LocalHaptics provides recorder, LocalSkyForceFallback provides true) { content() }
    }

    @Test fun eventsMapToTheTokenConstants() {
        assertEquals(HapticFeedbackConstants.CONFIRM, HapticEvents.constantFor(HapticEvent.UnlockSuccess, 34))
        assertEquals(HapticFeedbackConstants.CONTEXT_CLICK, HapticEvents.constantFor(HapticEvent.MeasureStart, 34))
        assertEquals(HapticFeedbackConstants.CONFIRM, HapticEvents.constantFor(HapticEvent.MeasureDone, 31))
        assertEquals(HapticFeedbackConstants.SEGMENT_TICK, HapticEvents.constantFor(HapticEvent.TabChange, 34))
        assertEquals(HapticFeedbackConstants.SEGMENT_TICK, HapticEvents.constantFor(HapticEvent.PagerSettle, 35))
        // Before API 34 there is no SEGMENT_TICK: the text-handle tick is the nearest light tick.
        assertEquals(HapticFeedbackConstants.TEXT_HANDLE_MOVE, HapticEvents.constantFor(HapticEvent.TabChange, 33))
        assertEquals(HapticFeedbackConstants.TEXT_HANDLE_MOVE, HapticEvents.constantFor(HapticEvent.PagerSettle, 31))
    }

    @Test fun measureDoneOnlyWhenAMeasurementEndsWithAReading() {
        assertTrue(HapticEvents.measureFinished(wasMeasuring = true, measuring = false, gotReading = true, failed = false))
        assertFalse("still running", HapticEvents.measureFinished(true, true, true, false))
        assertFalse("never started", HapticEvents.measureFinished(false, false, true, false))
        assertFalse("no reading", HapticEvents.measureFinished(true, false, false, false))
        assertFalse("ring said no", HapticEvents.measureFinished(true, false, true, true))
    }

    @Test fun pagerTicksOnlyWhenTheSettledPageChanges() {
        assertFalse("first settle is the initial page", HapticEvents.pagerSettled(previous = null, current = 0))
        assertFalse(HapticEvents.pagerSettled(previous = 1, current = 1))
        assertTrue(HapticEvents.pagerSettled(previous = 0, current = 1))
    }

    @Test fun textTabsTickOnceWhenChangingTabAndNotOnTheSelectedOne() {
        show {
            var selected by remember { mutableIntStateOf(0) }
            TextTabs(listOf("Heart", "Activity"), selected, onSelect = { selected = it })
        }
        rule.onNodeWithText("Heart").performClick()
        assertEquals(emptyList<HapticEvent>(), fired)
        rule.onNodeWithText("Activity").performClick()
        assertEquals(listOf(HapticEvent.TabChange), fired)
    }

    @Test fun bottomBarTicksWhenChangingTab() {
        show {
            var route by remember { mutableStateOf(Routes.TODAY) }
            OrbitBottomBar(route, onSelect = { route = it }, hazeState = null)
        }
        rule.onNodeWithText("Today").performClick()
        assertEquals(emptyList<HapticEvent>(), fired)
        rule.onNodeWithText("Sleep").performClick()
        assertEquals(listOf(HapticEvent.TabChange), fired)
    }

    @Test fun aReadingStartsWithAContextClickAndItsResultConfirms() {
        val tiles = listOf(dev.avijit.orbithealth.ui.measure.MeasureTile(dev.avijit.orbithealth.ring.MeasureKind.HEART_RATE, 30, live = true, estimate = false))
        var state by mutableStateOf(dev.avijit.orbithealth.ui.measure.MeasureUiState(visible = true, tiles = tiles))
        show {
            dev.avijit.orbithealth.ui.measure.MeasureContent(state, onStart = {}, onAgain = {}, onDone = {}, onStop = {})
        }
        rule.waitForIdle()
        assertEquals(emptyList<HapticEvent>(), fired)
        state = state.copy(phase = dev.avijit.orbithealth.ui.measure.MeasurePhase.Measuring(dev.avijit.orbithealth.ring.MeasureKind.HEART_RATE, 30, started = true))
        rule.waitForIdle()
        assertEquals(listOf(HapticEvent.MeasureStart), fired)
        val hr = mapOf(dev.avijit.orbithealth.ring.Metric.HEART_RATE to 72.0)
        state = state.copy(phase = dev.avijit.orbithealth.ui.measure.MeasurePhase.Measuring(dev.avijit.orbithealth.ring.MeasureKind.HEART_RATE, 30, started = true, values = hr))
        rule.waitForIdle()
        assertEquals("live values are not a new start", listOf(HapticEvent.MeasureStart), fired)
        state = state.copy(
            phase = dev.avijit.orbithealth.ui.measure.MeasurePhase.Done(
                dev.avijit.orbithealth.ring.MeasureKind.HEART_RATE, hr, "Within your usual range.", java.time.Instant.parse("2026-11-06T09:00:00Z"),
            ),
        )
        rule.waitForIdle()
        assertEquals(listOf(HapticEvent.MeasureStart, HapticEvent.MeasureDone), fired)
    }
}
