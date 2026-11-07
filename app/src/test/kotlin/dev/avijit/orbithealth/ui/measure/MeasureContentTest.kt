package dev.avijit.orbithealth.ui.measure

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.avijit.orbithealth.ring.MeasureEvent
import dev.avijit.orbithealth.ring.MeasureFailure
import dev.avijit.orbithealth.ring.MeasureKind
import dev.avijit.orbithealth.ring.Metric
import dev.avijit.orbithealth.ui.sky.LocalSkyForceFallback
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(qualifiers = "w390dp-h844dp-xxhdpi")
@RunWith(RobolectricTestRunner::class)
class MeasureContentTest {
    @get:Rule val rule = createComposeRule()

    private fun show(content: @Composable () -> Unit) = rule.setContent {
        CompositionLocalProvider(LocalSkyForceFallback provides true) { content() }
    }

    private val hrSpo2 = listOf(
        MeasureTile(MeasureKind.HEART_RATE, 30, live = true, estimate = false),
        MeasureTile(MeasureKind.SPO2, 45, live = true, estimate = false),
    )

    @Test fun pickerShowsOnlyTheRingsTiles() {
        val started = mutableListOf<MeasureKind>()
        show { MeasureContent(MeasureUiState(visible = true, tiles = hrSpo2), onStart = { started += it }, onAgain = {}, onDone = {}, onStop = {}) }
        rule.onNodeWithContentDescription("Heart rate, about 30 seconds").performClick()
        rule.onNodeWithContentDescription("Blood oxygen, about 45 seconds").assertExists()
        rule.onAllNodesWithContentDescription("Blood pressure", substring = true).assertCountEquals(0)
        rule.onAllNodesWithContentDescription("Stress", substring = true).assertCountEquals(0)
        rule.onAllNodesWithText("ESTIMATE").assertCountEquals(0)
        assertEquals(listOf(MeasureKind.HEART_RATE), started)
    }

    @Test fun bpResultShowsEstimateAndSysDia() {
        val tiles = hrSpo2 + MeasureTile(MeasureKind.BLOOD_PRESSURE, 50, live = true, estimate = true)
        val bp = mapOf(Metric.BLOOD_PRESSURE_SYS to 117.0, Metric.BLOOD_PRESSURE_DIA to 78.0, Metric.HEART_RATE to 88.0)
        val phase = MeasurePhase.Done(MeasureKind.BLOOD_PRESSURE, bp, MeasureCopy.WITHIN, Instant.parse("2026-11-06T14:02:00Z"))
        var again = 0
        show {
            MeasureContent(
                MeasureUiState(visible = true, tiles = tiles, phase = phase), onStart = {}, onAgain = { again++ }, onDone = {}, onStop = {},
                zone = ZoneOffset.UTC,
            )
        }
        rule.onNodeWithContentDescription("117/78 millimetres of mercury").assertExists()
        rule.onNodeWithText("mmHg").assertExists()
        rule.onNodeWithText("ESTIMATE").assertExists()
        rule.onNodeWithText("MEASURED · 14:02").assertExists()
        rule.onNodeWithText(MeasureCopy.WITHIN).assertExists()
        rule.onNodeWithText("Heart rate 88 bpm", substring = true).assertExists()
        rule.onNodeWithText("Done").assertExists()
        rule.onNodeWithText("Measure again").performClick()
        assertEquals(1, again)
    }

