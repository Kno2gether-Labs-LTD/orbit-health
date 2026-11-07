package dev.avijit.orbithealth.ui.sleep

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.compose.runtime.mutableStateOf
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.avijit.orbithealth.insights.Score
import dev.avijit.orbithealth.ring.SleepSegment
import dev.avijit.orbithealth.ring.SleepSession
import dev.avijit.orbithealth.ring.SleepStage
import dev.avijit.orbithealth.ui.nav.LocalNavAnimatedScope
import dev.avijit.orbithealth.ui.nav.LocalSharedTransitionScope
import dev.avijit.orbithealth.ui.nav.Routes
import dev.avijit.orbithealth.ui.sky.LocalSkyForceFallback
import dev.avijit.orbithealth.ui.today.HeroPager
import dev.avijit.orbithealth.ui.today.TodayUiState
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Today's Sleep hero → pushed Sleep detail, through the same shared-element wiring as the app ([LocalSharedTransitionScope]
 * over a NavHost, [LocalNavAnimatedScope] per destination, the hero's "hero-sky-SLEEP"/"hero-arc-SLEEP" keys).
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@RunWith(RobolectricTestRunner::class)
class SleepDetailTransitionTest {
    @get:Rule val rule = createComposeRule()

    private val start = Instant.parse("2026-11-04T23:41:00Z")
    private val night = run {
        var t = start
        val segs = listOf(SleepStage.LIGHT to 30, SleepStage.DEEP to 60, SleepStage.REM to 40, SleepStage.LIGHT to 200, SleepStage.AWAKE to 10)
            .map { (st, m) -> SleepSegment(st, t, m * 60).also { t = t.plusSeconds(m * 60L) } }
        SleepSession(start, t, segs)
    }
    private val nightUi = SleepUiState(
        session = night, score = Score(84, emptyList()), stageTotals = mapOf(
            SleepStage.AWAKE to 600, SleepStage.REM to 2400, SleepStage.LIGHT to 13800, SleepStage.DEEP to 3600,
        ),
        lowestHr = 54, avgSpo2 = 96, heartOverlay = emptyList(), insight = "Deep sleep came early, as it should.",
        title = "Last night", timeRange = "23:41 → 07:21", duration = "5h 50m",
        lowestHrAt = "03:10", avgHrv = 51, hrvVsNormal = 4, lowSpo2 = 92,
    )

    /** The night before, for paging. */
    private val earlierUi = nightUi.copy(
        session = SleepSession(
            night.start.minusSeconds(86_400), night.end.minusSeconds(86_400),
            night.segments.map { it.copy(start = it.start.minusSeconds(86_400)) },
        ),
        title = "Tue 3 Nov",
    )

    private val screen = mutableStateOf(SleepScreenState())
    private lateinit var nav: NavHostController
    private lateinit var backDispatcher: OnBackPressedDispatcher
    private lateinit var shared: SharedTransitionScope

    /** The Sleep hero card: its click action is labelled "Open Sleep" (the corner button is hidden from TalkBack). */
    private val openSleep = SemanticsMatcher("click label \"Open Sleep\"") {
        it.config.getOrNull(SemanticsActions.OnClick)?.label == "Open Sleep"
    }

