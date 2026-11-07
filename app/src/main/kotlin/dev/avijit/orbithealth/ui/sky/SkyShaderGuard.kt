package dev.avijit.orbithealth.ui.sky

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Brush
import java.util.concurrent.atomic.AtomicBoolean

/** A compiled sky program: the brush to draw and its uniform plumbing (the AGSL one is `AgslSkyProgram`). */
internal interface SkyShaderProgram {
    val brush: Brush
    fun bindStatic(spec: SkySpec, w: Float, h: Float, pxPerDp: Float)
    fun bindFrame(spec: SkySpec, frame: SkyFrame)
}

/** Builds a [SkyShaderProgram]; may throw (SkSL compile error on an older platform Skia). Swappable in tests. */
internal fun interface SkyProgramFactory {
    fun create(): SkyShaderProgram
}

/**
 * Process-wide shader health. The first shader failure (compile or uniform binding) flips [failed] for good: every
 * [Sky] reads it in composition, so all skies recompose onto the static fallback instead of crashing the screen. The
 * failure is logged once.
 */
internal class SkyShaderHealth(private val logger: (String, Throwable) -> Unit = { m, t -> Log.w(TAG, m, t) }) {
    var failed by mutableStateOf(false)
        private set
    private val logged = AtomicBoolean(false)

    fun reportFailure(where: String, t: Throwable) {
        failed = true
        if (logged.compareAndSet(false, true)) logger("Sky shader failed in $where; using the static fallback from now on", t)
    }

    companion object {
        private const val TAG = "OrbitSky"
        val Shared = SkyShaderHealth()
    }
}

/**
 * Owns one sky's program and turns any exception from building or binding it into a fallback, never a crash.
 * [program] is null when the shader is unusable (failed here or anywhere else in the process).
 */
internal class SkyShaderGuard(factory: SkyProgramFactory, private val health: SkyShaderHealth = SkyShaderHealth.Shared) {
    var program: SkyShaderProgram? =
        if (health.failed) null else try { factory.create() } catch (e: Exception) { health.reportFailure("compile", e); null }
        private set

    val usable: Boolean get() = program != null && !health.failed

    /** Runs [block] on the program; false (and the fallback for good) if it is unusable or [block] throws. */
    fun run(where: String, block: (SkyShaderProgram) -> Unit): Boolean {
        val p = program ?: return false
        if (health.failed) return false
        return try {
            block(p)
            true
        } catch (e: Exception) {
            program = null
            health.reportFailure(where, e)
            false
        }
    }
}
