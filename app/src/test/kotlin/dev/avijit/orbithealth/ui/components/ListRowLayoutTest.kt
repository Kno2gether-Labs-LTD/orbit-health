package dev.avijit.orbithealth.ui.components

import androidx.compose.foundation.layout.width
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.onNodeWithText
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A settings row with a long value (a server's host name) on a small phone: the label keeps its natural width and
 * the value ellipsizes. Native graphics, so text is measured with the real fonts.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h780dp-xxhdpi")
class ListRowLayoutTest {
    @get:Rule val rule = createComposeRule()

    @Test fun aLongValueNeverSqueezesTheLabel() {
        rule.setContent {
            ListCard {
                ListRow("Agent & sync", first = true, chevron = true, value = "ips-hom-serv-01.tail46a52a.ts.net")
            }
        }
        val label = rule.onNodeWithText("Agent & sync", useUnmergedTree = true).fetchSemanticsNode()
        val value = rule.onNodeWithText("ips-hom-serv-01", substring = true, useUnmergedTree = true).fetchSemanticsNode()
        // xxhdpi: 3 px per dp. One line at its natural size (~85dp), not squeezed to "Ag / e…".
        assertTrue("label width ${label.size.width}", label.size.width > 70 * 3)
        assertTrue("label height ${label.size.height}", label.size.height < 30 * 3)
        // The value is what gives way, and it stays inside the row.
        assertTrue("value right ${value.boundsInRoot.right}", value.boundsInRoot.right <= 360 * 3)
    }

    @Test fun aShortValueLeavesTheLabelTheRest() {
        rule.setContent {
            ListCard { ListRow("Keep connected in background", first = true, value = "On") }
        }
        val label = rule.onNodeWithText("Keep connected in background", useUnmergedTree = true).fetchSemanticsNode()
        assertTrue("label height ${label.size.height}", label.size.height < 30 * 3)
    }

    @Test fun aMiniCardValueShrinksToFitInsteadOfClipping() {
        rule.setContent {
            androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.width(100.dp)) {
                MiniMetricCard(dev.avijit.orbithealth.ui.theme.OrbitPalette.ActivityA, null, "Calories", "1,240", "kcal", "Active")
            }
        }
        val node = rule.onNodeWithText("1,240 kcal", useUnmergedTree = true).fetchSemanticsNode()
        // The whole value fits inside the card's 100dp less its 14dp padding each side.
        val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        node.config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        val text = results.single()
        assertTrue("value overflows at ${text.layoutInput.style.fontSize}", !text.didOverflowWidth)
        assertTrue("value right ${node.boundsInRoot.right}", node.boundsInRoot.right <= 100 * 3)
    }
}
