package dev.avijit.orbithealth.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** R6: the Day/Week capsule (34dp) and Body's text tabs (46dp) keep their look but take ≥ 48dp touches. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w390dp-h844dp-xxhdpi")
class TouchTargetTest {
    @get:Rule val rule = createComposeRule()

    @Test fun segmentedPillKeepsItsCapsuleButTakes48dpTouches() {
        var selected by mutableIntStateOf(0)
        rule.setContent {
            Box(Modifier.padding(40.dp)) {
                SegmentedPill(listOf("Day", "Week"), selected, { selected = it }, Modifier.testTag("pill"))
            }
        }
        rule.onNodeWithTag("pill").assertHeightIsEqualTo(34.dp)
        rule.onNodeWithText("Week").assertHeightIsAtLeast(48.dp)
        // A tap 1px inside the top of the target lands 7dp above the visible capsule, and still selects.
        rule.onNodeWithText("Week").performTouchInput { click(Offset(centerX, 1f)) }
        rule.runOnIdle { assertEquals(1, selected) }
    }

    @Test fun textTabsTake48dpTouchesWithoutMovingTheRow() {
        var selected by mutableIntStateOf(0)
        rule.setContent {
            Box(Modifier.padding(top = 40.dp)) {
                TextTabs(listOf("Heart", "Stress", "Activity"), selected, { selected = it }, Modifier.testTag("tabs"))
            }
        }
        rule.onNodeWithTag("tabs").assertHeightIsEqualTo(TextTabsDefaults.TouchHeight)
        rule.onNodeWithText("Stress").assertHeightIsAtLeast(48.dp)
        rule.onNodeWithText("Stress").performTouchInput { click(Offset(centerX, height - 1f)) }
        rule.runOnIdle { assertEquals(1, selected) }
    }
}
