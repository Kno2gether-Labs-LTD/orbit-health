package dev.avijit.orbithealth.ui.sky

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

/** The twinkling stars reuse one glow brush per star size: only the paint alpha changes per frame. */
class StarGlowsTest {
    @Test fun oneBrushPerStarSize() {
        val glows = StarGlows(density = 3f)
        val a = glows.forSize(2f)
        assertSame(a.first, glows.forSize(2f).first)
        assertNotSame(a.first, glows.forSize(2.5f).first)
        // CSS box-shadow 0 0 4px 1px around a 2dp star: 1 + 1 + 2 = 4dp, at 3 px per dp.
        assertEquals(12f, a.second, 1e-4f)
    }
}
