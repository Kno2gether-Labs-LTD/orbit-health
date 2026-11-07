package dev.avijit.orbithealth.ui.sky

import dev.avijit.orbithealth.ui.components.Motion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkyClockTest {
    private val ms = 1_000_000L

    private fun clock(animations: Boolean = true) = SkyClock(animationsEnabled = animations, timeline = SkyTimeline())

    @Test
    fun skyPausesWhenNotVisible() {
        val c = clock()
        c.onFrame(1_000 * ms)
        assertEquals(0f, c.time, 1e-6f)
        c.onFrame(1_100 * ms)
        assertEquals(0.1f, c.time, 1e-4f)

        // Scrolled off-screen: frames no longer move the sky.
        c.visible = false
        assertFalse(c.running)
        c.onFrame(1_500 * ms)
        assertEquals(0.1f, c.time, 1e-4f)

        // Back on screen but the activity stopped (lifecycle below STARTED): still frozen.
        c.visible = true
        c.lifecycleStarted = false
        assertFalse(c.running)
        c.onFrame(2_000 * ms)
        assertEquals(0.1f, c.time, 1e-4f)

        // Resumed: the clock follows the shared timeline again.
        c.lifecycleStarted = true
        assertTrue(c.running)
        c.onFrame(3_000 * ms)
        assertEquals(2.0f, c.time, 1e-4f)
    }

    @Test
    fun neighbourPagesHoldAStaticFrame() {
        // Only the current pager page animates (≤ 2 animated skies per screen): an inactive sky holds its frame.
        val c = clock()
        c.active = false
        assertFalse(c.running)
        listOf(0L, 100L, 5_000L).forEach { c.onFrame(it * ms) }
        assertEquals(SkyClock.STATIC_TIME, c.time, 0f)
        c.active = true
        assertTrue(c.running)
        c.onFrame(6_000 * ms)
        c.onFrame(6_100 * ms)
        assertEquals(0.1f, c.time, 1e-4f)
    }

    @Test
    fun reducedMotionGivesStaticFrame() {
        // "Remove animations" (animator duration scale 0) gives a clock that never leaves the static frame.
        assertFalse(Motion.animationsEnabled(0f))
        val c = clock(animations = Motion.animationsEnabled(0f))
        assertFalse(c.running)
        listOf(0L, 40L, 1_000L, 60_000L).forEach { c.onFrame(it * ms) }
        assertEquals(SkyClock.STATIC_TIME, c.time, 0f)
        // The static frame is deterministic: the same geometry every time, with no pulse beat.
        val spec = skySpec(SkyKind.PULSE)
        assertEquals(resolveFrame(spec, 342f, 352f, c.time, 73f), resolveFrame(spec, 342f, 352f, SkyClock.STATIC_TIME, 73f))
        assertEquals(1f, pulseScale(SkyClock.STATIC_TIME, 73f), 1e-6f)
    }

    @Test
    fun clockIsCappedAtThirtyFps() {
        val c = clock()
        c.onFrame(0)
        c.onFrame(16_667_000L) // next 60 Hz vsync: skipped
        assertEquals(0f, c.time, 1e-6f)
        c.onFrame(33_334_000L) // second vsync: ticks
        assertEquals(0.033334f, c.time, 1e-5f)
    }

    @Test
    fun skiesShareOneTimeline() {
        // Two skies on one timeline report the same uTime, so a hero -> detail transition never jumps.
        val tl = SkyTimeline()
        val a = SkyClock(true, tl)
        val b = SkyClock(true, tl)
        a.onFrame(5_000 * ms)
        a.onFrame(6_000 * ms)
        b.onFrame(6_000 * ms)
        assertEquals(a.time, b.time, 0f)
    }

    @Test
    fun inactiveSkySeedsItsHeldFrameFromTheSharedTimeline() {
        // The current page has been live for two minutes when a neighbour page is composed (held, not animating).
        var nanos = 0L
        val tl = SkyTimeline(nanoTime = { nanos })
        val current = SkyClock(true, tl)
        current.onFrame(0)
        nanos = 120_000 * ms
        current.onFrame(nanos)
        assertEquals(120f, current.time, 1e-3f)

        val neighbour = SkyClock(true, tl).apply { active = false }
        neighbour.seed()
        // Its still frame is taken from the shared timeline, not t = 0 ...
        assertEquals(120f, neighbour.time, 1e-3f)
        // ... so when the page becomes current the drift carries on from there instead of snapping two minutes ahead.
        neighbour.active = true
        neighbour.onFrame(120_100 * ms)
        assertEquals(120.1f, neighbour.time, 1e-3f)
    }

    @Test
    fun seedKeepsTheStaticFrameUnderReducedMotion() {
        var nanos = 50_000 * ms
        val tl = SkyTimeline(nanoTime = { nanos })
        tl.seconds(0)
        val c = SkyClock(animationsEnabled = false, timeline = tl)
        c.seed()
        assertEquals(SkyClock.STATIC_TIME, c.time, 0f)
    }
}
