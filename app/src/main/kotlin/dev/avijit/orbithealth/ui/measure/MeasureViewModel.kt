package dev.avijit.orbithealth.ui.measure

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.avijit.orbithealth.ring.MeasureEvent
import dev.avijit.orbithealth.ring.MeasureFailure
import dev.avijit.orbithealth.ring.MeasureKind
import dev.avijit.orbithealth.ring.MeasureSpec
import dev.avijit.orbithealth.ring.Metric
import dev.avijit.orbithealth.ring.RingCapabilities
import dev.avijit.orbithealth.ring.Sample
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the Measure sheet reads and drives, so its logic runs against fakes in tests. */
class MeasureDeps(
    /** The paired ring's cached capabilities (null until its first full handshake). */
    val capabilities: Flow<RingCapabilities?>,
    /** One reading over the sync engine's link (see SyncEngine.measure); cancelling stops it on the ring. */
    val measure: (MeasureKind) -> Flow<MeasureEvent>,
    /** Stored samples, for "your usual range". */
    val samples: (Metric, Instant, Instant) -> Flow<List<Sample>>,
    val clock: Clock,
)

/** One tile of the picker: a reading the paired ring can take on demand. */
data class MeasureTile(val kind: MeasureKind, val typicalSeconds: Int, val live: Boolean, val estimate: Boolean)

/** Where the sheet is. */
sealed interface MeasurePhase {
    /** The tile picker. */
    data object Picker : MeasurePhase

    /**
     * A reading of [kind] is running. [started] false: still reaching the ring. [values]: the latest live values (null
     * before the first). [elapsedSeconds] counts from the ring's start.
     */
    data class Measuring(
        val kind: MeasureKind,
        val typicalSeconds: Int,
        val started: Boolean = false,
        val values: Map<Metric, Double>? = null,
        val elapsedSeconds: Int = 0,
    ) : MeasurePhase

    /** A reading came in (already stored as MEASURED samples). [context]: one sentence against the user's usual range. */
    data class Done(val kind: MeasureKind, val values: Map<Metric, Double>, val context: String, val at: Instant) : MeasurePhase

    data class Failed(val kind: MeasureKind, val reason: MeasureFailure) : MeasurePhase
}

data class MeasureUiState(
    val visible: Boolean = false,
    val tiles: List<MeasureTile> = emptyList(),
    val phase: MeasurePhase = MeasurePhase.Picker,
)

/**
 * The Measure sheet: the picker lists only what the paired ring declares ([RingCapabilities.measure]), in the order of
 * [MeasureCopy.ORDER]. Starting a tile collects [MeasureDeps.measure] in a job owned by this model; leaving or dismissing
 * the sheet cancels it, which stops the measurement on the ring. Switching tiles mid-reading stops the old one first.
 */
class MeasureViewModel(private val deps: MeasureDeps, scope: CoroutineScope? = null) : ViewModel() {
    private val scope: CoroutineScope = scope ?: viewModelScope
    private val _state = MutableStateFlow(MeasureUiState())
    val state: StateFlow<MeasureUiState> = _state.asStateFlow()
    private var job: Job? = null

    init {
        this.scope.launch { deps.capabilities.collect { caps -> _state.update { it.copy(tiles = MeasureCopy.tiles(caps)) } } }
    }

    /** Opens the sheet: on the picker ([kind] null), or on [kind], starting it at once when [autoStart]. */
    fun open(kind: MeasureKind? = null, autoStart: Boolean = false) {
        stop()
        _state.update { it.copy(visible = true, phase = MeasurePhase.Picker) }
        if (kind != null && autoStart) start(kind)
    }

