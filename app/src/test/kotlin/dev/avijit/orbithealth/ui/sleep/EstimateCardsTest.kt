package dev.avijit.orbithealth.ui.sleep

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import dev.avijit.orbithealth.insights.EstimateStatus
import dev.avijit.orbithealth.insights.Score
import dev.avijit.orbithealth.insights.SleepEstimate
import dev.avijit.orbithealth.insights.SleepKind
import dev.avijit.orbithealth.ring.SleepSession
import dev.avijit.orbithealth.ui.sky.LocalSkyForceFallback
import dev.avijit.orbithealth.ui.today.TodayContent
import dev.avijit.orbithealth.ui.today.TodayUiState
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The estimate cards on Today and Sleep: the question, Yes / Adjust / No, the nap card and the estimated night. */
@Config(qualifiers = "w390dp-h844dp-xxhdpi")
@RunWith(RobolectricTestRunner::class)
class EstimateCardsTest {
    @get:Rule val rule = createComposeRule()

    private val zone = ZoneOffset.UTC
    private val start = Instant.parse("2026-11-07T03:10:00Z")
    private val end = Instant.parse("2026-11-07T10:40:00Z")
    private val night = EstimateCopy.suggestion(
        SleepEstimate(7, SleepKind.NIGHT, EstimateStatus.SUGGESTED, start, end, start, end, 54, 93, 0.9), zone,
    )
    private val nap = EstimateCopy.suggestion(
        SleepEstimate(
            8, SleepKind.NAP, EstimateStatus.SUGGESTED, Instant.parse("2026-11-07T14:10:00Z"), Instant.parse("2026-11-07T14:50:00Z"),
            Instant.parse("2026-11-07T14:10:00Z"), Instant.parse("2026-11-07T14:50:00Z"), 58, null, 0.8,
        ),
        zone,
    )

    private fun showNight(answers: MutableList<EstimateAnswer>) = rule.setContent {
        CompositionLocalProvider(LocalSkyForceFallback provides true) { NightSuggestionCard(night, { answers += it }, zone = zone) }
    }

    @Test fun nightCardAsksInPlainWords() {
        showNight(mutableListOf())
        rule.onNodeWithText("Looks like you slept 3:10–10:40. Right?").assertExists()
        rule.onNodeWithText("7h 30m · lowest 54 bpm · SpO₂ low 93%").assertExists()
        rule.onNodeWithText(EstimateCopy.NightKicker).assertExists()
        rule.onNodeWithText("ESTIMATE").assertExists()
    }

    @Test fun yesConfirmsAsSuggested() {
        val answers = mutableListOf<EstimateAnswer>()
        showNight(answers)
        rule.onNodeWithText("Yes").performClick()
        assertEquals(listOf<EstimateAnswer>(EstimateAnswer.Confirm(7)), answers)
    }

    @Test fun noDismisses() {
        val answers = mutableListOf<EstimateAnswer>()
        showNight(answers)
        rule.onNodeWithText("No").performClick()
        assertEquals(listOf<EstimateAnswer>(EstimateAnswer.Dismiss(7)), answers)
    }

    @Test fun adjustMovesTheTimesAndSaves() {
        val answers = mutableListOf<EstimateAnswer>()
        showNight(answers)
        rule.onNodeWithText("Adjust").performClick()
        rule.waitForIdle()
        rule.onNodeWithText(EstimateCopy.SheetTitle).assertExists()
        rule.onNodeWithText("7h 30m asleep").assertExists()
        // Fell asleep 20 minutes later, woke 10 minutes earlier. The sheet is its own window: invoke the click actions.
        repeat(2) { rule.onNodeWithContentDescription("Increase Fell asleep").performSemanticsAction(SemanticsActions.OnClick) }
        rule.onNodeWithContentDescription("Decrease Woke up").performSemanticsAction(SemanticsActions.OnClick)
        rule.onNodeWithText("03:30").assertExists()
        rule.onNodeWithText("10:30").assertExists()
        rule.onNodeWithText("7h 00m asleep").assertExists()
        rule.onNodeWithText("Save").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(
            listOf<EstimateAnswer>(EstimateAnswer.Confirm(7, start.plus(Duration.ofMinutes(20)), end.minus(Duration.ofMinutes(10)))),
            answers,
        )
    }