    private fun setUp(state: SleepScreenState) {
        screen.value = state
        rule.setContent {
            CompositionLocalProvider(LocalSkyForceFallback provides true) {
                backDispatcher = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
                nav = rememberNavController()
                SharedTransitionLayout {
                    shared = this
                    CompositionLocalProvider(LocalSharedTransitionScope provides this) {
                        NavHost(nav, startDestination = Routes.TODAY) {
                            composable(Routes.TODAY) {
                                CompositionLocalProvider(LocalNavAnimatedScope provides this) {
                                    Column {
                                        val pager = rememberPagerState(initialPage = 1) { 3 }
                                        HeroPager(
                                            TodayUiState(sleep = Score(84, emptyList()), lastNight = night), pager, scrollPx = { 0 },
                                            onOpen = { nav.navigate(Routes.sleepDetail(night.start.toEpochMilli())) },
                                        )
                                    }
                                }
                            }
                            composable(Routes.SLEEP_DETAIL, arguments = Routes.sleepDetailArguments) { entry ->
                                CompositionLocalProvider(LocalNavAnimatedScope provides this) {
                                    SleepContent(screen.value, onBack = { nav.popBackStack() }, focusNight = Routes.nightOf(entry), heroScore = 84)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Test fun backDuringTransitionDoesNotCrash() {
        setUp(SleepScreenState(loaded = true, nights = listOf(nightUi)))
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        rule.onNode(openSleep).performClick()
        // A few frames in: the hero is mid-flight into the Sleep header.
        repeat(6) { rule.mainClock.advanceTimeByFrame() }
        assertTrue("expected the shared transition to be running", shared.isTransitionActive)
        rule.runOnUiThread { backDispatcher.onBackPressed() }
        repeat(4) { rule.mainClock.advanceTimeByFrame() }
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        assertEquals(Routes.TODAY, nav.currentDestination?.route)
        assertTrue(!shared.isTransitionActive)
        rule.onNode(openSleep).assertIsDisplayed()
    }

    @Test fun backAfterSwipingToAnotherNightSkipsTheSharedTransition() {
        setUp(SleepScreenState(loaded = true, nights = listOf(earlierUi, nightUi)))
        rule.onNode(openSleep).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Last night").assertIsDisplayed()
        // Swipe back a night: the focus night (the hero's) is now off-screen.
        rule.onRoot().performTouchInput { swipeRight(startX = 20f, endX = width - 20f) }
        rule.waitForIdle()
        rule.onNodeWithText("Tue 3 Nov").assertIsDisplayed()
        rule.mainClock.autoAdvance = false
        rule.runOnUiThread { backDispatcher.onBackPressed() }
        var sawShared = false
        repeat(12) { rule.mainClock.advanceTimeByFrame(); if (shared.isTransitionActive) sawShared = true }
        assertFalse("Back from another night must not fly the hero to an off-screen page", sawShared)
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        assertEquals(Routes.TODAY, nav.currentDestination?.route)
        rule.onNode(openSleep).assertIsDisplayed()
    }

    @Test fun backWhileTheNightIsStillLoadingPopsCleanly() {
        setUp(SleepScreenState()) // not loaded: the header placeholder holds the shared keys
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        rule.onNode(openSleep).performClick()
        repeat(4) { rule.mainClock.advanceTimeByFrame() }
        assertTrue("expected the shared transition to be running", shared.isTransitionActive)
        // The data lands mid-flight (placeholder header → night page), then Back.
        screen.value = SleepScreenState(loaded = true, nights = listOf(earlierUi, nightUi))
        repeat(2) { rule.mainClock.advanceTimeByFrame() }
        rule.runOnUiThread { backDispatcher.onBackPressed() }
        repeat(4) { rule.mainClock.advanceTimeByFrame() }
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        assertEquals(Routes.TODAY, nav.currentDestination?.route)
        assertFalse(shared.isTransitionActive)
        rule.onNode(openSleep).assertIsDisplayed()
    }

    @Test fun backBeforeAnyDataPopsCleanly() {
        setUp(SleepScreenState())
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        rule.onNode(openSleep).performClick()
        repeat(3) { rule.mainClock.advanceTimeByFrame() }
        rule.runOnUiThread { backDispatcher.onBackPressed() }
        // The data arrives after Back was pressed, while the detail is leaving.
        screen.value = SleepScreenState(loaded = true, nights = listOf(nightUi))
        repeat(4) { rule.mainClock.advanceTimeByFrame() }
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        assertEquals(Routes.TODAY, nav.currentDestination?.route)
        rule.onNode(openSleep).assertIsDisplayed()
    }

    @Test fun sleepEmptyStateStillShown() {
        rule.setContent {
            CompositionLocalProvider(LocalSkyForceFallback provides true) {
                SleepContent(SleepScreenState(loaded = true, nights = emptyList()), onBack = {})
            }
        }
        rule.onNodeWithText(NoSleepCopy).assertIsDisplayed()
    }

    @Test fun detailShowsTheNightWithItsCaption() {
        setUp(SleepScreenState(loaded = true, nights = listOf(nightUi)))
        rule.runOnIdle { nav.navigate(Routes.sleepDetail(null)) }
        rule.waitForIdle()
        rule.onNodeWithText("Last night").assertIsDisplayed()
        rule.onNodeWithText("heart rate overlay · tap any point", substring = true).assertExists()
        rule.onNodeWithContentDescription("Back").performClick()
        rule.waitForIdle()
        rule.waitForIdle()
        assertEquals(Routes.TODAY, nav.currentDestination?.route)
    }
}
