package dev.avijit.orbithealth.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OrbitIconButtonTest {
    @get:Rule val rule = createComposeRule()

    @Test fun touchTargetIs48dpAroundTheVisibleCircle() {
        var clicks = 0
        rule.setContent {
            Box(Modifier.size(120.dp)) {
                Box(Modifier.align(Alignment.Center).testTag("slot")) {
                    OrbitIconButton(OrbitSymbol.Back, onClick = { clicks++ }, size = 40.dp, contentDescription = "Back")
                }
            }
        }
        // The button takes a 48dp slot and its semantics node (the touch target) is 48dp...
        rule.onNodeWithTag("slot").assertWidthIsEqualTo(48.dp).assertHeightIsEqualTo(48.dp)
        rule.onNodeWithContentDescription("Back").assertWidthIsEqualTo(48.dp).assertHeightIsEqualTo(48.dp)
        // ...while the circle stays 40dp: a tap 1dp inside the slot edge, outside the circle, still presses it.
        val edge = with(rule.density) { 1.dp.toPx() }
        rule.onNodeWithTag("slot").performTouchInput { click(Offset(edge, centerY)) }
        rule.waitForIdle()
        assertEquals(1, clicks)
        val node = rule.onNodeWithContentDescription("Back").fetchSemanticsNode()
        assertEquals(Role.Button, node.config[SemanticsProperties.Role])
        // The content description names it; no duplicate click label.
        assertNull(node.config[SemanticsActions.OnClick].label)
    }

}
