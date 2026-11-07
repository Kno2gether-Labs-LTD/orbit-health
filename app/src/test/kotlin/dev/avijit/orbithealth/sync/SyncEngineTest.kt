package dev.avijit.orbithealth.sync

import dev.avijit.orbithealth.ring.MonitorKind
import dev.avijit.orbithealth.ring.MonitorSetting
import dev.avijit.orbithealth.ring.MonitoringConfig
import dev.avijit.orbithealth.ring.MonitoringNotApplied
import dev.avijit.orbithealth.ring.RingCapabilities
import dev.avijit.orbithealth.ring.DeviceInfo
import dev.avijit.orbithealth.ring.HistoryBatch
import dev.avijit.orbithealth.ring.MeasureEvent
import dev.avijit.orbithealth.ring.MeasureKind
import dev.avijit.orbithealth.ring.Metric
import dev.avijit.orbithealth.ring.RingDriver
import dev.avijit.orbithealth.ring.Sample
import dev.avijit.orbithealth.ring.SampleSource
import dev.avijit.orbithealth.ring.Sex
import dev.avijit.orbithealth.ring.SyncFailed
import dev.avijit.orbithealth.ring.UserProfile
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncEngineTest {
    private val now = Instant.parse("2026-11-05T07:30:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val profile = UserProfile(175, 75, Sex.UNSPECIFIED, 35)
    private val events = mutableListOf<String>()
    private val batch = HistoryBatch(
        listOf(Sample(Metric.HEART_RATE, now.minusSeconds(600), 61.0, SampleSource.HISTORY)), emptyList(), emptyList(),
    )

    private inner class FakeDriver : RingDriver {
        var syncError: Exception? = null
        var handshakeError: Exception? = null
        var handshakeGate: CompletableDeferred<Unit>? = null
        var cleared: HistoryBatch? = null
        var canClear = true
        /** The TK5's schedulable checks, so monitoring tests send what they configure. */
        var monitorSpecs = mapOf(
            MonitorKind.HEART_RATE to dev.avijit.orbithealth.ring.MonitorSpec(listOf(10, 15, 20, 30, 60), true, false),
            MonitorKind.SPO2 to dev.avijit.orbithealth.ring.MonitorSpec(listOf(30, 60), true, false),
        )
        override suspend fun handshake(profile: UserProfile): Pair<DeviceInfo, RingCapabilities> {
            events += "handshake"
            handshakeGate?.await()
            handshakeError?.let { throw it }
            return DeviceInfo(1, "1.0", 90) to RingCapabilities(
                emptySet(), sleep = true, workouts = true, realtime = true, monitoring = monitorSpecs, canClearHistory = canClear,
            )
        }
        override suspend fun syncHistory(): HistoryBatch {
            events += "sync"
            syncError?.let { throw it }
            return batch
        }
        override suspend fun clearSyncedHistory(batch: HistoryBatch) { events += "clear"; cleared = batch }
        var live: Flow<Int> = emptyFlow()
        override fun liveHeartRate(): Flow<Int> = live
        var measureFlow: (MeasureKind) -> Flow<MeasureEvent> = { emptyFlow() }
        override fun measure(kind: MeasureKind): Flow<MeasureEvent> = measureFlow(kind)
        override suspend fun readMonitoring(): MonitoringConfig? = null
        var monitorGate: CompletableDeferred<Unit>? = null
        override suspend fun applyMonitoring(config: MonitoringConfig): MonitoringConfig {
            events += "monitor"
            monitorGate?.await()
            return config
        }
    }

    private val driver = FakeDriver()
    private var connectError: Exception? = null
    private var allowsClear = true
    private val linkUp = mutableListOf<MutableStateFlow<Boolean>>()
    private var ringId: String? = null

    private val connector: suspend () -> RingConnection = {
        events += "connect"
        connectError?.let { throw it }
        val up = MutableStateFlow(true).also { linkUp += it }
        RingConnection(driver, up, allowsClear, ringId) { events += "close"; up.value = false }
    }

    private var storeError: Exception? = null
    private val stored = mutableListOf<HistoryBatch>()
    private val store = BatchStore { b ->
        events += "store"
        storeError?.let { throw it }
        stored += b
    }

    private fun engine(mode: SyncMode, vendorApp: Boolean = false) =
        SyncEngine(connector, store, { mode }, clock, { profile }, { vendorApp })

    @Test fun familyWithoutCanClearHistoryNeverClears() = runTest {
        driver.canClear = false
        val e = engine(SyncMode.EXCLUSIVE)
        e.syncOnce()
        e.syncOnce() // also over the held link
        assertFalse("clear" in events)
        assertEquals(null, driver.cleared)
        assertTrue(e.state.value is SyncState.Synced)
    }

    @Test fun fakeFamilyDefaultsAreNonDestructive() = runTest {
        // A new family that doesn't opt in (RingCapabilities.canClearHistory defaults to false) is never cleared.
        val family = dev.avijit.orbithealth.ring.fake.FakeFamily(capabilities = dev.avijit.orbithealth.ring.RingCapabilities(emptySet(), sleep = false, workouts = false, realtime = false))
        val d = family.driver()
        val e = SyncEngine({ RingConnection(d, MutableStateFlow(true), allowsClear = true) {} }, store, { SyncMode.EXCLUSIVE }, clock, { profile }, { false })
        e.syncOnce()
        assertEquals(listOf("handshake", "sync"), d.calls)
        assertTrue(d.cleared.isEmpty())
        // Once the family opts in, the same engine path clears.
        family.capabilities = family.capabilities.copy(canClearHistory = true)
        e.disconnect()
        e.syncOnce()
        assertEquals(1, d.cleared.size)
    }

    @Test fun capabilitiesFromEveryHandshakeAreReported() = runTest {
        val seen = mutableListOf<dev.avijit.orbithealth.ring.RingCapabilities>()
        val e = SyncEngine(connector, store, { SyncMode.COEXISTENCE }, clock, { profile }, { false }, onCapabilities = { seen += it })
        e.syncOnce()
        assertEquals(1, seen.size)
        assertTrue(seen.single().canClearHistory)
    }

    @Test fun exclusiveModeClearsAfterSync() = runTest {
        val e = engine(SyncMode.EXCLUSIVE)
        e.syncOnce()
        assertEquals(listOf("connect", "handshake", "sync", "store", "clear"), events)
        assertTrue("store must happen before clear", events.indexOf("store") < events.indexOf("clear"))
        assertSame(batch, driver.cleared)
        assertFalse("exclusive keeps the link open", "close" in events)
    }

    @Test fun coexistenceModeNeverClears() = runTest {
        val e = engine(SyncMode.COEXISTENCE)
        e.syncOnce()
        assertEquals(listOf("connect", "handshake", "sync", "store", "close"), events)
        assertEquals(listOf(batch), stored)
        assertEquals(null, driver.cleared)
    }

    @Test fun failedSyncStoresNothingAndReportsFailed() = runTest {
        driver.syncError = SyncFailed("transfer stalled")
        val e = engine(SyncMode.EXCLUSIVE)
        e.syncOnce()
        assertTrue(stored.isEmpty())
        assertFalse("clear" in events)
        val s = e.state.value
        assertTrue("was $s", s is SyncState.Failed && "transfer stalled" in s.reason)
    }

    @Test fun storeFailureNeverClears() = runTest {
        storeError = IllegalStateException("disk full")
        val e = engine(SyncMode.EXCLUSIVE)
        e.syncOnce()
        assertFalse("clear" in events)
        val s = e.state.value
        assertTrue("was $s", s is SyncState.Failed && "disk full" in s.reason)
    }

    @Test fun ringSyncUnaffectedByServerFailure() = runTest {
        // The Orbit Server hook runs after the store committed and before the clear; a throw changes nothing.
        val e = SyncEngine(connector, store, { SyncMode.EXCLUSIVE }, clock, { profile }, { false }, onStored = {
            events += "server"
            throw IllegalStateException("server down")
        })
        e.syncOnce()
        assertEquals(listOf("connect", "handshake", "sync", "store", "server", "clear"), events)
        assertSame(batch, driver.cleared)
        assertEquals(SyncState.Synced(now), e.state.value)
        assertEquals(15.minutes, e.nextDelay())
    }

    @Test fun serverHookOnlyRunsAfterACommittedStore() = runTest {
        var calls = 0
        storeError = IllegalStateException("disk full")
        val e = SyncEngine(connector, store, { SyncMode.EXCLUSIVE }, clock, { profile }, { false }, onStored = { calls++ })
        e.syncOnce()
        assertEquals(0, calls)
        storeError = null
        e.syncOnce()
        assertEquals(1, calls)
    }

    @Test fun successfulSyncEmitsSyncedWithClockTime() = runTest {
        val e = engine(SyncMode.COEXISTENCE)
        e.syncOnce()
        assertEquals(SyncState.Synced(now), e.state.value)
    }

    @Test fun connectionThatDisallowsClearNeverClearsEvenInExclusive() = runTest {
        allowsClear = false // e.g. the debug Mac bridge
        val e = engine(SyncMode.EXCLUSIVE)
        e.syncOnce()
        assertFalse("clear" in events)
        assertEquals(listOf(batch), stored)
    }

    @Test fun debugBridgeForcesCoexistence() {
        assertEquals(SyncMode.COEXISTENCE, syncModeFor(TransportKind.DEBUG_BRIDGE, vendorAppInstalled = false))
        assertEquals(SyncMode.COEXISTENCE, syncModeFor(TransportKind.DEBUG_BRIDGE, vendorAppInstalled = true))
        assertEquals(SyncMode.COEXISTENCE, syncModeFor(TransportKind.BLE, vendorAppInstalled = true))
        assertEquals(SyncMode.EXCLUSIVE, syncModeFor(TransportKind.BLE, vendorAppInstalled = false))
    }

    @Test fun exclusiveReusesLinkWithoutRehandshake() = runTest {
        val e = engine(SyncMode.EXCLUSIVE)
        e.syncOnce(); events.clear()
        e.syncOnce()
        assertEquals(listOf("sync", "store", "clear"), events)
    }

    @Test fun reconnectsAndHandshakesAfterLinkDrop() = runTest {
        val e = engine(SyncMode.EXCLUSIVE)
        e.syncOnce(); events.clear()
        linkUp.last().value = false
        e.syncOnce()
        assertEquals(listOf("close", "connect", "handshake", "sync", "store", "clear"), events)
    }

    @Test fun handshakeFailureClosesLinkAndFails() = runTest {
        driver.handshakeError = IOException("no reply")
        val e = engine(SyncMode.EXCLUSIVE)
        e.syncOnce()
        assertEquals(listOf("connect", "handshake", "close"), events)
        assertTrue(e.state.value is SyncState.Failed)
    }

    @Test fun connectFailureWithVendorAppIsRingBusy() = runTest {
        connectError = IOException("BLE connect timed out")
        val e = engine(SyncMode.COEXISTENCE, vendorApp = true)
        e.syncOnce()
        assertEquals(SyncState.RingBusy, e.state.value)
        assertTrue(stored.isEmpty())
    }

    @Test fun connectFailureBacksOffExponentiallyAndResetsOnSuccess() = runTest {
        val e = engine(SyncMode.EXCLUSIVE)
        assertEquals(15.minutes, e.nextDelay())
        connectError = IOException("not found")
        val delays = (1..7).map { e.syncOnce(); e.nextDelay() }
        assertTrue(e.state.value is SyncState.Failed)
        assertEquals(listOf(30.seconds, 60.seconds, 120.seconds, 240.seconds, 480.seconds, 15.minutes, 15.minutes), delays)
        connectError = null
        e.syncOnce()
        assertEquals(15.minutes, e.nextDelay())
    }

    @Test fun concurrentSyncIsNoOp() = runTest {
        val gate = CompletableDeferred<Unit>()
        val slowStore = BatchStore { gate.await(); stored += it }
        val e = SyncEngine(connector, slowStore, { SyncMode.COEXISTENCE }, clock, { profile }, { false })
        val first = async { e.syncOnce() }
        testScheduler.runCurrent()
        assertEquals(SyncState.Syncing, e.state.value)
        assertFalse("second call while running must be a no-op", e.syncOnce())
        gate.complete(Unit)
        assertTrue(first.await())
        assertEquals(1, events.count { it == "sync" })
    }

    @Test fun modeChangeToCoexistenceDuringSyncPreventsClear() = runTest {
        var current = SyncMode.EXCLUSIVE
        val flippingStore = BatchStore { events += "store"; stored += it; current = SyncMode.COEXISTENCE }
        val e = SyncEngine(connector, flippingStore, { current }, clock, { profile }, { false })
        e.syncOnce()
        assertEquals(listOf("connect", "handshake", "sync", "store", "close"), events)
        assertEquals(null, driver.cleared)
    }

    @Test fun reconnectAfterDropUsesBackoff() = runTest {
        val e = SyncEngine(connector, store, { SyncMode.EXCLUSIVE }, clock, { profile }, { false }, backgroundScope)
        e.syncOnce()
        testScheduler.runCurrent()
        events.clear()
        connectError = IOException("out of range")
        linkUp.last().value = false // the held EXCLUSIVE link drops

        advanceTimeBy(29.seconds); testScheduler.runCurrent()
        assertTrue("no retry before 30 s: $events", events.isEmpty())
        advanceTimeBy(2.seconds) // t = 31 s: first retry (fails)
        assertEquals(listOf("close", "connect"), events)
        assertTrue(e.state.value is SyncState.Failed)

        advanceTimeBy(58.seconds) // t = 89 s
        assertEquals(1, events.count { it == "connect" })
        advanceTimeBy(2.seconds) // t = 91 s: second retry 60 s after the first (fails)
        assertEquals(2, events.count { it == "connect" })

        connectError = null
        advanceTimeBy(118.seconds) // t = 209 s
        assertEquals(2, events.count { it == "connect" })
        advanceTimeBy(2.seconds) // t = 211 s: third retry 120 s later succeeds
        assertEquals(listOf("close", "connect", "connect", "connect", "handshake", "sync", "store", "clear"), events)
        assertTrue(e.state.value is SyncState.Synced)
        assertEquals(15.minutes, e.nextDelay())

        advanceTimeBy(60.minutes) // no further retries once synced
        assertEquals(3, events.count { it == "connect" })
    }

    @Test fun liveHeartRateUsesTheLinkBlocksSyncsAndClosesInCoexistence() = runTest {
        val gate = CompletableDeferred<Unit>()
        driver.live = kotlinx.coroutines.flow.flow { emit(71); gate.await(); emit(72) }
        val e = engine(SyncMode.COEXISTENCE)
        val readings = async { e.liveHeartRate().toList() }
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("connect", "handshake"), events)
        assertFalse("a sync while measuring is a no-op", e.syncOnce())
        gate.complete(Unit)
        assertEquals(listOf(71, 72), readings.await())
        assertEquals(listOf("connect", "handshake", "close"), events)
        assertTrue("the link is free again", e.syncOnce())
    }

    @Test fun resultIsStoredAsMeasured() = runTest {
        val bp = mapOf(Metric.BLOOD_PRESSURE_SYS to 117.0, Metric.BLOOD_PRESSURE_DIA to 78.0, Metric.HEART_RATE to 88.0)
        driver.measureFlow = { kotlinx.coroutines.flow.flowOf(MeasureEvent.Started, MeasureEvent.Live(bp), MeasureEvent.Result(bp)) }
        val e = engine(SyncMode.COEXISTENCE)
        val got = e.measure(MeasureKind.BLOOD_PRESSURE).toList()
        assertEquals(MeasureEvent.Result(bp), got.last())
        // Every metric of the result, MEASURED, at the time it arrived; Live values are not stored.
        assertEquals(1, stored.size)
        assertEquals(
            bp.map { (m, v) -> Sample(m, now, v, SampleSource.MEASURED) }.toSet(),
            stored.single().samples.toSet(),
        )
        assertEquals(listOf("connect", "handshake", "store", "close"), events)
    }

    @Test fun measureHoldsTheLinkSoSyncsWaitAndCancelStopsIt() = runTest {
        var stopped = false
        driver.measureFlow = {
            kotlinx.coroutines.flow.flow {
                emit(MeasureEvent.Started)
                try { kotlinx.coroutines.awaitCancellation() } finally { stopped = true }
            }
        }
        val e = engine(SyncMode.COEXISTENCE)
        val job = launch { e.measure(MeasureKind.SPO2).collect {} }
        testScheduler.advanceUntilIdle()
        assertFalse("a sync while measuring is a no-op", e.syncOnce())
        job.cancel(); job.join()
        assertTrue("cancelling the reading cancels the driver's measure (its stop frame)", stopped)
        assertTrue(stored.isEmpty())
        assertTrue("the link is free again", e.syncOnce())
    }

    @Test fun measureFailsCalmlyWhenBusyOrUnreachable() = runTest {
        connectError = IOException("no ring")
        assertEquals(
            listOf(MeasureEvent.Failed(dev.avijit.orbithealth.ring.MeasureFailure.DISCONNECTED)),
            engine(SyncMode.EXCLUSIVE).measure(MeasureKind.HEART_RATE).toList(),
        )
        assertEquals(
            listOf(MeasureEvent.Failed(dev.avijit.orbithealth.ring.MeasureFailure.BUSY)),
            engine(SyncMode.COEXISTENCE, vendorApp = true).measure(MeasureKind.HEART_RATE).toList(),
        )
        // A sync holding the link (longer than the short wait for a stopping reading): BUSY, nothing sent.
        connectError = null
        driver.handshakeGate = CompletableDeferred()
        val e = engine(SyncMode.COEXISTENCE)
        val sync = launch { e.syncOnce() }
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(MeasureEvent.Failed(dev.avijit.orbithealth.ring.MeasureFailure.BUSY)), e.measure(MeasureKind.SPO2).toList())
        driver.handshakeGate!!.complete(Unit); sync.join()
    }

    @Test fun aReadingStartedWhileTheLastOneIsStillStoppingWaitsInsteadOfBusy() = runTest {
        // The previous reading was cancelled but not joined: its (slow) stop frame still holds the engine lock.
        val stops = mutableListOf<MeasureKind>()
        driver.measureFlow = { kind ->
            kotlinx.coroutines.flow.flow {
                emit(MeasureEvent.Started)
                try {
                    kotlinx.coroutines.awaitCancellation()
                } finally {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { kotlinx.coroutines.delay(2_000); stops += kind }
                }
            }
        }
        val e = engine(SyncMode.COEXISTENCE)
        val first = launch { e.measure(MeasureKind.HEART_RATE).collect {} }
        testScheduler.runCurrent()
        first.cancel()
        val second = async { e.measure(MeasureKind.SPO2).first() }
        testScheduler.advanceTimeBy(2_500)
        testScheduler.runCurrent()
        assertEquals(MeasureEvent.Started, second.await())
        assertEquals(listOf(MeasureKind.HEART_RATE), stops)
    }

    @Test fun liveHeartRateReportsUnreachableAndBusyRings() = runTest {
        connectError = IOException("no ring")
        val e = engine(SyncMode.EXCLUSIVE)
        val notConnected = runCatching { e.liveHeartRate().toList() }.exceptionOrNull()
        assertTrue("was $notConnected", notConnected is LiveUnavailable.NotConnected)
        val busy = runCatching { engine(SyncMode.COEXISTENCE, vendorApp = true).liveHeartRate().toList() }.exceptionOrNull()
        assertTrue("was $busy", busy is LiveUnavailable.Busy)
    }

    @Test fun cancelDuringHandshakeClosesLinkAndNextSyncHandshakes() = runTest {
        driver.handshakeGate = CompletableDeferred() // never completes: the handshake hangs
        val e = engine(SyncMode.EXCLUSIVE)
        val job = launch { e.liveHeartRate().collect {} }
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("connect", "handshake"), events)
        job.cancel()
        job.join()
        assertEquals("the half-open link is closed", listOf("connect", "handshake", "close"), events)

        driver.handshakeGate = null
        events.clear()
        assertTrue(e.syncOnce())
        assertEquals("a fresh link, handshaken before syncing", listOf("connect", "handshake", "sync", "store", "clear"), events)
    }

    @Test fun cancelMidStreamInCoexistenceClosesLink() = runTest {
        driver.live = kotlinx.coroutines.flow.flow { emit(70); kotlinx.coroutines.awaitCancellation() }
        val e = engine(SyncMode.COEXISTENCE)
        val got = mutableListOf<Int>()
        val job = launch { e.liveHeartRate().collect { got += it } }
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(70), got)
        assertFalse("close" in events)
        job.cancel()
        job.join()
        assertEquals(listOf("connect", "handshake", "close"), events)
        assertTrue("the lock is released", e.syncOnce())
    }

    @Test fun handshakeReportsDeviceInfoAndLinkedTracksHeldLink() = runTest {
        val infos = mutableListOf<DeviceInfo>()
        val e = SyncEngine(connector, store, { SyncMode.EXCLUSIVE }, clock, { profile }, { false }, onDeviceInfo = { infos += it })
        assertFalse(e.linked.value)
        e.syncOnce()
        assertEquals(listOf(DeviceInfo(1, "1.0", 90)), infos)
        assertTrue("exclusive holds the link", e.linked.value)
        e.disconnect()
        assertFalse(e.linked.value)
    }

    @Test fun coexistenceIsNotLinkedAfterSync() = runTest {
        val e = engine(SyncMode.COEXISTENCE)
        e.syncOnce()
        assertFalse(e.linked.value)
    }

    @Test fun applyMonitoringOnlySendsChangedConfig() = runTest {
        var want = MonitoringPrefs(10, true)
        val sent = mutableListOf<MonitoringPrefs>()
        val d = object : RingDriver by driver {
            override suspend fun applyMonitoring(config: MonitoringConfig): MonitoringConfig {
                sent += config.toPrefs()
                return config
            }
        }
        val conn: suspend () -> RingConnection = { RingConnection(d, MutableStateFlow(true), true) {} }
        val e = SyncEngine(conn, store, { SyncMode.EXCLUSIVE }, clock, { profile }, { false }, monitoring = { want })
        e.syncOnce()
        assertEquals(listOf(MonitoringPrefs(10, true)), sent) // applied once on the new link
        assertTrue(e.applyMonitoringNow() is MonitoringApplyResult.Applied)
        assertEquals(1, sent.size) // unchanged: not re-sent
        want = MonitoringPrefs(15, false)
        assertTrue(e.applyMonitoringNow() is MonitoringApplyResult.Applied) // over the held link
        assertEquals(MonitoringPrefs(15, false), sent.last())
        assertEquals(2, sent.size)
    }

    @Test fun storedFiveMinuteIntervalMigratesToTenAndIsPersisted() = runTest {
        var stored = MonitoringPrefs(5, true)
        val sent = mutableListOf<MonitoringConfig>()
        val d = object : RingDriver by driver {
            override suspend fun applyMonitoring(config: MonitoringConfig): MonitoringConfig = config.also { sent += it }
        }
        val conn: suspend () -> RingConnection = { RingConnection(d, MutableStateFlow(true), true) {} }
        val e = SyncEngine(
            conn, store, { SyncMode.EXCLUSIVE }, clock, { profile }, { false },
            monitoring = { stored }, onMonitoringPrefsMigrated = { stored = it },
        )
        assertTrue(e.syncOnce())
        assertEquals(MonitoringPrefs(10, true), stored) // persisted
        assertEquals(listOf(MonitoringPrefs(10, true).toRingConfig()), sent) // 5 never reaches the ring
    }

    @Test fun migrationIsPersistedInCoexistenceToo() = runTest {
        var stored = MonitoringPrefs(5, false)
        val e = SyncEngine(
            { RingConnection(driver, MutableStateFlow(true), true) {} }, store, { SyncMode.COEXISTENCE }, clock, { profile }, { false },
            monitoring = { stored }, onMonitoringPrefsMigrated = { stored = it },
        )
        e.syncOnce()
        assertEquals(MonitoringPrefs(10, false), stored)
        assertFalse("COEXISTENCE leaves the ring's schedule alone", "monitor" in events)
    }

    @Test fun storedScheduleIsWhatTheRingAccepted() = runTest {
        // The ring answers FC for SpO₂: only heart rate is accepted, and only that is stored.
        val d = object : RingDriver by driver {
            override suspend fun applyMonitoring(config: MonitoringConfig) =
                MonitoringConfig(config.settings - MonitorKind.SPO2)
        }
        var accepted: MonitoringConfig? = null
        val e = SyncEngine(
            { RingConnection(d, MutableStateFlow(true), true) {} }, store, { SyncMode.EXCLUSIVE }, clock, { profile }, { false },
            monitoring = { MonitoringPrefs(15, true) }, onMonitoringAccepted = { accepted = it },
        )
        e.syncOnce()
        assertEquals(MonitoringConfig(mapOf(MonitorKind.HEART_RATE to MonitorSetting(true, 15))), accepted)
    }

    @Test fun transientMonitoringRefusalIsRetriedButUnsupportedIsNot() = runTest {
        // First sync: SpO₂ refused with a retryable answer -> sent again at the next sync. Then accepted -> not resent.
        var refuse = true
        val sent = mutableListOf<MonitoringConfig>()
        val d = object : RingDriver by driver {
            override suspend fun applyMonitoring(config: MonitoringConfig): MonitoringConfig {
                sent += config
                val accepted = MonitoringConfig(config.settings - MonitorKind.SPO2)
                if (refuse) throw MonitoringNotApplied(accepted, setOf(MonitorKind.SPO2))
                return config
            }
        }
        val accepted = mutableListOf<MonitoringConfig>()
        val e = SyncEngine(
            { RingConnection(d, MutableStateFlow(true), true) {} }, store, { SyncMode.EXCLUSIVE }, clock, { profile }, { false },
            monitoring = { MonitoringPrefs(15, true) }, onMonitoringAccepted = { accepted += it },
        )
        assertTrue(e.syncOnce())
        assertTrue(e.state.value is SyncState.Synced)
        assertEquals(MonitoringConfig(mapOf(MonitorKind.HEART_RATE to MonitorSetting(true, 15))), accepted.last())
        val afterFirst = sent.size
        assertTrue("retried within the same sync", afterFirst >= 2)
        refuse = false
        e.syncOnce()
        val afterSecond = sent.size
        assertTrue("retried at the next sync", afterSecond > afterFirst)
        e.syncOnce()
        assertEquals("accepted: not resent", afterSecond, sent.size)

        // An FC-dropped kind (left out of the result, no throw) counts as done and is not resent.
        val fcSent = mutableListOf<MonitoringConfig>()
        val fc = object : RingDriver by driver {
            override suspend fun applyMonitoring(config: MonitoringConfig): MonitoringConfig {
                fcSent += config
                return MonitoringConfig(config.settings - MonitorKind.SPO2)
            }
        }
        val e2 = SyncEngine(
            { RingConnection(fc, MutableStateFlow(true), true) {} }, store, { SyncMode.EXCLUSIVE }, clock, { profile }, { false },
            monitoring = { MonitoringPrefs(15, true) },
        )
        e2.syncOnce(); e2.syncOnce()
        assertEquals(1, fcSent.size)
    }

    @Test fun monitoringFailureNeverBreaksSync() = runTest {
        val d = object : RingDriver by driver {
            override suspend fun applyMonitoring(config: MonitoringConfig): MonitoringConfig =
                throw IllegalArgumentException("interval not offered")
        }
        var accepted: MonitoringConfig? = null
        val e = SyncEngine(
            { RingConnection(d, MutableStateFlow(true), true) {} }, store, { SyncMode.EXCLUSIVE }, clock, { profile }, { false },
            monitoring = { MonitoringPrefs(10, true) }, onMonitoringAccepted = { accepted = it },
            onMonitoringPrefsMigrated = { error("not migrated") },
        )
        assertTrue(e.syncOnce())
        assertTrue(e.state.value is SyncState.Synced)
        assertNull(accepted)
        // A throwing store callback is swallowed too.
        val e2 = SyncEngine(
            { RingConnection(driver, MutableStateFlow(true), true) {} }, store, { SyncMode.EXCLUSIVE }, clock, { profile }, { false },
            monitoring = { MonitoringPrefs(5, true) }, onMonitoringAccepted = { error("disk full") },
            onMonitoringPrefsMigrated = { error("disk full") },
        )
        assertTrue(e2.syncOnce())
        assertTrue(e2.state.value is SyncState.Synced)
    }

    @Test fun anIntervalTheDriverRejectsIsReportedAsInvalidNotUnreachable() = runTest {
        val d = object : RingDriver by driver {
            override suspend fun applyMonitoring(config: MonitoringConfig): MonitoringConfig =
                throw IllegalArgumentException("interval not offered")
        }
        val e = SyncEngine(
            { RingConnection(d, MutableStateFlow(true), false) {} }, store, { SyncMode.COEXISTENCE }, clock, { profile }, { false },
            monitoring = { MonitoringPrefs(15, true) },
        )
        assertEquals(MonitoringApplyResult.Invalid(MonitoringPrefs(15, true).schedule), e.applyMonitoringNow())
    }

    @Test fun anotherRingOfTheSameModelGetsTheSettingsAfresh() = runTest {
        // Both rings report the same DeviceInfo.deviceId (a model code on YCBT); only the address tells them apart.
        val sent = mutableListOf<String?>()
        val d = object : RingDriver by driver {
            override suspend fun applyMonitoring(config: MonitoringConfig): MonitoringConfig = config.also { sent += ringId }
        }
        val links = mutableListOf<MutableStateFlow<Boolean>>()
        val e = SyncEngine(
            { val up = MutableStateFlow(true).also { links += it }; RingConnection(d, up, true, ringId) { up.value = false } },
            store, { SyncMode.EXCLUSIVE }, clock, { profile }, { false },
            monitoring = { MonitoringPrefs(10, true) },
        )
        ringId = "AA:01"
        e.syncOnce()
        links.last().value = false // the link drops
        ringId = "AA:01"
        e.syncOnce()
        assertEquals(listOf<String?>("AA:01"), sent) // same ring: not re-sent
        links.last().value = false
        ringId = "AA:02"
        e.syncOnce()
        assertEquals(listOf<String?>("AA:01", "AA:02"), sent) // another ring, same model code: sent afresh
    }

    @Test fun applyNowConnectsInCoexistenceAndReturnsWhatTheRingAccepted() = runTest {
        // The ring takes heart rate but drops SpO₂ (FC): the result and the stored schedule say only heart rate.
        val family = dev.avijit.orbithealth.ring.fake.FakeFamily(
            capabilities = dev.avijit.orbithealth.ring.fake.FakeFamily.caps(
                monitoring = mapOf(
                    MonitorKind.HEART_RATE to dev.avijit.orbithealth.ring.MonitorSpec(listOf(10, 15, 20, 30, 60), true, false),
                    MonitorKind.SPO2 to dev.avijit.orbithealth.ring.MonitorSpec(listOf(30, 60), true, false),
                ),
            ),
        )
        val d = family.driver().apply { accept = { MonitoringConfig(it.settings - MonitorKind.SPO2) } }
        val stored = mutableListOf<MonitoringConfig>()
        val e = SyncEngine(
            { events += "connect"; RingConnection(d, MutableStateFlow(true), false) { events += "close" } },
            store, { SyncMode.COEXISTENCE }, clock, { profile }, { false },
            monitoring = { MonitoringPrefs(15, true) }, onMonitoringAccepted = { stored += it },
        )
        val r = e.applyMonitoringNow()
        val hr15 = MonitoringConfig(mapOf(MonitorKind.HEART_RATE to MonitorSetting(true, 15)))
        assertEquals(MonitoringApplyResult.Applied(MonitoringPrefs(15, true).schedule, hr15), r)
        assertEquals(listOf(hr15), stored)
        assertEquals(listOf("connect", "close"), events) // COEXISTENCE: connected for it, then let the vendor app back
        assertFalse(e.linked.value)
        // Asked again with nothing changed: answered from what the ring took, nothing re-sent.
        assertEquals(MonitoringApplyResult.Applied(MonitoringPrefs(15, true).schedule, hr15), e.applyMonitoringNow())
        assertEquals(1, d.applied.size)
    }

    @Test fun applyNowWhenTheRingIsOutOfReachKeepsItForTheNextConnect() = runTest {
        val sent = mutableListOf<MonitoringConfig>()
        val d = object : RingDriver by driver {
            override suspend fun applyMonitoring(config: MonitoringConfig): MonitoringConfig = config.also { sent += it }
        }
        var reachable = false
        val e = SyncEngine(
            { if (!reachable) throw IOException("ring not found"); RingConnection(d, MutableStateFlow(true), false) {} },
            store, { SyncMode.COEXISTENCE }, clock, { profile }, { false },
            monitoring = { MonitoringPrefs(15, true) },
        )
        assertEquals(MonitoringApplyResult.NotConnected, e.applyMonitoringNow())
        assertTrue(sent.isEmpty())
        reachable = true
        e.syncOnce() // the next sync sends it, even in COEXISTENCE
        assertEquals(listOf(MonitoringPrefs(15, true).schedule), sent)
    }

    @Test fun applyNowWithTheVendorAppHoldingTheRingIsBusy() = runTest {
        val e = SyncEngine(
            { throw IOException("ring not found") }, store, { SyncMode.COEXISTENCE }, clock, { profile }, { true },
            monitoring = { MonitoringPrefs(15, true) },
        )
        assertEquals(MonitoringApplyResult.Busy, e.applyMonitoringNow())
    }

    @Test fun applyNowRefusedIsReportedAndRetried() = runTest {
        var refuse = true
        val d = object : RingDriver by driver {
            override suspend fun applyMonitoring(config: MonitoringConfig): MonitoringConfig {
                val accepted = MonitoringConfig(config.settings - MonitorKind.HEART_RATE)
                if (refuse) throw MonitoringNotApplied(accepted, setOf(MonitorKind.HEART_RATE))
                return config
            }
        }
        val e = SyncEngine(
            { RingConnection(d, MutableStateFlow(true), false) {} }, store, { SyncMode.COEXISTENCE }, clock, { profile }, { false },
            monitoring = { MonitoringPrefs(15, true) },
        )
        val r = e.applyMonitoringNow()
        assertTrue(r is MonitoringApplyResult.Refused)
        assertEquals(setOf(MonitorKind.HEART_RATE), (r as MonitoringApplyResult.Refused).refused)
        assertEquals(MonitoringConfig(mapOf(MonitorKind.SPO2 to MonitorSetting(true, 30))), r.accepted)
        refuse = false
        assertTrue(e.applyMonitoringNow() is MonitoringApplyResult.Applied)
    }

    @Test fun storedIntervalOutsideTheRingsCapsSnapsBeforeSendingAndIsPersisted() = runTest {
        // The handshake's caps allow SpO₂ only every 30 or 60 (and HR 10/15/20/30/60): a stored 45 goes out as 60, 12 as 10.
        val caps = RingCapabilities(
            emptySet(), sleep = true, workouts = false, realtime = true,
            monitoring = mapOf(
                MonitorKind.HEART_RATE to dev.avijit.orbithealth.ring.MonitorSpec(listOf(10, 15, 20, 30, 60), true, false),
                MonitorKind.SPO2 to dev.avijit.orbithealth.ring.MonitorSpec(listOf(30, 60), true, false),
            ),
        )
        var stored = MonitoringPrefs(
            MonitoringConfig(mapOf(MonitorKind.HEART_RATE to MonitorSetting(true, 12), MonitorKind.SPO2 to MonitorSetting(true, 45))),
        )
        val sent = mutableListOf<MonitoringConfig>()
        val d = object : RingDriver by driver {
            override suspend fun handshake(profile: UserProfile) = DeviceInfo(1, "1.0", 90) to caps
            override suspend fun applyMonitoring(config: MonitoringConfig): MonitoringConfig = config.also { sent += it }
        }
        val e = SyncEngine(
            { RingConnection(d, MutableStateFlow(true), false) {} }, store, { SyncMode.COEXISTENCE }, clock, { profile }, { false },
            monitoring = { stored }, onMonitoringPrefsMigrated = { stored = it },
        )
        e.applyMonitoringNow()
        val want = MonitoringConfig(mapOf(MonitorKind.HEART_RATE to MonitorSetting(true, 10), MonitorKind.SPO2 to MonitorSetting(true, 60)))
        assertEquals(listOf(want), sent)
        assertEquals(MonitoringPrefs(want), stored)
    }

    @Test fun kindsMissingFromCapsOrWithoutIntervalsAreNeverSent() = runTest {
        // The ring declares HR, and SpO₂ with no intervals (not really supported); the prefs also hold HRV.
        val sent = mutableListOf<MonitoringConfig>()
        driver.monitorSpecs = mapOf(
            MonitorKind.HEART_RATE to dev.avijit.orbithealth.ring.MonitorSpec(listOf(10, 15, 30, 60), true, false),
            MonitorKind.SPO2 to dev.avijit.orbithealth.ring.MonitorSpec(emptyList(), true, false),
        )
        val d = object : RingDriver by driver {
            override suspend fun applyMonitoring(config: MonitoringConfig): MonitoringConfig = config.also { sent += it }
        }
        val prefs = MonitoringPrefs(
            MonitoringConfig(
                mapOf(
                    MonitorKind.HEART_RATE to MonitorSetting(true, 15),
                    MonitorKind.SPO2 to MonitorSetting(true, 30),
                    MonitorKind.HRV to MonitorSetting(true, 60),
                ),
            ),
        )
        val e = SyncEngine(
            { RingConnection(d, MutableStateFlow(true), false) {} }, store, { SyncMode.COEXISTENCE }, clock, { profile }, { false },
            monitoring = { prefs },
        )
        val hr15 = MonitoringConfig(mapOf(MonitorKind.HEART_RATE to MonitorSetting(true, 15)))
        assertEquals(MonitoringApplyResult.Applied(hr15, hr15), e.applyMonitoringNow())
        assertEquals(listOf(hr15), sent)
    }

    @Test fun aRingWithNothingSchedulableIsNeverSentAFrame() = runTest {
        driver.monitorSpecs = emptyMap()
        val e = SyncEngine(
            { RingConnection(driver, MutableStateFlow(true), false) {} }, store, { SyncMode.COEXISTENCE }, clock, { profile }, { false },
            monitoring = { MonitoringPrefs(15, true) },
        )
        assertEquals(MonitoringApplyResult.Applied(MonitoringConfig.EMPTY, MonitoringConfig.EMPTY), e.applyMonitoringNow())
        assertFalse("monitor" in events)
    }

    @Test fun readMonitoringOnlyUsesAHeldLink() = runTest {
        val reported = MonitoringConfig(mapOf(MonitorKind.HEART_RATE to MonitorSetting(true, 20)))
        val d = object : RingDriver by driver {
            override suspend fun readMonitoring(): MonitoringConfig = reported
        }
        var connects = 0
        val conn: suspend () -> RingConnection = { connects++; RingConnection(d, MutableStateFlow(true), false) {} }
        val coex = SyncEngine(conn, store, { SyncMode.COEXISTENCE }, clock, { profile }, { false })
        assertNull("no link held: never connects just to ask", coex.readMonitoring())
        assertEquals(0, connects)
        val excl = SyncEngine(conn, store, { SyncMode.EXCLUSIVE }, clock, { profile }, { false })
        excl.syncOnce() // EXCLUSIVE keeps the link
        assertEquals(reported, excl.readMonitoring())
    }

    private fun monitoringEngine(mode: SyncMode, sent: MutableList<MonitoringPrefs>): SyncEngine {
        val d = object : RingDriver by driver {
            override suspend fun applyMonitoring(config: MonitoringConfig): MonitoringConfig {
                sent += config.toPrefs()
                return config
            }
        }
        val conn: suspend () -> RingConnection = { RingConnection(d, MutableStateFlow(true), true) {} }
        return SyncEngine(conn, store, { mode }, clock, { profile }, { mode == SyncMode.COEXISTENCE }, monitoring = { MonitoringPrefs(10, true) })
    }

    @Test fun exclusiveAutoAppliesOnFirstConnect() = runTest {
        val sent = mutableListOf<MonitoringPrefs>()
        monitoringEngine(SyncMode.EXCLUSIVE, sent).syncOnce()
        assertEquals(listOf(MonitoringPrefs(10, true)), sent)
    }

    @Test fun coexistenceDoesNotAutoApplyMonitoring() = runTest {
        val sent = mutableListOf<MonitoringPrefs>()
        val e = monitoringEngine(SyncMode.COEXISTENCE, sent)
        e.syncOnce()
        e.syncOnce()
        assertTrue("SmartHealth's ring settings are left alone until the user changes one in Me", sent.isEmpty())
        e.applyMonitoringNow() // the user changed a setting: it connects for it, even in COEXISTENCE
        assertEquals(listOf(MonitoringPrefs(10, true)), sent)
        e.syncOnce()
        e.syncOnce()
        assertEquals("sent once, not at every sync", 1, sent.size)
    }

    /** A clock the test can move forward. */
    private class MutableClock(var at: Instant) : Clock() {
        override fun instant(): Instant = at
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId?) = this
    }

    /** Ring-side setup commands each driver call costs on the wire (YcbtDriver: time, tz, user, info, caps; hr + spo2). */
    private fun setupCommands(evts: List<String>) =
        evts.sumOf { when (it) { "handshake" -> 5; "monitor" -> 2; else -> 0 }.toInt() }

    @Test fun cancelDuringMonitoringSetupOnFreshLiveLinkClosesLink() = runTest {
        driver.monitorGate = CompletableDeferred() // applyMonitoring hangs
        val e = SyncEngine(connector, store, { SyncMode.EXCLUSIVE }, clock, { profile }, { false }, monitoring = { MonitoringPrefs(10, true) })
        val job = launch { e.liveHeartRate().collect {} }
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("connect", "handshake", "monitor"), events)
        job.cancel()
        job.join()
        assertEquals("the half-set-up link is closed", listOf("connect", "handshake", "monitor", "close"), events)
        assertFalse(e.linked.value)
    }

    @Test fun liveHrOnFreshLinkAfterRecentSyncSkipsFullHandshake() = runTest {
        ringId = "AA:BB:CC:DD:EE:FF"
        val c = MutableClock(now)
        val e = SyncEngine(connector, store, { SyncMode.COEXISTENCE }, c, { profile }, { false }, monitoring = { MonitoringPrefs(10, true) })
        e.syncOnce() // full handshake, then the COEXISTENCE link is closed
        assertEquals(listOf("connect", "handshake", "sync", "store", "close"), events)

        c.at = now.plus(java.time.Duration.ofHours(2))
        events.clear()
        driver.live = kotlinx.coroutines.flow.flow { emit(66) }
        assertEquals(listOf(66), e.liveHeartRate().toList())
        assertEquals(listOf("connect", "close"), events)
        assertTrue("at most 2 setup commands before measuring", setupCommands(events) <= 2)
    }

    @Test fun liveHrDoesFullHandshakeWhenStaleOrOtherRing() = runTest {
        ringId = "AA:BB:CC:DD:EE:FF"
        val c = MutableClock(now)
        val e = SyncEngine(connector, store, { SyncMode.COEXISTENCE }, c, { profile }, { false })
        e.syncOnce()
        driver.live = kotlinx.coroutines.flow.flow { emit(66) }

        c.at = now.plus(java.time.Duration.ofHours(7)) // older than 6 h
        events.clear()
        e.liveHeartRate().toList()
        assertEquals(listOf("connect", "handshake", "close"), events)

        ringId = "11:22:33:44:55:66" // a different ring, just after a handshake with the first
        events.clear()
        e.liveHeartRate().toList()
        assertEquals(listOf("connect", "handshake", "close"), events)
    }

    @Test fun lightLinkHeldInExclusiveIsFullyHandshakenBeforeSync() = runTest {
        ringId = "AA:BB:CC:DD:EE:FF"
        val e = engine(SyncMode.EXCLUSIVE)
        e.syncOnce()
        e.disconnect()
        events.clear()
        driver.live = kotlinx.coroutines.flow.flow { emit(66) }
        e.liveHeartRate().toList()
        assertEquals("light link kept for the next sync", listOf("connect"), events)
        events.clear()
        e.syncOnce()
        assertEquals(listOf("handshake", "sync", "store", "clear"), events)
    }
}
