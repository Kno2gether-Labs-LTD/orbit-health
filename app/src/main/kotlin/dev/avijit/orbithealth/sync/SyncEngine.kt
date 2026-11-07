package dev.avijit.orbithealth.sync

import dev.avijit.orbithealth.ring.DeviceInfo
import dev.avijit.orbithealth.ring.HistoryBatch
import dev.avijit.orbithealth.ring.MeasureEvent
import dev.avijit.orbithealth.ring.MeasureFailure
import dev.avijit.orbithealth.ring.MeasureKind
import dev.avijit.orbithealth.ring.Metric
import dev.avijit.orbithealth.ring.Sample
import dev.avijit.orbithealth.ring.SampleSource
import dev.avijit.orbithealth.ring.MonitorKind
import dev.avijit.orbithealth.ring.MonitorSetting
import dev.avijit.orbithealth.ring.MonitorSpec
import dev.avijit.orbithealth.ring.MonitoringConfig
import dev.avijit.orbithealth.ring.MonitoringNotApplied
import dev.avijit.orbithealth.ring.RingCapabilities
import dev.avijit.orbithealth.ring.RingDriver
import dev.avijit.orbithealth.ring.UserProfile
import java.time.Clock
import java.time.Instant
import kotlin.time.Duration
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Why live heart rate could not start: no reachable ring, or the link is in use (a sync, or the vendor app). */
sealed class LiveUnavailable(message: String) : Exception(message) {
    class NotConnected(reason: String) : LiveUnavailable(reason)
    class Busy : LiveUnavailable("ring busy")
}

/**
 * The user's automatic-check choices (Me › My ring › Automatic checks): the wanted [schedule], one [MonitorSetting] per
 * kind. Installs from before M4 stored only a heart-rate interval (always on) and an SpO₂ switch; the
 * `(hrIntervalMin, spo2Overnight)` constructor builds that schedule (SpO₂ every [SPO2_INTERVAL_MIN] when on).
 */
data class MonitoringPrefs(val schedule: MonitoringConfig) {
    constructor(hrIntervalMin: Int, spo2Overnight: Boolean) : this(
        MonitoringConfig(
            mapOf(
                MonitorKind.HEART_RATE to MonitorSetting(enabled = true, intervalMin = hrIntervalMin),
                MonitorKind.SPO2 to MonitorSetting(enabled = spo2Overnight, intervalMin = SPO2_INTERVAL_MIN),
            ),
        ),
    )

    /**
     * The ring schedule these prefs ask for. With the ring's monitor [specs], only the kinds it declares with at least
     * one interval: a kind that is missing from the specs, or declared with no intervals, is never sent.
     */
    fun toRingConfig(specs: Map<MonitorKind, MonitorSpec>? = null): MonitoringConfig =
        if (specs == null) schedule
        else MonitoringConfig(schedule.settings.filterKeys { specs[it]?.intervalsMin?.isNotEmpty() == true })

    /**
     * These prefs with values the ring can't take replaced. With the ring's monitor [specs], every interval snaps to the
     * nearest allowed one ([snapInterval]), a kind that can't be turned off is on, and "overnight only" is dropped where
     * the ring has no time window. Without specs, only the retired 5 min heart-rate interval becomes 10.
     */
    fun migrated(specs: Map<MonitorKind, MonitorSpec>? = null): MonitoringPrefs {
        val out = schedule.settings.mapValues { (kind, s) ->
            // A spec with no intervals means the ring doesn't really support the kind: left as it is (never sent).
            val spec = specs?.get(kind)?.takeIf { it.intervalsMin.isNotEmpty() }
            when {
                spec != null -> snapSetting(s, spec)
                kind == MonitorKind.HEART_RATE && s.intervalMin == RETIRED_HR_INTERVAL_MIN -> s.copy(intervalMin = 10)
                else -> s
            }
        }
        return if (out == schedule.settings) this else MonitoringPrefs(MonitoringConfig(out))
    }

