package dev.avijit.orbithealth.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/** Mini-card values shrink to fit instead of clipping ("1,240 kcal" in 79dp, "199 bpm" on a 360dp phone). */
class TextFitTest {
    /** A fake measure: width grows linearly with size, like a single line of text. */
    private fun widthOf(chars: Int) = { sp: Float -> chars * sp * 0.6f }

    @Test fun keepsTheTokenSizeWhenItFits() {
        assertEquals(28f, TextFit.fitFontSize(28f, 20f, 1f, 100f, widthOf(5)), 0f) // 84px wide
    }

    @Test fun stepsDownUntilItFits() {
        // "1,240 kcal" ≈ 8 em-ish chars: 28sp is 134px, 79px fits at 16.4 → floored at the 20sp minimum.
        assertEquals(20f, TextFit.fitFontSize(28f, 20f, 1f, 79f, widthOf(8)), 0f)
        // 7 chars in 110px: 26sp is 109.2px.
        assertEquals(26f, TextFit.fitFontSize(28f, 20f, 1f, 110f, widthOf(7)), 0f)
    }
}
