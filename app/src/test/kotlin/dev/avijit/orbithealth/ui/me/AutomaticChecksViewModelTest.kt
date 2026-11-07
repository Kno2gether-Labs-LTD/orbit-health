package dev.avijit.orbithealth.ui.me

import dev.avijit.orbithealth.prefs.SettingsSnapshot
import dev.avijit.orbithealth.ring.MonitorKind
import dev.avijit.orbithealth.ring.MonitorSetting
import dev.avijit.orbithealth.ring.MonitorSpec
import dev.avijit.orbithealth.ring.MonitoringConfig
import dev.avijit.orbithealth.ring.RingCapabilities
import dev.avijit.orbithealth.ring.Sex
import dev.avijit.orbithealth.ring.UserProfile
import dev.avijit.orbithealth.ring.fake.FakeFamily
import dev.avijit.orbithealth.sync.BatchStore
import dev.avijit.orbithealth.sync.MonitoringApplyResult
import dev.avijit.orbithealth.sync.RingConnection
import dev.avijit.orbithealth.sync.SyncEngine
import dev.avijit.orbithealth.sync.SyncMode
import dev.avijit.orbithealth.sync.monitoringPrefs
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomaticChecksViewModelTest {
    private val now = Instant.parse("2026-11-06T15:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val hrSpec = MonitorSpec(listOf(10, 15, 20, 30, 60), canTurnOff = true, supportsWindow = false)
    private val spo2Spec = MonitorSpec(listOf(30, 60), canTurnOff = true, supportsWindow = false)
    private val tk5Caps = FakeFamily.caps(monitoring = mapOf(MonitorKind.SPO2 to spo2Spec, MonitorKind.HEART_RATE to hrSpec))

    private val settings = MutableStateFlow(SettingsSnapshot(ringAddress = "AA:BB", ringCapabilities = tk5Caps))
    private val saved = mutableListOf<MonitoringConfig>()
    private var reported: MonitoringConfig? = null
    private var apply: suspend () -> MonitoringApplyResult = { MonitoringApplyResult.NotConnected }

    private fun TestScope.vm(
        applyWith: (suspend () -> MonitoringApplyResult)? = null,
        readWith: (suspend () -> MonitoringConfig?)? = null,
    ): AutomaticChecksViewModel {
        val d = UnconfinedTestDispatcher(testScheduler)
        return AutomaticChecksViewModel(
            AutomaticChecksDeps(
                settings = settings,
                saveWanted = { c -> saved += c; settings.update { it.copy(ringMonitoringWanted = c) } },
                readFromRing = readWith ?: { reported },
                apply = applyWith ?: { apply() },
                clock = clock,
                now = flowOf(now),
            ),
            CoroutineScope(backgroundScope.coroutineContext + d),
        )
    }

    /** A real SyncEngine over a FakeFamily ring, writing what it accepted back into [settings] like AppGraph does. */
    private fun engineFor(family: FakeFamily, mode: SyncMode = SyncMode.COEXISTENCE): Pair<SyncEngine, dev.avijit.orbithealth.ring.fake.FakeRingDriver> {
        val driver = family.driver()
        val engine = SyncEngine(
            connector = { RingConnection(driver, MutableStateFlow(true), allowsClear = false) {} },
            store = BatchStore { },
            mode = { mode },
            clock = clock,
            profile = { UserProfile(175, 75, Sex.UNSPECIFIED, 35) },
            vendorAppInstalled = { false },
            monitoring = { settings.value.monitoringPrefs() },
            onMonitoringPrefsMigrated = { p -> settings.update { it.copy(ringMonitoringWanted = p.schedule) } },
            onMonitoringAccepted = { c ->
                settings.update {
                    it.copy(
                        ringMonitoringAccepted = MonitoringConfig(it.ringMonitoringAccepted?.settings.orEmpty() + c.settings),
                        ringMonitoringAcceptedAt = now,
                    )
                }
            },
        )
        return engine to driver
    }

    @Test fun rowsComeFromCapabilities() = runTest {
        // A family with only heart rate: one row, no SpO₂.
        val hrOnly = FakeFamily(capabilities = FakeFamily.caps(monitoring = mapOf(MonitorKind.HEART_RATE to hrSpec)))
        settings.update { it.copy(ringCapabilities = hrOnly.capabilities) }
        val vm = vm()
        assertEquals(listOf(MonitorKind.HEART_RATE), vm.state.value.rows.map { it.kind })
        assertFalse(vm.state.value.rows.single().showOvernight)

        // Heart rate + SpO₂ with a time window (declared SpO₂ first): both rows, in order; only SpO₂ offers "Overnight only".
        val windowed = FakeFamily(
            capabilities = FakeFamily.caps(
                monitoring = mapOf(
                    MonitorKind.SPO2 to MonitorSpec(listOf(30, 60), canTurnOff = true, supportsWindow = true),
                    MonitorKind.HEART_RATE to MonitorSpec(listOf(10, 30), canTurnOff = false, supportsWindow = false),
                ),
            ),
        )
        settings.update { it.copy(ringCapabilities = windowed.capabilities) }
        val rows = vm.state.value.rows
        assertEquals(listOf(MonitorKind.HEART_RATE, MonitorKind.SPO2), rows.map { it.kind })
        assertEquals(listOf("Heart rate", "SpO₂"), rows.map { it.title })
        assertEquals(listOf(false, true), rows.map { it.showOvernight })
        // The switch only where the ring lets the check be turned off.
        assertEquals(listOf(false, true), rows.map { it.showSwitch })

        // The window path (test-only: the TK5 has none) reaches the ring as overnightOnly.
        val (engine, driver) = engineFor(windowed)
        val vm2 = vm(applyWith = { engine.applyMonitoringNow() })
        vm2.setOvernightOnly(MonitorKind.SPO2, true)
        runCurrent()
        assertTrue(driver.applied.last()[MonitorKind.SPO2]!!.overnightOnly)
        assertEquals("Every 30 minutes, overnight", vm2.state.value.rows[1].line)
        assertEquals("Your ring is now checking SpO₂ every 30 minutes, overnight", (vm2.state.value.status as ApplyStatus.Done).message)
    }

    @Test fun onlyAllowedIntervalsOffered() = runTest {
        val vm = vm()
        val (hr, spo2) = vm.state.value.rows
        assertEquals(listOf(10, 15, 20, 30, 60), hr.intervals)
        assertEquals(listOf(30, 60), spo2.intervals)
        // An interval the ring doesn't allow is ignored: nothing saved, nothing sent.
        var applied = 0
        apply = { applied++; MonitoringApplyResult.NotConnected }
        vm.setInterval(MonitorKind.SPO2, 15)
        vm.setInterval(MonitorKind.HEART_RATE, 5)
        runCurrent()
        assertTrue(saved.isEmpty())
        assertEquals(0, applied)
        assertEquals(listOf("15", "min"), AutomaticChecksCopy.chip(15).toList())
        assertEquals(listOf("1", "hr"), AutomaticChecksCopy.chip(60).toList())
    }

    @Test fun showsWhatRingAccepted() = runTest {
        // A real engine and a ring that takes heart rate but turns SpO₂ down (FC: left out of what it accepted).
        val family = FakeFamily(capabilities = tk5Caps)
        val (engine, driver) = engineFor(family)
        driver.accept = { MonitoringConfig(it.settings - MonitorKind.SPO2) }
        val gate = CompletableDeferred<Unit>()
        val vm = vm(applyWith = { gate.await(); engine.applyMonitoringNow() })

        vm.setInterval(MonitorKind.HEART_RATE, 15)
        runCurrent()
        // Quiet sending state, and the row already shows the choice.
        assertEquals(ApplyStatus.Sending, vm.state.value.status)
        assertEquals(15, vm.state.value.rows[0].intervalMin)
        assertEquals("Every 15 minutes, day and night", vm.state.value.rows[0].line)
        gate.complete(Unit)
        runCurrent()

        assertEquals(MonitorSetting(true, 15), driver.applied.last()[MonitorKind.HEART_RATE])
        assertEquals(ApplyStatus.Done("Your ring is now checking heart rate every 15 minutes", ok = true), vm.state.value.status)
        assertEquals(ScheduleSource.LAST_SET, vm.state.value.source)
        assertEquals("Last set from Orbit Health · just now", vm.state.value.caption)
        assertNull(vm.state.value.rows[0].ringLine)

        // Turning SpO₂ off: the ring drops it, so the screen says the ring kept what it had, and shows it under the row.
        vm.setEnabled(MonitorKind.SPO2, false)
        runCurrent()
        val st = vm.state.value
        assertEquals(
            ApplyStatus.Done("Your ring turned down that SpO₂ setting, so it stays as it was on the ring.", ok = false),
            st.status,
        )
        assertFalse(st.rows[1].enabled) // the choice is kept
        assertNull("the ring never accepted SpO₂ from Orbit, so nothing to compare", st.rows[1].ringLine)

        // A ring that reports its own schedule (not the TK5) is shown as read from the ring.
        reported = MonitoringConfig(mapOf(MonitorKind.HEART_RATE to MonitorSetting(true, 20), MonitorKind.SPO2 to MonitorSetting(true, 60)))
        val fresh = vm()
        assertEquals(ScheduleSource.RING, fresh.state.value.source)
        assertEquals("Read from your ring just now", fresh.state.value.caption)
        assertEquals(listOf(20, 60), fresh.state.value.rows.map { it.intervalMin })
    }

    @Test fun fallsBackToLastAppliedWhenRingCantReport() = runTest {
        // Nothing accepted yet: the prefs, captioned as not sent.
        val vm = vm(readWith = { null })
        assertEquals(ScheduleSource.NOT_SENT, vm.state.value.source)
        assertEquals("Not sent to your ring yet", vm.state.value.caption)
        assertEquals(listOf(10, 30), vm.state.value.rows.map { it.intervalMin })

        // The ring accepted something an hour ago: that is what "last set" means, and a pref that differs is flagged.
        settings.update {
            it.copy(
                hrIntervalMin = 15,
                ringMonitoringAccepted = MonitoringConfig(mapOf(MonitorKind.HEART_RATE to MonitorSetting(true, 10), MonitorKind.SPO2 to MonitorSetting(true, 30))),
                ringMonitoringAcceptedAt = now.minusSeconds(3600),
            )
        }
        val st = vm.state.value
        assertEquals(ScheduleSource.LAST_SET, st.source)
        assertEquals("Last set from Orbit Health · 1 hr ago", st.caption)
        assertEquals(15, st.rows[0].intervalMin)
        assertEquals("On your ring now: every 10 minutes", st.rows[0].ringLine)
        assertNull(st.rows[1].ringLine)
    }

    @Test fun storedValueOutsideCapsSnapsToNearestAllowedAndIsSaved() = runTest {
        // A stored SpO₂ every 45 and heart rate every 5 (older builds): 60 and 10, saved at once.
        settings.update {
            it.copy(ringMonitoringWanted = MonitoringConfig(mapOf(MonitorKind.HEART_RATE to MonitorSetting(true, 5), MonitorKind.SPO2 to MonitorSetting(true, 45))))
        }
        val vm = vm()
        val want = MonitoringConfig(mapOf(MonitorKind.HEART_RATE to MonitorSetting(true, 10), MonitorKind.SPO2 to MonitorSetting(true, 60)))
        assertEquals(listOf(want), saved)
        assertEquals(want, settings.value.ringMonitoringWanted)
        assertEquals(listOf(10, 60), vm.state.value.rows.map { it.intervalMin })
        // Values the ring allows are never re-saved.
        saved.clear()
        vm()
        assertTrue(saved.isEmpty())
    }

    @Test fun refusedOrUnreachableIsSaidHumanlyAndTheChoiceIsKept() = runTest {
        val vm = vm()
        apply = { MonitoringApplyResult.NotConnected }
        vm.setInterval(MonitorKind.HEART_RATE, 20)
        runCurrent()
        assertEquals(ApplyStatus.Done(AutomaticChecksCopy.NOT_IN_REACH, ok = false), vm.state.value.status)
        assertEquals(20, settings.value.ringMonitoringWanted!![MonitorKind.HEART_RATE]!!.intervalMin) // kept for the next sync

        apply = { MonitoringApplyResult.Busy }
        vm.setInterval(MonitorKind.HEART_RATE, 30)
        runCurrent()
        assertEquals(ApplyStatus.Done(AutomaticChecksCopy.BUSY, ok = false), vm.state.value.status)

        val sent = settings.value.monitoringPrefs().schedule
        apply = { MonitoringApplyResult.Refused(sent, MonitoringConfig.EMPTY, setOf(MonitorKind.HEART_RATE)) }
        vm.setInterval(MonitorKind.HEART_RATE, 60)
        runCurrent()
        assertEquals(ApplyStatus.Done(AutomaticChecksCopy.TRY_AGAIN, ok = false), vm.state.value.status)
        assertEquals(60, settings.value.ringMonitoringWanted!![MonitorKind.HEART_RATE]!!.intervalMin)

        // The words are sentences with no codes.
        listOf(AutomaticChecksCopy.NOT_IN_REACH, AutomaticChecksCopy.BUSY, AutomaticChecksCopy.TRY_AGAIN).forEach {
            assertTrue(it.endsWith(".") && it.none(Char::isDigit) && "FC" !in it)
        }
        assertEquals("Your ring has stopped checking heart rate on its own",
            AutomaticChecksCopy.result(MonitoringApplyResult.Applied(sent, MonitoringConfig(mapOf(MonitorKind.HEART_RATE to MonitorSetting(false, 10)))), MonitorKind.HEART_RATE).message)
        assertEquals("Off. Only when you measure.", AutomaticChecksCopy.line(MonitorSetting(false, 10)))
        assertEquals("Every hour, day and night", AutomaticChecksCopy.line(MonitorSetting(true, 60)))
    }

    @Test fun unknownCapsOrNoRingShowsACalmEmptyState() = runTest {
        settings.update { it.copy(ringCapabilities = null) }
        val vm = vm()
        assertTrue(vm.state.value.rows.isEmpty())
        assertEquals("Connect your ring to set automatic checks.", vm.state.value.empty)
        settings.update { SettingsSnapshot(ringCapabilities = tk5Caps) } // caps but no paired ring
        assertEquals(AutomaticChecksCopy.NO_RING, vm.state.value.empty)
        settings.update { it.copy(ringAddress = "AA:BB", ringCapabilities = RingCapabilities(emptySet(), true, false, true)) }
        assertEquals(AutomaticChecksCopy.NO_CHECKS, vm.state.value.empty)
    }

    @Test fun quickTapsSaveInOrderSoTheLastTapWins() = runTest {
        // The first save is slow; a second tap lands meanwhile. Saves never overlap, and the final one is the last tap.
        val gate = CompletableDeferred<Unit>()
        var saving = 0
        var overlapped = false
        val sentWith = mutableListOf<Int>()
        val d = UnconfinedTestDispatcher(testScheduler)
        val vm = AutomaticChecksViewModel(
            AutomaticChecksDeps(
                settings = settings,
                saveWanted = { c ->
                    if (saving++ > 0) overlapped = true
                    if (c[MonitorKind.HEART_RATE]!!.intervalMin == 15) gate.await()
                    settings.update { it.copy(ringMonitoringWanted = c) }
                    saving--
                },
                readFromRing = { null },
                apply = { sentWith += settings.value.ringMonitoringWanted!![MonitorKind.HEART_RATE]!!.intervalMin; MonitoringApplyResult.NotConnected },
                clock = clock,
                now = flowOf(now),
            ),
            CoroutineScope(backgroundScope.coroutineContext + d),
        )
        vm.setInterval(MonitorKind.HEART_RATE, 15)
        vm.setInterval(MonitorKind.HEART_RATE, 30)
        vm.setInterval(MonitorKind.HEART_RATE, 60)
        runCurrent()
        assertEquals(60, vm.state.value.rows[0].intervalMin) // the rows follow the taps at once
        gate.complete(Unit)
        runCurrent()
        assertFalse(overlapped)
        assertEquals(60, settings.value.ringMonitoringWanted!![MonitorKind.HEART_RATE]!!.intervalMin)
        assertEquals("the ring gets the last tap", 60, sentWith.last())
        assertTrue("conflated: 30 was replaced before it was taken", 30 !in sentWith)
    }

    @Test fun reportedValueOutsideCapsIsSnappedForDisplayAndNeverSaved() = runTest {
        // A ring that can report its schedule says heart rate every 5 min, which it doesn't list as allowed.
        val family = FakeFamily(capabilities = tk5Caps)
        val driver = family.driver().apply {
            monitoring = MonitoringConfig(mapOf(MonitorKind.HEART_RATE to MonitorSetting(true, 5), MonitorKind.SPO2 to MonitorSetting(true, 60)))
        }
        val vm = vm(readWith = { driver.readMonitoring() })
        val hr = vm.state.value.rows[0]
        assertEquals(ScheduleSource.RING, vm.state.value.source)
        assertEquals(10, hr.intervalMin) // nearest allowed, so a chip is selected
        assertEquals("On your ring now: every 5 minutes", hr.ringLine)
        // The in-caps SpO₂ value is adopted; the 5 min heart rate is never saved as wanted.
        assertTrue(saved.none { it[MonitorKind.HEART_RATE]?.intervalMin == 5 })
        assertEquals(60, settings.value.ringMonitoringWanted!![MonitorKind.SPO2]!!.intervalMin)
        // The switch still works.
        apply = { MonitoringApplyResult.NotConnected }
        vm.setEnabled(MonitorKind.HEART_RATE, false)
        runCurrent()
        assertFalse(settings.value.ringMonitoringWanted!![MonitorKind.HEART_RATE]!!.enabled)
    }

    @Test fun aKindWithNoIntervalsIsTreatedAsUnsupported() = runTest {
        settings.update {
            it.copy(ringCapabilities = FakeFamily.caps(monitoring = mapOf(
                MonitorKind.HEART_RATE to MonitorSpec(emptyList(), canTurnOff = true, supportsWindow = false),
                MonitorKind.SPO2 to spo2Spec,
            )))
        }
        val vm = vm()
        assertEquals(listOf(MonitorKind.SPO2), vm.state.value.rows.map { it.kind })
        assertEquals("SpO₂ every 30 min", MeMath.autoChecks(settings.value))
        // Only empty ones: the ring has no settable checks.
        settings.update { it.copy(ringCapabilities = FakeFamily.caps(monitoring = mapOf(MonitorKind.HEART_RATE to MonitorSpec(emptyList(), true, false)))) }
        assertEquals(AutomaticChecksCopy.NO_CHECKS, vm.state.value.empty)
        assertEquals(null, MeMath.autoChecks(settings.value))
    }

    @Test fun oneShortTimeFormatEverywhere() {
        assertEquals("10 min", AutomaticChecksCopy.short(10))
        assertEquals("1 hr", AutomaticChecksCopy.short(60))
        assertEquals("every 10 minutes", AutomaticChecksCopy.every(10))
        assertEquals("every hour", AutomaticChecksCopy.every(60))
        val s = settings.value.copy(spo2Overnight = true, ringMonitoringWanted = MonitoringConfig(mapOf(
            MonitorKind.HEART_RATE to MonitorSetting(true, 60), MonitorKind.SPO2 to MonitorSetting(true, 30),
        )))
        assertEquals("Heart rate every 1 hr · SpO₂ every 30 min", MeMath.autoChecks(s))
    }

    @Test fun aSettingTheDriverRejectsIsSaidHonestly() {
        val done = AutomaticChecksCopy.result(MonitoringApplyResult.Invalid(MonitoringConfig.EMPTY), MonitorKind.HEART_RATE)
        assertEquals(AutomaticChecksCopy.INVALID, done.message)
        assertTrue(done.message.startsWith("Orbit couldn't build that setting for your ring"))
        assertFalse(done.ok)
    }

    @Test fun readingsWithoutAScheduleAreNamedOnce() {
        val spec = dev.avijit.orbithealth.ring.MeasureSpec(typicalSeconds = 30, live = false, estimate = false)
        val caps = tk5Caps.copy(measure = dev.avijit.orbithealth.ring.MeasureKind.entries.associateWith { spec })
        assertEquals(
            "Your ring can't check blood pressure, HRV or stress on a schedule. Measure them any time with Measure now on Body › Heart.",
            AutomaticChecksCopy.measureOnly(caps),
        )
        val bpOnly = caps.copy(measure = mapOf(dev.avijit.orbithealth.ring.MeasureKind.BLOOD_PRESSURE to spec, dev.avijit.orbithealth.ring.MeasureKind.HEART_RATE to spec))
        assertEquals("Your ring can't check blood pressure on a schedule. Measure it any time with Measure now on Body › Heart.", AutomaticChecksCopy.measureOnly(bpOnly))
        assertEquals(null, AutomaticChecksCopy.measureOnly(caps.copy(measure = mapOf(dev.avijit.orbithealth.ring.MeasureKind.HEART_RATE to spec))))
    }
}
