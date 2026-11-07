package dev.avijit.orbithealth.ui.today

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import dev.avijit.orbithealth.insights.Score
import dev.avijit.orbithealth.ui.sky.LocalSkyForceFallback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** R6 polish: one "Open <metric>" action per hero, and the arc reveal waits until its page is current. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w390dp-h844dp-xxhdpi")
class HeroPagerPolishTest {
    @get:Rule val rule = createComposeRule()

    private lateinit var pager: PagerState
    private lateinit var scope: CoroutineScope

    private fun setUp() {
        rule.setContent {
            CompositionLocalProvider(LocalSkyForceFallback provides true) {
                Column {
                    pager = rememberPagerState { TodayCopy.HeroPages.size }
                    scope = rememberCoroutineScope()
                    HeroPager(
                        TodayUiState(readiness = Score(80, emptyList()), sleep = Score(84, emptyList())),
                        pager, scrollPx = { 0 }, onOpen = {},
                    )
                }
            }
        }
    }

    private fun opens(label: String) = SemanticsMatcher("exposes \"Open $label\"") { node ->
        node.config.getOrNull(SemanticsActions.OnClick)?.label == "Open $label" ||
            node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Open $label") == true
    }

    @Test fun eachHeroExposesOneOpenAction() {
        setUp()
        rule.waitForIdle()
        // The card and its corner button used to both say "Open Readiness" to TalkBack.
        val nodes = rule.onAllNodes(opens("Readiness"), useUnmergedTree = true).fetchSemanticsNodes()
        assertEquals(1, nodes.size)
        assertEquals("Open Readiness", nodes.single().config.getOrNull(SemanticsActions.OnClick)?.label)
    }

    @Test fun arcRevealStartsWhenThePageBecomesCurrent() {
        setUp()
        rule.mainClock.autoAdvance = false
        rule.mainClock.advanceTimeBy(3_000)
        // Readiness (current) has revealed; Sleep (composed off-screen as the neighbour) is still waiting at 0.
        rule.onNodeWithText("80", useUnmergedTree = true).assertExists()
        assertTrue(rule.onAllNodesWithText("84", useUnmergedTree = true).fetchSemanticsNodes().isEmpty())

        rule.runOnUiThread { scope.launch { pager.scrollToPage(1) } }
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeBy(300)
        // Mid-reveal on becoming current: counting, not yet landed.
        assertTrue(rule.onAllNodesWithText("84", useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        rule.mainClock.advanceTimeBy(3_000)
        rule.onNodeWithText("84", useUnmergedTree = true).assertExists()
    }

    @Test fun onlyTheCurrentPageAndTheDragTargetAnimate() {
        // At rest only the current page's sky runs.
        assertTrue(HeroDefaults.skyAnimates(page = 1, currentPage = 1, offsetFraction = 0f, scrolling = false))
        assertFalse(HeroDefaults.skyAnimates(page = 2, currentPage = 1, offsetFraction = 0f, scrolling = false))
        // Dragging towards page 2: it joins (2 animated skies at most) so it is already live when it lands.
        assertTrue(HeroDefaults.skyAnimates(page = 2, currentPage = 1, offsetFraction = 0.2f, scrolling = true))
        assertFalse(HeroDefaults.skyAnimates(page = 0, currentPage = 1, offsetFraction = 0.2f, scrolling = true))
        assertTrue(HeroDefaults.skyAnimates(page = 0, currentPage = 1, offsetFraction = -0.2f, scrolling = true))
        // During the hero → detail transition nothing in the pager animates.
        assertFalse(HeroDefaults.skyAnimates(page = 1, currentPage = 1, offsetFraction = 0f, scrolling = false, transition = true))
    }
}