    companion object {
        /** SpO₂ check interval when on (verified on the TK5: `0x0126 [01, 1e]`). Was 60 before M2. */
        const val SPO2_INTERVAL_MIN = 30
        private const val RETIRED_HR_INTERVAL_MIN = 5

        /** [s] made acceptable to [spec]: interval snapped, on if it can't be off, no overnight without a window. */
        fun snapSetting(s: MonitorSetting, spec: MonitorSpec): MonitorSetting = s.copy(
            intervalMin = snapInterval(s.intervalMin, spec.intervalsMin),
            enabled = s.enabled || !spec.canTurnOff,
            overnightOnly = s.overnightOnly && spec.supportsWindow,
        )

        /**
         * The allowed interval nearest [min]; on a tie the longer one (fewer checks, kinder to the battery). [min] itself
         * when it is allowed or nothing is declared.
         */
        fun snapInterval(min: Int, allowed: List<Int>): Int =
            if (allowed.isEmpty() || min in allowed) min
            else allowed.minWith(compareBy<Int>({ kotlin.math.abs(it - min) }, { -it }))
    }
}

/** What became of the user's automatic-check settings when [SyncEngine.applyMonitoringNow] sent them. */
sealed interface MonitoringApplyResult {
    /** The ring took [accepted]. It can be a subset of [sent]: a kind the ring doesn't support is left out (not retried). */
    data class Applied(val sent: MonitoringConfig, val accepted: MonitoringConfig) : MonitoringApplyResult

    /** The ring turned down [refused] for now ([accepted] went through). Sent again at the next sync. */
    data class Refused(val sent: MonitoringConfig, val accepted: MonitoringConfig, val refused: Set<MonitorKind>) : MonitoringApplyResult

    /** The ring couldn't be reached, or stopped answering. Sent at the next connect. */
    data object NotConnected : MonitoringApplyResult

    /** The vendor app holds the ring. Sent at the next connect. */
    data object Busy : MonitoringApplyResult

    /**
     * The driver rejected [sent] before sending anything (it fails the family's own validation, e.g. an interval the
     * ring doesn't offer). Nothing reached the ring; this is an app-side problem, not a reachability one.
     */
    data class Invalid(val sent: MonitoringConfig) : MonitoringApplyResult
}

/** Persists a batch atomically; must only return once the batch is committed. */
fun interface BatchStore { suspend fun store(batch: HistoryBatch) }

/**
 * A connected (and not yet handshaken) link to the ring. [allowsClear] = false marks links that must
 * never delete ring history whatever the mode (the debug Mac bridge). [ringId] identifies the physical
 * ring (BLE address) so a recent handshake with the same ring can be reused for a quick live reading;
 * null means "unknown" and always gets a full handshake.
 */
class RingConnection(
    val driver: RingDriver,
    val connected: StateFlow<Boolean>,
    val allowsClear: Boolean,
    val ringId: String? = null,
    private val onClose: () -> Unit,
) {
    fun close() = onClose()
}

/**
 * Orchestrates one history sync and, only when safe, the ring-side clear.
 *
 * Per [syncOnce]: connect if there is no live link (handshake on every new link) → `syncHistory()` →
 * `store.store(batch)` → only if the mode is EXCLUSIVE (read at the start AND again right before
 * clearing), the link [RingConnection.allowsClear], the link's latest full handshake declared
 * [RingCapabilities.canClearHistory] (families are non-destructive until they opt in), and the store returned normally:
 * `clearSyncedHistory(batch)` with that same batch instance. COEXISTENCE never clears and disconnects
 * after each sync; EXCLUSIVE keeps the link open for the next sync.
 *
 * Concurrency: one sync at a time. A [syncOnce] call made while another runs is a no-op and returns
 * false immediately (the running sync already covers it).
 *
 * Reconnect trigger: with a [scope], a link held after an EXCLUSIVE sync is watched; if it drops, the
 * engine retries (reconnect + sync) after the failure backoff (30 s, doubling to 15 min) until a sync
 * succeeds or the mode is no longer EXCLUSIVE, instead of waiting for the next periodic tick.
 *
 * Ring settings: [monitoring] is the wanted [MonitoringPrefs]. In EXCLUSIVE it is sent after every handshake and at
 * the start and end of each sync whenever it differs from what this process last sent. In COEXISTENCE (incl. the debug
 * bridge) the vendor app's ring settings are left alone: the config is only sent after the user changed it
 * ([applyMonitoringNow], which connects for it; if that fails, at the next of those points). It goes out through
 * [RingDriver.applyMonitoring] and what the ring accepted goes to [onMonitoringAccepted]; prefs holding a value the
 * ring can't take are snapped to its allowed ones first and persisted through [onMonitoringPrefsMigrated] (in either mode). A monitoring failure
 * never fails a sync. Every handshake's [DeviceInfo] (firmware, battery) goes to [onDeviceInfo]; [linked] says whether a
 * handshaken link is held right now.
 *
 * Light handshake: "Measure now" on a fresh link skips the full handshake (time, time zone, user info, device info,
 * capabilities) when this process fully handshook the same [RingConnection.ringId] within [LIGHT_HANDSHAKE_WINDOW],
 * so the reading starts at once. Such a link is fully handshaken before it is used for a sync.
 */