    @Test fun napCardIsSmallAndConfirmable() {
        val answers = mutableListOf<EstimateAnswer>()
        rule.setContent { NapSuggestionCard(nap, { answers += it }) }
        rule.onNodeWithText("Nap · 14:10–14:50 · 40 min").assertExists()
        rule.onNodeWithText("Yes, a nap").performClick()
        rule.onNodeWithText("No").performClick()
        assertEquals(listOf(EstimateAnswer.Confirm(8), EstimateAnswer.Dismiss(8)), answers)
    }

    @Test fun todayShowsTheSuggestionAndPassesTheAnswer() {
        val answers = mutableListOf<EstimateAnswer>()
        rule.setContent {
            CompositionLocalProvider(LocalSkyForceFallback provides true) {
                TodayContent(
                    TodayUiState(loaded = true, nightSuggestion = night, napSuggestion = nap),
                    onRefresh = {}, onDismissBanner = {}, onUninstall = {}, onAnswerEstimate = { answers += it },
                )
            }
        }
        rule.onNodeWithText("Looks like you slept 3:10–10:40. Right?").performScrollTo()
        rule.onNodeWithText("Yes").performScrollTo().performClick()
        rule.onNodeWithText("Nap · 14:10–14:50 · 40 min").performScrollTo()
        assertEquals(listOf<EstimateAnswer>(EstimateAnswer.Confirm(7)), answers)
    }

    @Test fun todayHeroLabelsAConfirmedEstimate() {
        rule.setContent {
            CompositionLocalProvider(LocalSkyForceFallback provides true) {
                TodayContent(
                    TodayUiState(
                        loaded = true, sleep = Score(80, emptyList()), lastNight = SleepSession(start, end, emptyList()),
                        lastNightEstimated = true, sleepInsight = "A full night. You met your sleep goal.", sleepMeta = EstimateCopy.NoStages,
                    ),
                    onRefresh = {}, onDismissBanner = {}, onUninstall = {},
                )
            }
        }
        rule.onNodeWithContentDescription("Estimated sleep, no stages", useUnmergedTree = true).assertExists()
        rule.onAllNodesWithText("7h 30m asleep", useUnmergedTree = true).fetchSemanticsNodes().let { assertEquals(1, it.size) }
    }

    @Test fun sleepScreenShowsAnEstimatedNightWithoutStages() {
        val n = SleepMath.nights(
            emptyList(), emptyList(), emptyList(), 450, java.time.LocalDate.of(2026, 11, 7), zone,
            estimates = listOf(SleepEstimate(7, SleepKind.NIGHT, EstimateStatus.CONFIRMED, start, end, start, end, 54, 93, 0.9)),
        )
        rule.setContent {
            CompositionLocalProvider(LocalSkyForceFallback provides true) { SleepContent(SleepScreenState(loaded = true, nights = n)) }
        }
        rule.onNodeWithText(EstimateCopy.EstimatedNightTitle).performScrollTo()
        rule.onNodeWithText(EstimateCopy.NoStages).performScrollTo()
        rule.onAllNodesWithText("Stages").fetchSemanticsNodes().let { assertEquals(0, it.size) }
        rule.onNodeWithText("About 7h 30m · 03:10 → 10:40").assertExists()
    }

    @Test fun emptySleepScreenAsksAboutAMissedNight() {
        rule.setContent {
            CompositionLocalProvider(LocalSkyForceFallback provides true) {
                SleepContent(SleepScreenState(loaded = true, nights = emptyList(), suggestion = night))
            }
        }
        rule.onNodeWithText("Looks like you slept 3:10–10:40. Right?").performScrollTo()
    }
}
