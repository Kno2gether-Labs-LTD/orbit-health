package dev.avijit.orbithealth.ui.today

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import dev.avijit.orbithealth.insights.Score
import dev.avijit.orbithealth.ring.SleepSegment
import dev.avijit.orbithealth.ring.SleepSession
import dev.avijit.orbithealth.ring.SleepStage
import dev.avijit.orbithealth.ui.sky.LocalSkyForceFallback
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * What a first real night exposed on Today: the sleep hero's meta clipped under a two-line insight, the stress
 * ribbon's "Now" printed at the wrong hour, and sleep copy contradicting the score. Native graphics, so text wraps
 * as it does on a phone.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w390dp-h844dp-xxhdpi")
class TodayRealDataPolishTest {
    @get:Rule val rule = createComposeRule()

    private lateinit var pager: PagerState
    private lateinit var scope: CoroutineScope

    private val night: SleepSession = run {
        var t = Instant.parse("2026-11-06T00:41:00Z")
        val start = t
        val segs = listOf(SleepStage.LIGHT to 50, SleepStage.DEEP to 40, SleepStage.REM to 1, SleepStage.LIGHT to 120, SleepStage.REM to 60, SleepStage.LIGHT to 72)
            .map { (st, min) -> SleepSegment(st, t, min * 60).also { t = t.plusSeconds(min * 60L) } }
        SleepSession(start, t, segs)
    }

    private fun showSleepHero(insight: String, meta: String) {
        rule.setContent {
            CompositionLocalProvider(LocalSkyForceFallback provides true) {
                Column {
                    pager = rememberPagerState(initialPage = 1) { TodayCopy.HeroPages.size }
                    scope = rememberCoroutineScope()
                    HeroPager(
                        TodayUiState(sleep = Score(85, emptyList()), lastNight = night, sleepInsight = insight, sleepMeta = meta),
                        pager, scrollPx = { 0 }, onOpen = {},
                    )
                }
            }
        }
        rule.waitForIdle()
    }

    @Test fun aTwoLineSleepInsightHidesTheMetaInsteadOfClippingIt() {
        showSleepHero("A short night. An early bedtime will help, and a calm evening too.", "Deep 1h 01m · REM 1h 02m")
        rule.onAllNodesWithText("Deep 1h 01m · REM 1h 02m", useUnmergedTree = true).fetchSemanticsNodes().let {
            assertTrue("meta must not be drawn under a wrapped insight", it.isEmpty())
        }
        // The insight itself ends inside the 392dp card.
        val b = rule.onNodeWithText("A short night", substring = true, useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue("insight bottom ${b.bottom} inside the card", b.bottom <= HeroDefaults.Height)
    }

    @Test fun aOneLineSleepInsightKeepsTheMeta() {
        showSleepHero("Deep sleep came early.", "Deep 1h 01m · REM 1h 02m")
        val b = rule.onNodeWithText("Deep 1h 01m · REM 1h 02m", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue("meta bottom ${b.bottom} inside the card", b.bottom <= HeroDefaults.Height - 8.dp)
    }

    @Test fun sleepDurationsUseOneFormat() {
        assertEquals("5h 43m", TodayCopy.hm(5 * 3600 + 42 * 60 + 40))
        assertEquals("5h 03m", TodayCopy.hm(5 * 3600 + 3 * 60))
        assertEquals("0h 45m", TodayCopy.hm(45 * 60))
        // The Sleep detail says the same as Today.
        assertEquals(TodayCopy.hm(20_560), dev.avijit.orbithealth.ui.sleep.SleepMath.hm(20_560))
    }

    @Test fun aShortButSoundNightDoesNotContradictItsScore() {
        assertEquals("Short, but sound. An early night will top you up.", TodayCopy.sleepInsight(5 * 3600 + 43 * 60, false, 450, score = 85))
        assertEquals("A short night. An early bedtime will help.", TodayCopy.sleepInsight(5 * 3600, false, 450, score = 55))
        assertEquals("A short night. An early bedtime will help.", TodayCopy.sleepInsight(5 * 3600, false, 450))
    }

    @Test fun readinessLearningCopyCountsNights() {
        assertEquals("Learning your baseline: night 1 of 3.", TodayCopy.readinessInsight(null, 0))
        assertEquals("Learning your baseline: night 2 of 3.", TodayCopy.readinessInsight(null, 2))
    }

    @Test fun stressRibbonPutsNowWhereNowIs() {
        // 10:24 in the 06–18 window: "Now" at 0.37, every tick still shown (09 is 84 min away).
        val at1024 = StressRibbonMath.labels(6, 10 * 60 + 24)
        assertEquals(listOf("06", "09", "Now", "12", "15", "18"), at1024.map { it.second })
        assertEquals((4 * 60 + 24) / 720f, at1024.first { it.second == "Now" }.first, 1e-4f)
        // 11:44: the 12 tick is 16 min away and gives way.
        assertEquals(listOf("06", "09", "Now", "15", "18"), StressRibbonMath.labels(6, 11 * 60 + 44).map { it.second })
        // The dotted rest-of-window starts right after the last reading, not at now.
        assertEquals(110f, StressRibbonMath.dotsFrom(100f, 10f))
        assertEquals(0f, StressRibbonMath.dotsFrom(null, 10f))
    }

    @Test fun fourDigitStepsStayInsideTheHeroArc() = stepsFitTheArc(2_646)

    @Test fun fiveDigitStepsStayInsideTheHeroArc() = stepsFitTheArc(25_000)

    private fun stepsFitTheArc(n: Int) {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            CompositionLocalProvider(LocalSkyForceFallback provides true) {
                Column {
                    HeroPager(
                        TodayUiState(
                            activity = Score(100, emptyList()),
                            goals = listOf(dev.avijit.orbithealth.insights.Goal("Steps", 10_000, n, null)),
                        ),
                        rememberPagerState(initialPage = 2) { TodayCopy.HeroPages.size }, scrollPx = { 0 }, onOpen = {},
                    )
                }
            }
        }
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeBy(4_000)
        val text = java.text.NumberFormat.getIntegerInstance(java.util.Locale.US).format(n)
        val b = rule.onNodeWithText(text, useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue("$text is ${b.right - b.left} wide", b.right - b.left <= HeroDefaults.NumeralMaxWidth + 2.dp)
    }
}
