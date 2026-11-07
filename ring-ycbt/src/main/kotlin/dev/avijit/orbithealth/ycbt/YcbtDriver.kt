package dev.avijit.orbithealth.ycbt

import dev.avijit.orbithealth.ring.DeviceInfo
import dev.avijit.orbithealth.ring.HistoryBatch
import dev.avijit.orbithealth.ring.MeasureEvent
import dev.avijit.orbithealth.ring.MeasureFailure
import dev.avijit.orbithealth.ring.MeasureKind
import dev.avijit.orbithealth.ring.Metric
import dev.avijit.orbithealth.ring.MonitorKind
import dev.avijit.orbithealth.ring.MonitorSetting
import dev.avijit.orbithealth.ring.MonitoringNotApplied
import dev.avijit.orbithealth.ring.MonitoringConfig
import dev.avijit.orbithealth.ring.RingCapabilities
import dev.avijit.orbithealth.ring.RingDriver
import dev.avijit.orbithealth.ring.RingTransport
import dev.avijit.orbithealth.ring.SyncFailed
import dev.avijit.orbithealth.ring.UserProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Clock
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * [RingDriver] for Yucheng-protocol rings.
 *
 * Two-phase history: [syncHistory] never deletes. It remembers the types that verifiably completed
 * (CRC-checked data or "no data") together with the returned batch; [clearSyncedHistory] deletes
 * only those, once, and only when handed that exact batch instance. SLEEP is never deleted while
 * [CLEAR_SLEEP_VERIFIED] is false. A type whose payload does not decode cleanly is left out of the
 * batch and never cleared (see [lastDecodeFailures]); the other types still sync. Types that never
 * answer are unsupported and never deleted. A started transfer that stalls, disconnects or fails
 * verification throws [SyncFailed] and forgets any previously remembered types.
 */