    /** Starts a reading of [kind] (stopping any other first). */
    fun start(kind: MeasureKind) {
        // The old reading is cancelled now, and the new one starts only once it has finished (its stop frames sent and
        // the engine lock released); otherwise the new reading would find the ring busy.
        val prev = job
        prev?.cancel()
        job = scope.launch {
            val tiles = MeasureCopy.tiles(deps.capabilities.first())
            val tile = tiles.firstOrNull { it.kind == kind }
            if (tile == null) {
                prev?.join()
                _state.update { it.copy(phase = MeasurePhase.Failed(kind, MeasureFailure.UNSUPPORTED)) }
                return@launch
            }
            // The new reading shows at once (connecting), while the old one finishes stopping.
            _state.update { it.copy(phase = MeasurePhase.Measuring(kind, tile.typicalSeconds)) }
            prev?.join()
            var startedAt: Instant? = null
            var ticker: Job? = null
            try {
                deps.measure(kind).collect { e ->
                    when (e) {
                        MeasureEvent.Started -> {
                            startedAt = deps.clock.instant()
                            measuring { it.copy(started = true) }
                            if (ticker == null) ticker = launch {
                                var s = 0
                                while (true) {
                                    delay(1_000)
                                    s++
                                    measuring { it.copy(elapsedSeconds = s) }
                                }
                            }
                        }
                        is MeasureEvent.Live -> measuring { it.copy(started = true, values = e.values) }
                        is MeasureEvent.Result -> {
                            ticker?.cancel()
                            val at = deps.clock.instant()
                            val context = contextFor(kind, e.values, startedAt ?: at)
                            _state.update { it.copy(phase = MeasurePhase.Done(kind, e.values, context, at)) }
                        }
                        is MeasureEvent.Failed -> {
                            ticker?.cancel()
                            _state.update { it.copy(phase = MeasurePhase.Failed(kind, e.reason)) }
                        }
                    }
                }
                // A flow that ended without a Result or a failure: say so rather than spin.
                if (_state.value.phase is MeasurePhase.Measuring) {
                    _state.update { it.copy(phase = MeasurePhase.Failed(kind, MeasureFailure.RING_ERROR)) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { it.copy(phase = MeasurePhase.Failed(kind, MeasureFailure.RING_ERROR)) }
            } finally {
                ticker?.cancel()
            }
        }
    }

    /** "Measure again" after a result or a failure: the same reading. */
    fun again() {
        when (val p = _state.value.phase) {
            is MeasurePhase.Done -> start(p.kind)
            is MeasurePhase.Failed -> start(p.kind)
            else -> Unit
        }
    }

    /** Back to the tiles (stops a running reading). */
    fun showPicker() {
        stop()
        _state.update { it.copy(phase = MeasurePhase.Picker) }
    }

    /** The sheet was dismissed or left the screen: the ring stops measuring. */
    fun dismiss() {
        stop()
        _state.update { it.copy(visible = false, phase = MeasurePhase.Picker) }
    }

    override fun onCleared() {
        stop()
    }

    private fun stop() {
        job?.cancel()
        job = null
    }

    private inline fun measuring(crossinline f: (MeasurePhase.Measuring) -> MeasurePhase.Measuring) =
        _state.update { s -> (s.phase as? MeasurePhase.Measuring)?.let { s.copy(phase = f(it)) } ?: s }

    /** The usual range from the [HISTORY_DAYS] before the reading began (never the reading itself). */
    private suspend fun contextFor(kind: MeasureKind, values: Map<Metric, Double>, before: Instant): String {
        val metric = MeasureCopy.primaryMetric(kind)
        val history = try {
            deps.samples(metric, before.minus(Duration.ofDays(HISTORY_DAYS)), before.minusMillis(1)).first()
                .filter { it.time < before }.map { it.value }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
        return MeasureCopy.contextLine(kind, values, history)
    }

    companion object {
        const val HISTORY_DAYS = 30L
    }
}

/** The sheet's words and numbers, kept pure for tests. */
object MeasureCopy {
    /** Tile order (spec §5). */
    val ORDER = listOf(MeasureKind.HEART_RATE, MeasureKind.SPO2, MeasureKind.HRV, MeasureKind.STRESS, MeasureKind.BLOOD_PRESSURE)

    /** Until the ring has reported what it can do (no full handshake yet), heart rate alone is offered. */
    private val FALLBACK = mapOf(MeasureKind.HEART_RATE to MeasureSpec(typicalSeconds = 30, live = true, estimate = false))

    /** Fewer readings than this and there is no "usual range" yet. */
    const val MIN_HISTORY = 10

    fun tiles(caps: RingCapabilities?): List<MeasureTile> {
        val specs = caps?.measure ?: FALLBACK
        return ORDER.mapNotNull { k -> specs[k]?.let { MeasureTile(k, it.typicalSeconds, it.live, it.estimate) } }
    }

    fun name(kind: MeasureKind): String = when (kind) {
        MeasureKind.HEART_RATE -> "Heart rate"
        MeasureKind.SPO2 -> "SpO₂"
        MeasureKind.HRV -> "HRV"
        MeasureKind.STRESS -> "Stress"
        MeasureKind.BLOOD_PRESSURE -> "Blood pressure"
    }

    /** Spoken name (TalkBack reads "SpO₂" badly). */
    fun spokenName(kind: MeasureKind): String = when (kind) {
        MeasureKind.SPO2 -> "Blood oxygen"
        MeasureKind.HRV -> "Heart rate variability"
        else -> name(kind)
    }

    fun duration(seconds: Int): String = "about $seconds s"

    fun primaryMetric(kind: MeasureKind): Metric = when (kind) {
        MeasureKind.HEART_RATE -> Metric.HEART_RATE
        MeasureKind.SPO2 -> Metric.SPO2
        MeasureKind.HRV -> Metric.HRV
        MeasureKind.STRESS -> Metric.STRESS
        MeasureKind.BLOOD_PRESSURE -> Metric.BLOOD_PRESSURE_SYS
    }

    fun unit(kind: MeasureKind): String = when (kind) {
        MeasureKind.HEART_RATE -> "bpm"
        MeasureKind.SPO2 -> "%"
        MeasureKind.HRV -> "ms"
        MeasureKind.STRESS -> ""
        MeasureKind.BLOOD_PRESSURE -> "mmHg"
    }

    /** The numeral: "72", "98", "41", "7.1" (stress keeps its tenth), "117/78"; null when [values] lack it. */
    fun value(kind: MeasureKind, values: Map<Metric, Double>?): String? {
        if (values == null) return null
        fun r(m: Metric) = values[m]?.let { Math.round(it).toString() }
        return when (kind) {
            MeasureKind.BLOOD_PRESSURE -> {
                val sys = r(Metric.BLOOD_PRESSURE_SYS) ?: return null
                val dia = r(Metric.BLOOD_PRESSURE_DIA) ?: return null
                "$sys/$dia"
            }
            MeasureKind.STRESS -> values[Metric.STRESS]?.let { v ->
                if (v == Math.floor(v)) v.toLong().toString() else String.format(java.util.Locale.ROOT, "%.1f", v)
            }
            else -> r(primaryMetric(kind))
        }
    }

    /** "117/78 mmHg", "72 bpm", "98%". */
    fun valueWithUnit(kind: MeasureKind, values: Map<Metric, Double>): String? {
        val v = value(kind, values) ?: return null
        val u = unit(kind)
        return when {
            u.isEmpty() -> v
            u == "%" -> "$v%"
            else -> "$v $u"
        }
    }

    /** Extra line under a BP result: "Heart rate 88 bpm" when the ring sent one. */
    fun secondary(kind: MeasureKind, values: Map<Metric, Double>): String? =
        if (kind == MeasureKind.BLOOD_PRESSURE) values[Metric.HEART_RATE]?.let { "Heart rate ${Math.round(it)} bpm" } else null

    const val KEEP_STILL = "Keep still, ring snug on your finger"
    const val CONNECTING = "Reaching your ring…"
    const val READING = "Reading…"
    const val LONGER = "Taking a little longer…"
    const val WITHIN = "Within your usual range."
    const val ESTIMATE_NOTE = "Estimated by the ring's light sensor"
    const val NEUTRAL = "Saved. Your usual range appears once there are a few more readings."

    fun remaining(typicalSeconds: Int, elapsed: Int): String {
        val left = typicalSeconds - elapsed
        return if (left > 0) "$left s left" else LONGER
    }

    /** One sentence for [values] against the user's [history] of the kind's main metric (spec §5). */
    fun contextLine(kind: MeasureKind, values: Map<Metric, Double>, history: List<Double>): String {
        val v = values[primaryMetric(kind)] ?: return NEUTRAL
        if (history.size < MIN_HISTORY) return NEUTRAL
        val sorted = history.sorted()
        val lo = quantile(sorted, 0.10)
        val hi = quantile(sorted, 0.90)
        val spread = (hi - lo).coerceAtLeast(1.0)
        return when {
            v in lo..hi -> WITHIN
            // More oxygen is never a worry: don't make a high reading sound like a change to watch.
            kind == MeasureKind.SPO2 && v > hi -> "In the healthy range, at the top of your usual."
            v > hi -> if (v > hi + spread) "Higher than usual for you." else "A little higher than usual."
            // Oxygen at 95% or more is healthy whatever "usual" says; don't make it sound like a drop.
            kind == MeasureKind.SPO2 && v >= 95.0 -> "In the healthy range, a touch below your usual."
            else -> if (v < lo - spread) "Lower than usual for you." else "A little lower than usual."
        }
    }

    private fun quantile(sorted: List<Double>, q: Double): Double {
        val pos = q * (sorted.size - 1)
        val i = pos.toInt()
        val f = pos - i
        return if (i + 1 < sorted.size) sorted[i] + (sorted[i + 1] - sorted[i]) * f else sorted[i]
    }

    /** Failure copy: specific and human, never a code. */
    fun failure(reason: MeasureFailure, kind: MeasureKind): String = when (reason) {
        MeasureFailure.NOT_WORN, MeasureFailure.TIMEOUT -> "Couldn't get a reading. Is the ring snug on your finger?"
        MeasureFailure.DISCONNECTED -> "Your ring isn't connected right now. Keep it nearby and try again."
        // A sync holding the link, or the vendor app holding the ring.
        MeasureFailure.BUSY -> "Your ring is busy right now. Try again in a moment."
        MeasureFailure.UNSUPPORTED -> "Your ring can't measure ${name(kind).let { if (kind == MeasureKind.HRV || kind == MeasureKind.SPO2) it else it.lowercase() }} on demand."
        MeasureFailure.RING_ERROR -> "Your ring stopped partway through. Give it a moment, then try again."
    }

    /** The heading over a failure. */
    fun failureTitle(reason: MeasureFailure): String = when (reason) {
        MeasureFailure.DISCONNECTED -> "Ring not reachable"
        MeasureFailure.BUSY -> "Ring busy"
        MeasureFailure.UNSUPPORTED -> "Not on this ring"
        else -> "No reading this time"
    }
}
