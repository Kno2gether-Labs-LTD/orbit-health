package dev.avijit.orbithealth.ui.sky

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SkyShaderGuardTest {
    private val logs = mutableListOf<String>()
    private val health = SkyShaderHealth { m, _ -> logs += m }

    private class FakeProgram(val failOnFrame: Boolean = false) : SkyShaderProgram {
        var frames = 0
        override val brush: Brush = SolidColor(Color.Black)
        override fun bindStatic(spec: SkySpec, w: Float, h: Float, pxPerDp: Float) = Unit
        override fun bindFrame(spec: SkySpec, frame: SkyFrame) {
            frames++
            if (failOnFrame) throw IllegalArgumentException("unable to find uniform named uSun")
        }
    }

    private val spec = skySpec(SkyKind.NIGHT)
    private val frame = resolveFrame(spec, 342f, 392f, 0f, 0f)

    @Test
    fun shaderFailureFallsBackWithoutCrash() {
        // An SkSL compile error (older platform Skia) is caught: no program, the sky falls back, logged once.
        val guard = SkyShaderGuard({ throw IllegalArgumentException("error: 12: unknown identifier") }, health)
        assertNull(guard.program)
        assertFalse(guard.usable)
        assertTrue(health.failed)
        assertFalse(guard.run("bindStatic") { error("must not run") })
        // Process-wide: a later sky does not even try to compile, and nothing is logged twice.
        var built = false
        val later = SkyShaderGuard({ built = true; FakeProgram() }, health)
        assertFalse(built)
        assertFalse(later.usable)
        assertEquals(1, logs.size)
    }

    @Test
    fun bindingFailureFlipsToFallbackForGood() {
        val program = FakeProgram(failOnFrame = true)
        val guard = SkyShaderGuard({ program }, health)
        assertTrue(guard.usable)
        assertTrue(guard.run("bindStatic") { it.bindStatic(spec, 342f, 392f, 1.3f) })
        assertFalse(guard.run("bindFrame") { it.bindFrame(spec, frame) })
        assertTrue(health.failed)
        assertFalse(guard.usable)
        // Never touched again after the first failure.
        assertFalse(guard.run("bindFrame") { it.bindFrame(spec, frame) })
        assertEquals(1, program.frames)
        assertEquals(1, logs.size)
    }

    @Test
    fun healthyProgramStaysOnTheShader() {
        val program = FakeProgram()
        val guard = SkyShaderGuard({ program }, health)
        repeat(3) { assertTrue(guard.run("bindFrame") { p -> p.bindFrame(spec, frame) }) }
        assertFalse(health.failed)
        assertEquals(0, logs.size)
    }
}