class YcbtDriver(
    private val transport: RingTransport,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val clock: Clock = Clock.systemUTC(),
    private val commandTimeout: Duration = 5.seconds,
    /** How long past [MeasureSpec.typicalSeconds] a measurement with no value yet waits before TIMEOUT. */
    private val measureGrace: Duration = 30.seconds,
) : RingDriver {
    /** One in-flight command sequence at a time. */
    private val mutex = Mutex()

    private val frames: Flow<Frame> = transport.incoming.map(FrameDecoder::decode).filterNotNull()

    override suspend fun handshake(profile: UserProfile): Pair<DeviceInfo, RingCapabilities> = session {
        val z = zone()
        val now = clock.instant()
        command(Commands.setTime(ZonedDateTime.ofInstant(now, z)))
        command(Commands.setTimeZone(now, z))
        command(Commands.setUserInfo(profile))
        val info = decodeDeviceInfo(command(Commands.deviceInfo()).payload)
        val caps = decodeCapabilities(command(Commands.supportFunction()).payload)
        info to caps
    }

    /** The TK5 answers 0x0207 (GetDeviceUserConfig) with 0xFC, so YCBT can't read its schedule back. */
    override suspend fun readMonitoring(): MonitoringConfig? = null

    /**
     * Sends HEART_RATE (0x010C) then SPO2 (0x0126) when present in [config] and reads each 1-byte ack: 00 = accepted,
     * FC = not supported by this ring, anything else = not accepted. Returns only the accepted kinds. Other kinds
     * (blood pressure answers FC on the TK5; HRV and temperature are unverified) are never sent and never returned.
     *
     * The ring acks any interval (even 255), so the driver validates: an interval not in [YcbtDefaults.MONITORING]
     * throws [IllegalArgumentException] before anything is sent. Throws [SyncFailed] when the ring does not answer.
     */
    override suspend fun applyMonitoring(config: MonitoringConfig): MonitoringConfig {
        val wanted = MONITOR_ORDER.mapNotNull { kind -> config[kind]?.let { kind to it } }
        for ((kind, setting) in wanted) {
            val allowed = YcbtDefaults.MONITORING.getValue(kind).intervalsMin
            require(setting.intervalMin in allowed) { "$kind interval ${setting.intervalMin} min is not one of $allowed" }
        }
        return session {
            val accepted = LinkedHashMap<MonitorKind, MonitorSetting>()
            val refused = LinkedHashSet<MonitorKind>()
            for ((kind, setting) in wanted) {
                val frame = when (kind) {
                    MonitorKind.HEART_RATE -> Commands.setHeartMonitor(setting.enabled, setting.intervalMin)
                    else -> Commands.setSpo2Monitor(setting.enabled, setting.intervalMin)
                }
                val ack = command(frame).payload
                val status = if (ack.isEmpty()) -1 else ack[0].toInt() and 0xFF
                when (status) {
                    ACK_OK -> accepted[kind] = setting
                    ACK_UNSUPPORTED -> Unit // FC: the ring can't do this; dropped, not retried
                    else -> refused += kind // any other answer may be transient: the caller retries
                }
            }
            if (refused.isNotEmpty()) throw MonitoringNotApplied(MonitoringConfig(accepted), refused)
            MonitoringConfig(accepted)
        }
    }

    /** The batch returned by the most recent successful [syncHistory] and its deletable types; guarded by [mutex]. */
    private class Synced(val batch: HistoryBatch, val types: List<HistoryType>)

    private var lastSynced: Synced? = null

    /**
     * History types whose CRC-verified payload failed strict decoding in the most recent [syncHistory]
     * (empty after a clean sync; reset at the start of each sync). Those types were left out of the
     * returned batch and are never cleared; exposed for logging/UI.
     */
    @Volatile
    var lastDecodeFailures: Set<HistoryType> = emptySet()
        private set

    override suspend fun syncHistory(): HistoryBatch = session {
        lastSynced = null // a failure below must never leave a stale success clearable
        lastDecodeFailures = emptySet()
        val z = zone()
        val decodeFailures = mutableSetOf<HistoryType>()
        val received = mutableListOf<HistoryType>()
        val batches = mutableListOf<HistoryBatch>()
        for (type in SYNC_ORDER) {
            when (val r = transfer(type)) {
                Transfer.Unsupported -> Unit // never answered: skipped and never deleted
                Transfer.Empty -> received += type
                is Transfer.Data -> {
                    // A decode failure affects only this type: it is left out of the batch and never
                    // cleared, and the other types still sync. Transport failures above still throw.
                    val decoded = try {
                        HistoryDecoders.decode(type, r.bytes, z)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        decodeFailures += type
                        null
                    }
                    if (decoded != null) {
                        batches += decoded
                        received += type
                    }
                }
            }
        }
        val batch = HistoryBatch(
            samples = batches.flatMap { it.samples },
            sleep = batches.flatMap { it.sleep },
            workouts = batches.flatMap { it.workouts },
        )
        // If this call is cancelled after this line, the caller never holds `batch`, so it can't be cleared.
        lastDecodeFailures = decodeFailures.toSet()
        lastSynced = Synced(batch, received.filter(::clearable))
        batch
    }

    /**
     * Deletes the history types received by the most recent successful [syncHistory], but only if
     * [batch] is the very instance it returned (reference identity), then forgets them, so a second
     * call is a no-op. Any other batch is a no-op. Callers MUST persist [batch] before calling this.
     * Delete failures are ignored (no retry): the data is already safe locally.
     */
    override suspend fun clearSyncedHistory(batch: HistoryBatch) = session {
        val synced = lastSynced?.takeIf { it.batch === batch } ?: return@session
        lastSynced = null
        for (type in synced.types) {
            val del = Commands.historyDelete(type)
            ignoringFailure { request(del, commandTimeout) { it.type == del.type } }
        }
    }

    /**
     * On-demand measurement of every kind in [YcbtDefaults.MEASURE] (verified on the TK5):
     * - start: 0x032F [1, type] (HEART_RATE first turns realtime on with 0x0309 [1, 1, 2], as before);
     * - values: HR 0x0601, SpO₂ 0x0602, BP and HRV 0x0603, stress 0x0610; each valid value is a Live event when the
     *   spec is live;
     * - end: 0x040E [type, 01] with a value gives Result (the last valid values). If [MeasureSpec.typicalSeconds]
     *   pass with a value, Result too; with none it waits up to [measureGrace] more for one, then TIMEOUT. Status 02
     *   ([MEASURE_NOT_WORN], sent right after start when the ring is off a finger) is NOT_WORN; any other status is
     *   RING_ERROR;
     * - stop: 0x032F [0, type] (and 0x0309 off for HR) is always sent, also on cancel, in a NonCancellable block.
     */
    override fun measure(kind: MeasureKind): Flow<MeasureEvent> = runMeasure(kind, continuous = false)

    /**
     * Streams until cancelled, starting and stopping the ring once (no Result, no timeout). This is what the default
     * [RingDriver.liveHeartRate] uses, so live HR keeps today's wire frames: 0x0309 [1,1,2], 0x032F [1,0] … then
     * 0x032F [0,0], 0x0309 [0,1,2] on cancel.
     */
    override fun measureContinuously(kind: MeasureKind): Flow<MeasureEvent> = runMeasure(kind, continuous = true)

    private fun runMeasure(kind: MeasureKind, continuous: Boolean): Flow<MeasureEvent> = channelFlow {
        val spec = YcbtDefaults.MEASURE[kind]
        if (spec == null) {
            send(MeasureEvent.Failed(MeasureFailure.UNSUPPORTED))
            return@channelFlow
        }
        // The driver lock is held from start to stop, so no queued caller runs while the ring is still measuring.
        val outcome: MeasureEvent = mutex.withLock { try {
            openSession {
                if (kind == MeasureKind.HEART_RATE) command(Commands.realtime(true, REALTIME_HEART_KIND))
                val ack = command(Commands.measure(true, kind)).payload
                val ackStatus = if (ack.isEmpty()) ACK_OK else ack[0].toInt() and 0xFF
                if (ackStatus != ACK_OK) {
                    return@openSession MeasureEvent.Failed(
                        if (ackStatus == ACK_UNSUPPORTED) MeasureFailure.UNSUPPORTED else MeasureFailure.RING_ERROR,
                    )
                }
                send(MeasureEvent.Started)
                var last: Map<Metric, Double>? = null
                var status: Int? = null // raw 0x040E status for this kind, once it arrives
                suspend fun step() {
                    val f = next()
                    if (f.type == MEASURE_RESULT) {
                        decodeMeasureStatus(f.payload)?.let { (k, s) -> if (k == kind) status = s }
                        return
                    }
                    val v = decodeMeasureValue(kind, f) ?: return
                    last = v
                    if (spec.live || continuous) send(MeasureEvent.Live(v))
                }
                if (continuous) {
                    while (true) step() // ends by cancellation or a disconnect (SyncFailed)
                }
                withTimeoutOrNull(spec.typicalSeconds.seconds) { while (status == null) step() }
                if (status == null && last == null) withTimeoutOrNull(measureGrace) { while (status == null && last == null) step() }
                // "Done" can come just before its value frame: give the value a moment.
                if (status == MEASURE_DONE && last == null) withTimeoutOrNull(TRAILING_VALUE) { while (last == null) step() }
                val values = last
                when {
                    status == MEASURE_NOT_WORN -> MeasureEvent.Failed(MeasureFailure.NOT_WORN)
                    status != null && status != MEASURE_DONE -> MeasureEvent.Failed(MeasureFailure.RING_ERROR)
                    values != null -> MeasureEvent.Result(values)
                    status == MEASURE_DONE -> MeasureEvent.Failed(MeasureFailure.RING_ERROR)
                    else -> MeasureEvent.Failed(MeasureFailure.TIMEOUT)
                }
            }
        } catch (e: SyncFailed) {
            MeasureEvent.Failed(if (transport.connected.value) MeasureFailure.RING_ERROR else MeasureFailure.DISCONNECTED)
        } finally {
            // Fresh listeners (the measuring session's die with a cancel), still under the same lock.
            withContext(NonCancellable) { ignoringFailure { openSession { stopCommands(kind) } } }
        } }
        send(outcome)
    }

    private suspend fun Session.stopCommands(kind: MeasureKind) {
        ignoringFailure { command(Commands.measure(false, kind)) }
        if (kind == MeasureKind.HEART_RATE) ignoringFailure { command(Commands.realtime(false, REALTIME_HEART_KIND)) }
    }

    /** Runs [block], swallowing any failure except cancellation. */
    private inline fun ignoringFailure(block: () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // best effort
        }
    }

    /** Holds the command lock and a single subscription to incoming frames for the whole block. */
    private suspend fun <T> session(block: suspend Session.() -> T): T = mutex.withLock { openSession(block) }

    /** [session] without taking the lock: the caller must hold [mutex]. */
    private suspend fun <T> openSession(block: suspend Session.() -> T): T =
        coroutineScope {
            val inbox = Channel<Frame?>(Channel.UNLIMITED) // null = disconnected
            val listeners = listOf(
                launch(start = CoroutineStart.UNDISPATCHED) { frames.collect { inbox.send(it) } },
                launch(start = CoroutineStart.UNDISPATCHED) {
                    transport.connected.first { !it }
                    inbox.send(null)
                },
            )
            try {
                Session(inbox).block()
            } finally {
                listeners.forEach { it.cancelAndJoin() }
                inbox.close()
            }
        }

    private sealed interface Transfer {
        object Unsupported : Transfer
        object Empty : Transfer
        class Data(val bytes: ByteArray) : Transfer
    }

    private inner class Session(private val inbox: Channel<Frame?>) {
        /** Next received frame; throws [SyncFailed] on disconnect. */
        suspend fun next(): Frame = inbox.receive() ?: throw SyncFailed("ring disconnected")

        /** First frame matching [match] within [timeout], or null. */
        suspend fun await(timeout: Duration, match: (Frame) -> Boolean): Frame? = withTimeoutOrNull(timeout) {
            var f = next()
            while (!match(f)) f = next()
            f
        }

        suspend fun write(f: Frame) {
            if (!transport.connected.value) throw SyncFailed("ring disconnected")
            try {
                transport.write(f.encode())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw SyncFailed("write 0x%04x failed: %s".format(f.type, e.message))
            }
        }

        suspend fun request(f: Frame, timeout: Duration, match: (Frame) -> Boolean): Frame? {
            write(f)
            return await(timeout, match)
        }

        /** Sends [f] and returns the reply of the same type; one retry, then [SyncFailed]. */
        suspend fun command(f: Frame): Frame {
            repeat(ATTEMPTS) {
                request(f, commandTimeout) { it.type == f.type }?.let { return it }
            }
            throw SyncFailed("no reply to 0x%04x".format(f.type))
        }

        suspend fun transfer(type: HistoryType): Transfer {
            val asm = HistoryAssembler(type)
            val headerType = HISTORY_GROUP or type.requestSub
            repeat(ATTEMPTS) {
                val header = request(Commands.historyRequest(type), commandTimeout) { it.type == headerType }
                    ?: return@repeat
                if (asm.accept(header) == Step.Empty) return Transfer.Empty
                // Transfer started: from here any stall, disconnect or bad CRC aborts the whole sync.
                while (true) {
                    val f = await(commandTimeout) { it.type shr 8 == HISTORY_GROUP shr 8 }
                        ?: throw SyncFailed("$type transfer stalled")
                    when (val step = asm.accept(f)) {
                        Step.Continue -> Unit
                        Step.Empty -> throw SyncFailed("$type transfer restarted empty")
                        is Step.Complete -> {
                            write(step.ack)
                            return Transfer.Data(step.data)
                        }
                        is Step.Corrupt -> {
                            ignoringFailure { write(step.nack) }
                            throw SyncFailed("$type transfer failed verification")
                        }
                    }
                }
            }
            return Transfer.Unsupported // never answered: skipped and never deleted
        }
    }

    companion object {
        /**
         * Whether SLEEP history may be deleted from the ring after a sync. The sleep layout has only been
         * checked against synthetic records (no real overnight capture yet), so it stays false and the ring
         * keeps its sleep history: a misdecoded night is then recoverable by a later build.
         *
         * To flip it: capture a real night in coexistence mode (`tools/capture_fixtures.py history_sleep`),
         * commit the fixture, add a HistoryDecodersTest that decodes it and checks start/end/stages against
         * what the vendor app showed for that night, then set this to true and update
         * `YcbtDriverTest.sleepIsNeverClearedWhileUnverified` to expect the 0x0541 delete.
         */
        const val CLEAR_SLEEP_VERIFIED = false

        private fun clearable(type: HistoryType) = type != HistoryType.SLEEP || CLEAR_SLEEP_VERIFIED

        private val SYNC_ORDER = listOf(
            HistoryType.SLEEP, HistoryType.HEART, HistoryType.ALL, HistoryType.SPO2,
            HistoryType.BLOOD, HistoryType.BODY, HistoryType.SPORT,
        )
        private const val ATTEMPTS = 2 // first try + 1 retry
        private const val HISTORY_GROUP = 0x0500
        private val MONITOR_ORDER = listOf(MonitorKind.HEART_RATE, MonitorKind.SPO2)
        private const val ACK_OK = 0x00
        private const val ACK_UNSUPPORTED = 0xFC
        private val TRAILING_VALUE = 3.seconds
        private const val MEASURE_RESULT = 0x040E
        private const val REALTIME_HEART_KIND = 1
    }
}
