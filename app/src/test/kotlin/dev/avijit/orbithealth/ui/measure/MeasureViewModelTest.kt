package dev.avijit.orbithealth.ui.measure

import dev.avijit.orbithealth.ring.MeasureEvent
import dev.avijit.orbithealth.ring.MeasureFailure
import dev.avijit.orbithealth.ring.MeasureKind
import dev.avijit.orbithealth.ring.MeasureSpec
import dev.avijit.orbithealth.ring.Metric
import dev.avijit.orbithealth.ring.RingCapabilities
import dev.avijit.orbithealth.ring.Sample
import dev.avijit.orbithealth.ring.SampleSource
import dev.avijit.orbithealth.ring.fake.FakeFamily
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import dev.avijit.orbithealth.ring.RingDriver
import dev.avijit.orbithealth.ring.Sex
import dev.avijit.orbithealth.ring.UserProfile
import dev.avijit.orbithealth.sync.BatchStore
import dev.avijit.orbithealth.sync.RingConnection
import dev.avijit.orbithealth.sync.SyncEngine
import dev.avijit.orbithealth.sync.SyncMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MeasureViewModelTest {
    private val now = Instant.parse("2026-11-06T14:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val tk5: RingCapabilities = FakeFamily.caps(
        measure = mapOf(
            MeasureKind.BLOOD_PRESSURE to MeasureSpec(50, live = true, estimate = true),
            MeasureKind.STRESS to MeasureSpec(60, live = false, estimate = true),
            MeasureKind.HRV to MeasureSpec(60, live = true, estimate = false),
            MeasureKind.SPO2 to MeasureSpec(45, live = true, estimate = false),
            MeasureKind.HEART_RATE to MeasureSpec(30, live = true, estimate = false),
        ),
    )
    private val caps: MutableStateFlow<RingCapabilities?> = MutableStateFlow(tk5)
    private var history: List<Sample> = emptyList()
    private var measure: (MeasureKind) -> Flow<MeasureEvent> = { flowOf<MeasureEvent>() }

    private fun TestScope.vm(): MeasureViewModel {
        val d = UnconfinedTestDispatcher(testScheduler)
        return MeasureViewModel(
            MeasureDeps(
                capabilities = caps,
                measure = { k: MeasureKind -> measure(k) },
                samples = { m, from, to -> flowOf(history.filter { it.metric == m && it.time >= from && it.time <= to }) },
                clock = clock,
            ),
            CoroutineScope(backgroundScope.coroutineContext + d),
        )
    }

    @Test fun tilesComeFromCapabilities() = runTest {
        // A fake family with only HR and SpO₂: only those tiles, in the spec's order.
        val family = FakeFamily(
            capabilities = FakeFamily.caps(
                measure = mapOf(
                    MeasureKind.SPO2 to MeasureSpec(45, live = true, estimate = false),
                    MeasureKind.HEART_RATE to MeasureSpec(30, live = true, estimate = false),
                ),
            ),
        )
        caps.value = family.capabilities
        val vm = vm()
        assertEquals(listOf(MeasureKind.HEART_RATE, MeasureKind.SPO2), vm.state.value.tiles.map { it.kind })
        assertEquals(listOf(30, 45), vm.state.value.tiles.map { it.typicalSeconds })

        // The full TK5 set, ordered HR, SpO₂, HRV, Stress, BP whatever the map order; BP and stress are estimates.
        caps.value = tk5
        val tiles = vm.state.value.tiles
        assertEquals(MeasureCopy.ORDER, tiles.map { it.kind })
        assertEquals(setOf(MeasureKind.STRESS, MeasureKind.BLOOD_PRESSURE), tiles.filter { it.estimate }.map { it.kind }.toSet())

        // Starting a kind the ring doesn't declare never reaches the ring.
        caps.value = family.capabilities
        var asked = false
        measure = { asked = true; flowOf<MeasureEvent>() }
        vm.start(MeasureKind.BLOOD_PRESSURE)
        runCurrent()
        assertFalse(asked)
        assertEquals(MeasurePhase.Failed(MeasureKind.BLOOD_PRESSURE, MeasureFailure.UNSUPPORTED), vm.state.value.phase)
    }

    @Test fun bpResultShowsEstimateAndSysDia() = runTest {
        val bp = mapOf(Metric.BLOOD_PRESSURE_SYS to 117.0, Metric.BLOOD_PRESSURE_DIA to 78.0, Metric.HEART_RATE to 88.0)
        measure = { flowOf(MeasureEvent.Started, MeasureEvent.Live(bp), MeasureEvent.Result(bp)) }
        val vm = vm()
        vm.open(MeasureKind.BLOOD_PRESSURE, autoStart = true)
        runCurrent()
        val done = vm.state.value.phase as MeasurePhase.Done
        assertEquals(MeasureKind.BLOOD_PRESSURE, done.kind)
        assertEquals("117/78 mmHg", MeasureCopy.valueWithUnit(done.kind, done.values))
        assertEquals("Heart rate 88 bpm", MeasureCopy.secondary(done.kind, done.values))
        assertTrue(vm.state.value.tiles.first { it.kind == MeasureKind.BLOOD_PRESSURE }.estimate)
        assertEquals(MeasureCopy.NEUTRAL, done.context) // no history yet
    }

    @Test fun leavingSheetStopsMeasurement() = runTest {
        var collecting = false
        var stopped = false
        measure = {
            flow {
                collecting = true
                emit(MeasureEvent.Started)
                try { awaitCancellation() } finally { stopped = true }
            }
        }
        val vm = vm()
        vm.open(MeasureKind.HEART_RATE, autoStart = true)
        runCurrent()
        advanceTimeBy(5_500)
        assertTrue(collecting)
        val m = vm.state.value.phase as MeasurePhase.Measuring
        assertTrue(m.started)
        assertEquals(5, m.elapsedSeconds)
        vm.dismiss()
        runCurrent()
        assertTrue("collection cancelled, so the ring stops measuring", stopped)
        assertFalse(vm.state.value.visible)
        assertEquals(MeasurePhase.Picker, vm.state.value.phase)
    }

    @Test fun switchingTilesStopsTheRunningReadingFirst() = runTest {
        val stopped = mutableListOf<MeasureKind>()
        measure = { kind -> flow { emit(MeasureEvent.Started); try { awaitCancellation() } finally { stopped += kind } } }
        val vm = vm()
        vm.open(MeasureKind.HEART_RATE, autoStart = true)
        runCurrent()
        vm.start(MeasureKind.SPO2)
        runCurrent()
        assertEquals(listOf(MeasureKind.HEART_RATE), stopped)
        assertEquals(MeasureKind.SPO2, (vm.state.value.phase as MeasurePhase.Measuring).kind)
        vm.showPicker()
        runCurrent()
        assertEquals(listOf(MeasureKind.HEART_RATE, MeasureKind.SPO2), stopped)
    }

    @Test fun switchingReadingsMidMeasureWaitsForTheStopInsteadOfRingBusy() = runTest {
        // The REAL SyncEngine over a FakeFamily ring whose stop takes 2 s: the second reading must not say "busy".
        val family = FakeFamily(capabilities = tk5)
        val stops = mutableListOf<MeasureKind>()
        val base = family.driver()
        val slowStop = object : RingDriver by base {
            override fun measure(kind: MeasureKind): Flow<MeasureEvent> = flow {
                emit(MeasureEvent.Started)
                try {
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) { delay(2_000); stops += kind }
                }
            }
        }
        val engine = SyncEngine(
            { RingConnection(slowStop, MutableStateFlow(true), false) {} },
            BatchStore {}, { SyncMode.COEXISTENCE }, clock, { UserProfile(175, 75, Sex.UNSPECIFIED, 35) }, { false },
        )
        measure = engine::measure
        val vm = vm()
        vm.open(MeasureKind.HEART_RATE, autoStart = true)
        runCurrent()
        assertTrue((vm.state.value.phase as MeasurePhase.Measuring).started)
        vm.start(MeasureKind.SPO2)
        runCurrent()
        assertEquals(MeasureKind.SPO2, (vm.state.value.phase as MeasurePhase.Measuring).kind) // shown at once
        advanceTimeBy(2_500)
        runCurrent()
        assertEquals(listOf(MeasureKind.HEART_RATE), stops)
        val phase = vm.state.value.phase
        assertTrue("not busy: $phase", phase is MeasurePhase.Measuring && phase.kind == MeasureKind.SPO2 && phase.started)
        vm.dismiss()
        advanceTimeBy(2_500)
        runCurrent()
        assertEquals(listOf(MeasureKind.HEART_RATE, MeasureKind.SPO2), stops)
    }

    @Test fun failureCopyIsHuman() = runTest {
        val snug = "Couldn't get a reading. Is the ring snug on your finger?"
        assertEquals(snug, MeasureCopy.failure(MeasureFailure.NOT_WORN, MeasureKind.HEART_RATE))
        assertEquals(snug, MeasureCopy.failure(MeasureFailure.TIMEOUT, MeasureKind.SPO2))
        assertEquals(
            "Your ring isn't connected right now. Keep it nearby and try again.",
            MeasureCopy.failure(MeasureFailure.DISCONNECTED, MeasureKind.HRV),
        )
        assertEquals("Your ring is busy right now. Try again in a moment.", MeasureCopy.failure(MeasureFailure.BUSY, MeasureKind.STRESS))
        for (r in MeasureFailure.entries) for (k in MeasureKind.entries) {
            val copy = MeasureCopy.failure(r, k)
            assertFalse("no codes in \"$copy\"", copy.contains(r.name) || copy.contains("_") || copy.any { it.isDigit() })
            assertTrue("a sentence: \"$copy\"", copy.first().isUpperCase() && copy.last() in ".?")
        }
        // And the view model reaches the copy through the reason the ring gave.
        measure = { flowOf(MeasureEvent.Started, MeasureEvent.Failed(MeasureFailure.TIMEOUT)) }
        val vm = vm()
        vm.open(MeasureKind.SPO2, autoStart = true)
        runCurrent()
        val f = vm.state.value.phase as MeasurePhase.Failed
        assertEquals(snug, MeasureCopy.failure(f.reason, f.kind))
    }

    @Test fun resultIsComparedWithTheUsualRange() = runTest {
        history = (1..20).map { Sample(Metric.HEART_RATE, now.minusSeconds(3_600L * it), 60.0 + it % 10, SampleSource.HISTORY) }
        measure = { flowOf(MeasureEvent.Started, MeasureEvent.Result(mapOf(Metric.HEART_RATE to 64.0))) }
        val vm = vm()
        vm.start(MeasureKind.HEART_RATE)
        runCurrent()
        assertEquals(MeasureCopy.WITHIN, (vm.state.value.phase as MeasurePhase.Done).context)
        assertEquals("A little higher than usual.", MeasureCopy.contextLine(MeasureKind.HEART_RATE, mapOf(Metric.HEART_RATE to 73.0), history.map { it.value }))
        assertEquals("Higher than usual for you.", MeasureCopy.contextLine(MeasureKind.HEART_RATE, mapOf(Metric.HEART_RATE to 95.0), history.map { it.value }))
        val spo2 = List(20) { 97.0 + it % 3 }
        assertEquals("In the healthy range, a touch below your usual.", MeasureCopy.contextLine(MeasureKind.SPO2, mapOf(Metric.SPO2 to 96.0), spo2))
        val spo2Low = List(20) { 95.0 + it % 3 }
        assertEquals("In the healthy range, at the top of your usual.", MeasureCopy.contextLine(MeasureKind.SPO2, mapOf(Metric.SPO2 to 99.0), spo2Low))
    }

    @Test fun valuesAreFormattedPerKind() {
        assertEquals("98%", MeasureCopy.valueWithUnit(MeasureKind.SPO2, mapOf(Metric.SPO2 to 98.0)))
        assertEquals("7.1", MeasureCopy.value(MeasureKind.STRESS, mapOf(Metric.STRESS to 7.1)))
        assertEquals("41 ms", MeasureCopy.valueWithUnit(MeasureKind.HRV, mapOf(Metric.HRV to 41.0)))
        assertEquals("about 30 s", MeasureCopy.duration(30))
        assertEquals("12 s left", MeasureCopy.remaining(30, 18))
        assertEquals(MeasureCopy.LONGER, MeasureCopy.remaining(30, 31))
    }
}
