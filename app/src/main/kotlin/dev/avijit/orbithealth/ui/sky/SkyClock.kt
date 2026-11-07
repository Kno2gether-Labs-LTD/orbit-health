package dev.avijit.orbithealth.ui.sky

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.avijit.orbithealth.ui.components.rememberMotionEnabled
import kotlinx.coroutines.flow.first

/**
 * One time origin for every sky in the process, so a sky that continues across a hero → detail transition keeps the
 * same uTime (tokens §6, shared element). Set on the first frame any sky ever draws.
 */
class SkyTimeline(private val nanoTime: () -> Long = System::nanoTime) {
    private var originNanos = Long.MIN_VALUE

    fun seconds(frameTimeNanos: Long): Float {
        if (originNanos == Long.MIN_VALUE) originNanos = frameTimeNanos
        return ((frameTimeNanos - originNanos) / 1e9).toFloat()
    }

    /** The timeline's seconds right now, off the frame clock (Choreographer frame times share the nanoTime base). */
    fun secondsNow(): Float = seconds(nanoTime())

    companion object {
        val Shared = SkyTimeline()
    }
}

/**
 * Drives a sky's `uTime`. It ticks at most every [frameIntervalNanos] (30 fps: skies are slow, half the frames save
 * half the GPU work) and only while [running]: animations on, the sky [visible] on screen and [active], and its
 * lifecycle at least STARTED. With animations off ("Remove animations") it never leaves [STATIC_TIME], so the sky is a still frame.
 *
 * [time] is snapshot state: draw code that reads it is invalidated on each tick, without recomposition.
 */
@Stable
class SkyClock(
    val animationsEnabled: Boolean,
    private val timeline: SkyTimeline = SkyTimeline.Shared,
    private val frameIntervalNanos: Long = FRAME_INTERVAL_NANOS,
) {
    var visible by mutableStateOf(true)
    var lifecycleStarted by mutableStateOf(true)
    /** False holds the frame (a pager neighbour peeking in: only the current page's sky animates). */
    var active by mutableStateOf(true)

    val running: Boolean get() = animationsEnabled && visible && lifecycleStarted && active

    var time by mutableFloatStateOf(STATIC_TIME)
        private set

    private var lastTick = Long.MIN_VALUE

    fun onFrame(frameTimeNanos: Long) {
        if (!running) {
            lastTick = Long.MIN_VALUE
            return
        }
        if (lastTick != Long.MIN_VALUE && frameTimeNanos - lastTick < frameIntervalNanos - VSYNC_SLACK_NANOS) return
        lastTick = frameTimeNanos
        time = timeline.seconds(frameTimeNanos)
    }

    /**
     * Takes the held frame from the shared timeline instead of t = 0, so a sky composed while inactive (a pager
     * neighbour) shows the same moment as the live one and carries on from there when it becomes current, with no
     * drift snap. A no-op under reduced motion (the static frame stays) or once the clock has ticked.
     */
    fun seed() {
        if (!animationsEnabled || lastTick != Long.MIN_VALUE || seeded) return
        seeded = true
        time = timeline.secondsNow()
    }

    private var seeded = false

    /** Ticks on every frame while running; suspends (no frame callbacks at all) while paused. */
    suspend fun run() {
        seed()
        while (true) {
            snapshotFlow { running }.first { it }
            withFrameNanos(::onFrame)
        }
    }

    companion object {
        /** The frozen frame for reduced motion and API 31–32 fallbacks: all drifts at their start (k = 0), no beat. */
        const val STATIC_TIME = 0f
        const val FRAME_INTERVAL_NANOS = 33_333_333L
        /** A 60 Hz vsync pair lands a hair under 33.3 ms; accept it rather than skip to the third frame. */
        private const val VSYNC_SLACK_NANOS = 2_000_000L
    }
}

/**
 * A [SkyClock] wired to the system: static when animations are off or in previews, paused below STARTED. Visibility is
 * reported by the sky itself (see [Sky]), from its on-screen bounds.
 */
@Composable
fun rememberSkyClock(): SkyClock {
    val motion = rememberMotionEnabled()
    val clock = remember(motion) { SkyClock(animationsEnabled = motion) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(clock, lifecycle) {
        lifecycle.currentStateFlow.collect { clock.lifecycleStarted = it.isAtLeast(Lifecycle.State.STARTED) }
    }
    LaunchedEffect(clock) { if (clock.animationsEnabled) clock.run() }
    return clock
}
