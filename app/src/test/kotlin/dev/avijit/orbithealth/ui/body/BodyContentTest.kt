package dev.avijit.orbithealth.ui.body

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dev.avijit.orbithealth.ui.sky.LocalSkyForceFallback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The Living Skies Body sections keep their behaviour: Measure now, ESTIMATE labels, honest BP, empty states. */
/** A 390dp-wide phone, as the mockup. */
@Config(qualifiers = "w390dp-h844dp-xxhdpi")
@RunWith(RobolectricTestRunner::class)
class BodyContentTest {
    @get:Rule val rule = createComposeRule()

    private fun show(content: @Composable () -> Unit) = rule.setContent {
        CompositionLocalProvider(LocalSkyForceFallback provides true) { content() }
    }

    @Test fun heartKeepsMeasureNowTheBpEstimateAndItsLastDate() {
        var measured = 0
        var tab: BodyTab? = null
        show {
            HeartContent(
                HeartUiState(loaded = true, resting = 61, hrv = 48, bpLast = "Wed 4 Nov"),
                onTab = { tab = it }, onMeasure = { measured++ }, onRange = {},
            )
        }
        rule.onNodeWithText("Measure now").performScrollTo().performClick()
        assertEquals(1, measured)
        rule.onNodeWithText("ESTIMATE").performScrollTo()
        rule.onNodeWithText("Last: Wed 4 Nov", substring = true).performScrollTo()
        rule.onNodeWithText("Activity").performScrollTo().performClick()
        assertEquals(BodyTab.ACTIVITY, tab)
    }

    @Test fun stressIsLabelledAnEstimateAndEmptyStatesAreCalm() {
        show { StressContent(StressUiState(loaded = true), onTab = {}, onRange = {}) }
        rule.onNodeWithText("ESTIMATE").performScrollTo()
        rule.onNodeWithText(NoStressCopy).performScrollTo()
        rule.onNodeWithText(NoSpo2Copy).performScrollTo()
    }

    @Test fun activityShowsTheWorkoutsEmptyStateAndNoStartWorkout() {
        show { ActivityContent(ActivityUiState(loaded = true, steps = 914, goal = 9200), onTab = {}) }
        rule.onNodeWithText("8,286 to go", substring = true).performScrollTo()
        rule.onNodeWithText(NoWorkoutsCopy).performScrollTo()
        rule.onAllNodesWithText("Start workout", substring = true).assertCountEquals(0)
    }

    @Test fun trendsLeadWithTheMonthSentence() {
        val sentence = "Over the last month your heart has settled and your nights are steadier."
        show { TrendsContent(TrendsUiState(loaded = true, sentence = sentence), onTab = {}, onRange = {}) }
        rule.onNodeWithText(sentence).performScrollTo()
    }

    @Test fun aStoredMeasuredReadingIsCaptionedMeasured() {
        val at = java.time.Instant.parse("2026-11-06T11:40:00Z")
        val measured = dev.avijit.orbithealth.ring.Sample(
            dev.avijit.orbithealth.ring.Metric.HEART_RATE, at, 72.0, dev.avijit.orbithealth.ring.SampleSource.MEASURED,
        )
        val latest = BodyMath.heartLatest(measured, java.time.Instant.parse("2026-11-06T11:52:00Z"), java.time.ZoneOffset.UTC)
        show {
            HeartContent(
                HeartUiState(loaded = true, latestHr = measured, latestFreshness = latest.freshness, latestCaption = latest.caption, latestBpm = latest.bpm),
                onTab = {}, onMeasure = {}, onRange = {},
            )
        }
        rule.onNodeWithText("Measured · 11:40").performScrollTo()
        rule.onAllNodesWithText("Ring on finger").assertCountEquals(0)
        rule.onAllNodesWithText("LIVE").assertCountEquals(0)
        rule.onNodeWithText("HEART RATE").performScrollTo()
    }

    @Test fun aReadingOverAWeekOldIsADashNotTheCurrentHeartRate() {
        val old = dev.avijit.orbithealth.ring.Sample(
            dev.avijit.orbithealth.ring.Metric.HEART_RATE, java.time.Instant.parse("2026-10-20T09:00:00Z"), 64.0,
            dev.avijit.orbithealth.ring.SampleSource.HISTORY,
        )
        val latest = BodyMath.heartLatest(old, java.time.Instant.parse("2026-11-06T11:52:00Z"), java.time.ZoneOffset.UTC)
        show {
            HeartContent(
                HeartUiState(
                    loaded = true, latestHr = old, latestFreshness = latest.freshness, latestCaption = latest.caption, latestBpm = latest.bpm,
                ),
                onTab = {}, onMeasure = {}, onRange = {},
            )
        }
        rule.onNodeWithText(BodyMath.NO_RECENT_HR).performScrollTo()
        rule.onAllNodesWithText("64").assertCountEquals(0)
        rule.onNodeWithText("Wear your ring to see your heart rate").performScrollTo()
    }

    @Test fun weekMinisNameTheirPeriodNotToday() {
        show { HeartContent(HeartUiState(loaded = true, range = Range.WEEK, resting = 66, hrv = 84), onTab = {}, onMeasure = {}, onRange = {}) }
        rule.onAllNodesWithText("Last 7 days").assertCountEquals(2)
        rule.onAllNodesWithText("Today").assertCountEquals(0)
    }
}