    @Test fun measuringSaysKeepStillAndStressReadsCalmly() {
        val tiles = hrSpo2 + MeasureTile(MeasureKind.STRESS, 60, live = false, estimate = true)
        val st = mutableStateOf(MeasureUiState(visible = true, tiles = tiles, phase = MeasurePhase.Measuring(MeasureKind.STRESS, 60, started = true, elapsedSeconds = 12)))
        show { MeasureContent(st.value, onStart = {}, onAgain = {}, onDone = {}, onStop = {}) }
        rule.onNodeWithText(MeasureCopy.KEEP_STILL).assertExists()
        rule.onNodeWithText(MeasureCopy.READING).assertExists()
        rule.onNodeWithText("48 s left").assertExists()
        rule.onNodeWithText("ESTIMATE").assertExists()
        // The other readings stay one tap away.
        rule.onNodeWithContentDescription("Heart rate, about 30 seconds").assertExists()
        st.value = st.value.copy(phase = MeasurePhase.Failed(MeasureKind.STRESS, MeasureFailure.TIMEOUT))
        rule.onNodeWithText("Couldn't get a reading. Is the ring snug on your finger?").assertExists()
        rule.onNodeWithText("Try again").assertExists()
    }

    @Test fun bpAndHrvReadCalmlyUntilTheFirstValueThenTheNumeralRollsIn() {
        val tiles = hrSpo2 + MeasureTile(MeasureKind.BLOOD_PRESSURE, 50, live = true, estimate = true) +
            MeasureTile(MeasureKind.HRV, 75, live = true, estimate = false)
        val st = mutableStateOf(MeasureUiState(visible = true, tiles = tiles, phase = MeasurePhase.Measuring(MeasureKind.BLOOD_PRESSURE, 50, started = true, elapsedSeconds = 20)))
        show { MeasureContent(st.value, onStart = {}, onAgain = {}, onDone = {}, onStop = {}) }
        rule.onNodeWithText(MeasureCopy.READING).assertExists()
        rule.onAllNodesWithText("—").assertCountEquals(0) // never a bare dash for ~50 s
        rule.onAllNodesWithText("mmHg").assertCountEquals(0)
        val bp = mapOf(Metric.BLOOD_PRESSURE_SYS to 117.0, Metric.BLOOD_PRESSURE_DIA to 78.0)
        st.value = st.value.copy(phase = MeasurePhase.Measuring(MeasureKind.BLOOD_PRESSURE, 50, started = true, values = bp, elapsedSeconds = 46))
        rule.waitForIdle()
        rule.onNodeWithContentDescription("117/78 millimetres of mercury").assertExists()
        rule.onAllNodesWithText(MeasureCopy.READING).assertCountEquals(0)
        // HRV the same way.
        st.value = st.value.copy(phase = MeasurePhase.Measuring(MeasureKind.HRV, 75, started = true, elapsedSeconds = 30))
        rule.waitForIdle()
        rule.onNodeWithText(MeasureCopy.READING).assertExists()
        rule.onAllNodesWithText("—").assertCountEquals(0)
        // A failure names itself in the overline.
        st.value = st.value.copy(phase = MeasurePhase.Failed(MeasureKind.HRV, MeasureFailure.BUSY))
        rule.waitForIdle()
        rule.onNodeWithText("RING BUSY").assertExists()
    }

    @Test fun leavingSheetStopsMeasurement() {
        var stopped = false
        val vm = MeasureViewModel(
            MeasureDeps(
                capabilities = flowOf(dev.avijit.orbithealth.ring.fake.FakeFamily.caps()),
                measure = { flow { emit(MeasureEvent.Started); try { awaitCancellation() } finally { stopped = true } } },
                samples = { _, _, _ -> flowOf(emptyList()) },
                clock = java.time.Clock.systemUTC(),
            ),
        )
        val onScreen = mutableStateOf(true)
        show { if (onScreen.value) MeasureSheet(vm) }
        rule.runOnIdle { vm.open(MeasureKind.HEART_RATE, autoStart = true) }
        rule.waitForIdle()
        assertTrue(vm.state.value.phase is MeasurePhase.Measuring)
        rule.runOnIdle { onScreen.value = false } // the screen under the sheet went away
        rule.waitForIdle()
        assertTrue("the ring stops measuring", stopped)
        assertEquals(false, vm.state.value.visible)
    }
}