class SyncEngine(
    private val connector: suspend () -> RingConnection,
    private val store: BatchStore,
    private val mode: () -> SyncMode,
    private val clock: Clock,
    private val profile: suspend () -> UserProfile,
    private val vendorAppInstalled: () -> Boolean,
    private val scope: CoroutineScope? = null,
    private val monitoring: suspend () -> MonitoringPrefs? = { null },
    /** Persists prefs that [MonitoringPrefs.migrated] changed (e.g. a stored 5 min heart-rate interval → 10). */
    private val onMonitoringPrefsMigrated: suspend (MonitoringPrefs) -> Unit = {},
    /** What the ring ACCEPTED from the latest [RingDriver.applyMonitoring] (may be a subset of what was sent). */
    private val onMonitoringAccepted: suspend (MonitoringConfig) -> Unit = {},
    private val onDeviceInfo: suspend (DeviceInfo) -> Unit = {},
    /** The capabilities from every full handshake (cached with the paired ring for offline screens). Display-only. */
    private val onCapabilities: suspend (RingCapabilities) -> Unit = {},
    /**
     * Called once a batch is committed (before any ring clear), e.g. to start the Orbit Server push. Must return at
     * once (launch work elsewhere); a throw is swallowed and never fails the ring sync.
     */
    private val onStored: () -> Unit = {},
) {
    private val _state = MutableStateFlow<SyncState>(SyncState.Idle)
    val state: StateFlow<SyncState> = _state.asStateFlow()

    private val _linked = MutableStateFlow(false)
    /** True while a handshaken link to the ring is held (EXCLUSIVE between syncs, or during a sync / live reading). */
    val linked: StateFlow<Boolean> = _linked.asStateFlow()

    private val mutex = Mutex()
    private var link: RingConnection? = null // guarded by mutex
    private var linkWatch: Job? = null // guarded by mutex; watches the held link for drops
    private var appliedMonitoring: MonitoringPrefs? = null // guarded by mutex; last prefs sent (and answered) this process
    private var lastRingKey: String? = null // guarded by mutex; the ring appliedMonitoring was sent to
    private var userChangedMonitoring = false // guarded by mutex; a Me change not yet accepted by the ring
    private var lastAccepted: MonitoringConfig? = null // guarded by mutex; what the ring took for appliedMonitoring
    private var handshakeCaps: RingCapabilities? = null // guarded by mutex; from this process's latest full handshake
    private var lastFullHandshake: Pair<String, Instant>? = null // guarded by mutex; (ringId, when)
    private var linkNeedsHandshake = false // guarded by mutex; the held link only had a light handshake
    /** guarded by mutex; the held link's family may delete synced history (from its full handshake). */
    private var linkCanClear = false
    @Volatile private var reconnect: Job? = null
    @Volatile private var consecutiveFailures = 0

    /** Runs one sync. Returns false (doing nothing) if a sync is already in progress. */
    suspend fun syncOnce(): Boolean {
        if (!mutex.tryLock()) return false
        try {
            runSync()
        } finally {
            mutex.unlock()
        }
        return true
    }

    /**
     * Live heart rate over this engine's link ("Measure now"). Collecting takes the link the way a sync does: it
     * connects and handshakes if no link is held, and while it runs any [syncOnce] is a no-op. Cancelling stops the
     * measurement; a COEXISTENCE link is closed afterwards, an EXCLUSIVE one kept for the next sync.
     * Throws [LiveUnavailable.Busy] if a sync is running or the vendor app holds the ring, and
     * [LiveUnavailable.NotConnected] if the ring can't be reached. Readings are not stored.
     */
    fun liveHeartRate(): Flow<Int> = flow {
        if (!mutex.tryLock()) throw LiveUnavailable.Busy()
        try {
            val conn = acquireForReading()
            try {
                emitAll(conn.driver.liveHeartRate())
            } finally {
                releaseAfterReading()
            }
        } finally {
            mutex.unlock()
        }
    }

    /**
     * One on-demand reading of [kind] over this engine's link (the Measure sheet): the events of
     * [RingDriver.measure], with the link handled as for [liveHeartRate] (connect and handshake, or the light
     * handshake, if no link is held; any [syncOnce] meanwhile is a no-op). The engine lock is held across the whole
     * reading, so the driver's stop frame goes out before a sync can start. Cancelling stops the measurement on the ring.
     *
     * Never throws for ring trouble: a running sync (or the vendor app holding the ring) is `Failed(BUSY)`, an
     * unreachable ring `Failed(DISCONNECTED)`. A Result is stored, before it is emitted, as [SampleSource.MEASURED]
     * samples (every metric in it, e.g. BP sys and dia plus heart rate) at the time it arrived; it then shows in charts
     * at once and goes to the Orbit Server with the next push.
     */
    fun measure(kind: MeasureKind): Flow<MeasureEvent> = flow {
        // A reading that was just switched away from may still be sending its stop frames: wait a moment for it.
        if (!mutex.tryLock() && withTimeoutOrNull(READING_LOCK_WAIT) { mutex.lock() } == null) {
            emit(MeasureEvent.Failed(MeasureFailure.BUSY))
            return@flow
        }
        try {
            val conn = try {
                acquireForReading()
            } catch (_: LiveUnavailable.Busy) {
                null.also { emit(MeasureEvent.Failed(MeasureFailure.BUSY)) }
            } catch (_: LiveUnavailable.NotConnected) {
                null.also { emit(MeasureEvent.Failed(MeasureFailure.DISCONNECTED)) }
            } ?: return@flow
            try {
                conn.driver.measure(kind).collect { event ->
                    if (event is MeasureEvent.Result) storeMeasured(event.values)
                    emit(event)
                }
            } finally {
                releaseAfterReading()
            }
        } finally {
            mutex.unlock()
        }
    }

    /** Stores a reading as MEASURED samples. A store failure is swallowed: the reading still shows. */
    private suspend fun storeMeasured(values: Map<Metric, Double>) {
        if (values.isEmpty()) return
        val at = clock.instant()
        bestEffort { store.store(HistoryBatch(values.map { (m, v) -> Sample(m, at, v, SampleSource.MEASURED) }, emptyList(), emptyList())) }
    }

    /**
     * The link for a live reading; the caller holds [mutex]. Reuses a held link, else connects and handshakes (or the
     * light handshake for a ring fully handshaken recently). Throws [LiveUnavailable].
     */
    private suspend fun acquireForReading(applyMonitoring: Boolean = true): RingConnection {
        link?.takeIf { it.connected.value }?.let { return it }
        dropLink()
        val fresh = try {
            connector()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw if (vendorAppInstalled()) LiveUnavailable.Busy() else LiveUnavailable.NotConnected(e.describe())
        }
        link = fresh
        try {
            if (recentlyHandshaken(fresh)) {
                _linked.value = true
                linkNeedsHandshake = true // a later sync over this link handshakes fully first
            } else {
                handshake(fresh)
            }
            if (applyMonitoring) ensureMonitoring(fresh)
        } catch (e: CancellationException) {
            // Never leave a half-set-up link behind: the next sync would reuse it as is, and in
            // COEXISTENCE it would keep the vendor app off the ring.
            dropLink()
            throw e
        } catch (e: Exception) {
            dropLink()
            throw LiveUnavailable.NotConnected("handshake: ${e.describe()}")
        }
        return fresh
    }

    /** After a live reading (caller holds [mutex]): a COEXISTENCE link is closed, an EXCLUSIVE one kept and watched. */
    private fun releaseAfterReading() {
        if (mode() != SyncMode.EXCLUSIVE) dropLink() else link?.let { held -> if (linkWatch == null) watch(held) }
    }

    /**
     * Sends the user's automatic-check settings now (Automatic checks screen), in either mode: waits for a running sync
     * or reading to finish, then uses the held link or connects for it (as a reading does; a COEXISTENCE link is let go
     * afterwards). Returns what the ring did with them. Anything not applied stays wanted and goes out again at the next
     * sync or connect. Never throws for ring trouble.
     */
    suspend fun applyMonitoringNow(): MonitoringApplyResult = mutex.withLock {
        userChangedMonitoring = true
        val conn = try {
            acquireForReading(applyMonitoring = false)
        } catch (_: LiveUnavailable.Busy) {
            return@withLock MonitoringApplyResult.Busy
        } catch (_: LiveUnavailable.NotConnected) {
            return@withLock MonitoringApplyResult.NotConnected
        }
        try {
            sendMonitoring(conn) ?: MonitoringApplyResult.NotConnected
        } finally {
            releaseAfterReading()
        }
    }

    /**
     * The ring's own automatic-check schedule, over a link that is already held (a reading never connects just for
     * this). Null when no link is held, the engine is busy, the family can't report it (the TK5), or it fails.
     */
    suspend fun readMonitoring(): MonitoringConfig? {
        if (!mutex.tryLock()) return null
        try {
            val conn = link?.takeIf { it.connected.value } ?: return null
            return try {
                conn.driver.readMonitoring()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        } finally {
            mutex.unlock()
        }
    }

    /** Closes the held link (e.g. when the transport selection changes). Waits for a running sync. */
    suspend fun disconnect() = mutex.withLock { dropLink() }

    /** Delay before the next periodic sync: 15 min normally; after failures 30 s doubling up to 15 min. */
    fun nextDelay(): Duration {
        val n = consecutiveFailures
        if (n == 0) return PERIOD
        val backoff = INITIAL_BACKOFF * (1L shl (n - 1).coerceAtMost(20)).toDouble()
        return if (backoff > PERIOD) PERIOD else backoff
    }

    private suspend fun runSync() {
        val startMode = mode()
        try {
            val conn = link?.takeIf { it.connected.value } ?: run {
                dropLink()
                _state.value = SyncState.Connecting
                val fresh = try {
                    connector()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    fail(if (vendorAppInstalled()) SyncState.RingBusy else SyncState.Failed("connect: ${e.describe()}"))
                    return
                }
                link = fresh
                try {
                    handshake(fresh)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    dropLink()
                    fail(SyncState.Failed("handshake: ${e.describe()}"))
                    return
                }
                fresh
            }
            if (linkNeedsHandshake) {
                // Held link set up by a light handshake for a live reading: set time, tz and profile before syncing.
                try {
                    handshake(conn)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    dropLink()
                    fail(SyncState.Failed("handshake: ${e.describe()}"))
                    return
                }
            }
            ensureMonitoring(conn)

            _state.value = SyncState.Syncing
            val batch = try {
                conn.driver.syncHistory()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                dropLink()
                fail(SyncState.Failed("sync: ${e.describe()}"))
                return
            }

            try {
                store.store(batch)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Not persisted: the ring keeps its history. Never clear.
                dropLink()
                fail(SyncState.Failed("store: ${e.describe()}"))
                return
            }

            try {
                onStored()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The server push is optional; the ring sync already succeeded.
            }

            ensureMonitoring(conn) // picks up a change made while the sync ran, before a COEXISTENCE link is let go

            // Phase two — only after the store committed.
            val exclusive = startMode == SyncMode.EXCLUSIVE && mode() == SyncMode.EXCLUSIVE
            // Clearing needs all three: EXCLUSIVE, a transport that allows it, and a family that declared it.
            if (exclusive && conn.allowsClear && linkCanClear) {
                try {
                    conn.driver.clearSyncedHistory(batch)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Data is already stored; the ring just keeps it and the next sync dedups. Reset the link.
                    dropLink()
                }
            }
            if (!exclusive) dropLink()

            consecutiveFailures = 0
            _state.value = SyncState.Synced(clock.instant())
            link?.let { held -> if (linkWatch == null) watch(held) }
        } catch (e: CancellationException) {
            dropLink()
            _state.value = SyncState.Idle
            throw e
        }
    }

    /** Handshakes on a fresh link, marks it [linked] and reports its [DeviceInfo]. */
    private suspend fun handshake(conn: RingConnection) {
        val (info, caps) = conn.driver.handshake(profile())
        linkCanClear = caps.canClearHistory
        handshakeCaps = caps
        _linked.value = true
        linkNeedsHandshake = false
        lastFullHandshake = conn.ringId?.let { it to clock.instant() }
        // Another ring: send its settings afresh. Keyed on the ring's own id (BLE address); DeviceInfo.deviceId is only a
        // model code on some families (YCBT), so two rings of one model would look the same. Unknown address: deviceId.
        val ringKey = conn.ringId ?: "device:${info.deviceId}"
        if (ringKey != lastRingKey) appliedMonitoring = null
        lastRingKey = ringKey
        try {
            onDeviceInfo(info)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Display-only bookkeeping; never fails a sync.
        }
        try {
            onCapabilities(caps)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Cache only; never fails a sync.
        }
    }

    /**
     * Sends the wanted monitoring config if this process has not sent it yet (in COEXISTENCE only after a user change).
     * Failures retry on the next chance. True when the ring has the current config.
     */
    private suspend fun ensureMonitoring(conn: RingConnection): Boolean = sendMonitoring(conn) is MonitoringApplyResult.Applied

    /**
     * [ensureMonitoring] with its outcome: null when there is nothing to send (no prefs, or COEXISTENCE without a user
     * change). Prefs holding values the ring can't take are snapped to allowed ones and persisted first (either mode).
     */
    private suspend fun sendMonitoring(conn: RingConnection): MonitoringApplyResult? {
        val stored = try {
            monitoring()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return null
        // Monitoring is only ever sent over a link this process handshook (fully, or lightly after a full one), so the
        // latest full handshake's capabilities are always known here.
        val want = stored.migrated(handshakeCaps?.monitoring)
        if (want != stored) bestEffort { onMonitoringPrefsMigrated(want) }
        if (mode() != SyncMode.EXCLUSIVE && !userChangedMonitoring) return null
        val sent = want.toRingConfig(handshakeCaps?.monitoring)
        if (want == appliedMonitoring) {
            userChangedMonitoring = false
            return MonitoringApplyResult.Applied(sent, lastAccepted ?: sent)
        }
        if (sent.settings.isEmpty()) {
            // Nothing the ring can schedule: no frame to send, and nothing to retry.
            appliedMonitoring = want
            lastAccepted = sent
            userChangedMonitoring = false
            return MonitoringApplyResult.Applied(sent, sent)
        }
        return try {
            val accepted = conn.driver.applyMonitoring(sent)
            appliedMonitoring = want
            lastAccepted = accepted
            userChangedMonitoring = false
            bestEffort { onMonitoringAccepted(accepted) }
            MonitoringApplyResult.Applied(sent, accepted)
        } catch (e: CancellationException) {
            throw e
        } catch (e: MonitoringNotApplied) {
            // Partly refused for a reason worth retrying: keep what went through, but send again next time.
            bestEffort { onMonitoringAccepted(e.accepted) }
            MonitoringApplyResult.Refused(sent, e.accepted, e.refused)
        } catch (_: IllegalArgumentException) {
            // The driver's own validation refused the config before sending: not a link problem, so say so honestly.
            MonitoringApplyResult.Invalid(sent)
        } catch (_: Exception) {
            // Never fails a sync: an unreachable ring or a dropped link retries next time.
            MonitoringApplyResult.NotConnected
        }
    }

    /** Runs bookkeeping that must never fail a sync. */
    private suspend fun bestEffort(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    /** True if this process fully handshook the same ring within [LIGHT_HANDSHAKE_WINDOW]. */
    private fun recentlyHandshaken(conn: RingConnection): Boolean {
        val (id, at) = lastFullHandshake ?: return false
        val age = java.time.Duration.between(at, clock.instant())
        return conn.ringId != null && conn.ringId == id && !age.isNegative && age < LIGHT_HANDSHAKE_WINDOW
    }

    private fun fail(s: SyncState) {
        consecutiveFailures++
        _state.value = s
    }

    private fun dropLink() {
        linkWatch?.cancel() // before close(): an intentional close is not a drop
        linkWatch = null
        link?.close()
        link = null
        linkNeedsHandshake = false
        linkCanClear = false
        _linked.value = false
    }

    private fun watch(held: RingConnection) {
        val s = scope ?: return
        linkWatch = s.launch {
            held.connected.first { !it }
            _linked.value = false
            // Separate job: syncOnce() drops the dead link, which cancels this watcher.
            if (reconnect?.isActive != true) reconnect = s.launch { reconnectLoop(held) }
        }
    }

    private suspend fun reconnectLoop(dropped: RingConnection) {
        mutex.withLock { if (link === dropped) consecutiveFailures++ } // first retry after 30 s
        while (true) {
            delay(nextDelay())
            if (mode() != SyncMode.EXCLUSIVE) return // COEXISTENCE: the periodic tick reconnects on demand
            syncOnce()
            if (_state.value is SyncState.Synced) return
        }
    }

    private fun Exception.describe() = message ?: javaClass.simpleName

    companion object {
        /** How long [measure] waits for the engine lock (e.g. the previous reading's stop frames) before saying BUSY. */
        val READING_LOCK_WAIT: Duration = 5.seconds

        /** How long a full handshake with a ring lets a fresh live-reading link skip the next one. */
        val LIGHT_HANDSHAKE_WINDOW: java.time.Duration = java.time.Duration.ofHours(6)
        private val PERIOD = 15.minutes
        private val INITIAL_BACKOFF = 30.seconds
    }
}
