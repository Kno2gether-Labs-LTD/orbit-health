package dev.avijit.orbithealth.ui.me

import dev.avijit.orbithealth.ring.RingCapabilities
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.avijit.orbithealth.prefs.SettingsSnapshot
import dev.avijit.orbithealth.ring.MonitorKind
import dev.avijit.orbithealth.ring.MonitorSetting
import dev.avijit.orbithealth.ring.MonitorSpec
import dev.avijit.orbithealth.ring.MonitoringConfig
import dev.avijit.orbithealth.sync.MonitoringApplyResult
import dev.avijit.orbithealth.sync.MonitoringPrefs
import dev.avijit.orbithealth.sync.monitoringPrefs
import dev.avijit.orbithealth.ui.today.minuteTicks
import java.time.Clock
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What the Automatic checks screen reads and does. Backed by Settings and the SyncEngine in the app; faked in tests. */
class AutomaticChecksDeps(
    /** Cached capabilities, the wanted schedule, and what the ring last accepted (with when). */
    val settings: Flow<SettingsSnapshot>,
    /** Saves the schedule the user wants (Settings.setRingMonitoringWanted). */
    val saveWanted: suspend (MonitoringConfig) -> Unit,
    /** The ring's own schedule over a held link (SyncEngine.readMonitoring); null when it can't report it (the TK5). */
    val readFromRing: suspend () -> MonitoringConfig?,
    /** Sends the wanted schedule now and says what the ring did (SyncEngine.applyMonitoringNow). */
    val apply: suspend () -> MonitoringApplyResult,
    val clock: Clock,
    /** Drives "Last set from Orbit Health · 2 min ago". */
    val now: Flow<Instant> = minuteTicks(clock),
)

/** Where the values on screen come from. */
enum class ScheduleSource { RING, LAST_SET, NOT_SENT }

/** One automatic check: what the user wants, what the ring allows, and what the ring has if that differs. */
data class CheckRowUi(
    val kind: MonitorKind,
    val title: String,
    val enabled: Boolean,
    /** Only when the ring lets this check be turned off. */
    val showSwitch: Boolean,
    val intervalMin: Int,
    /** Exactly the ring's allowed intervals, in order. */
    val intervals: List<Int>,
    /** Only when the ring supports a time window. */
    val showOvernight: Boolean,
    val overnightOnly: Boolean,
    /** "Every 10 minutes, day and night". */
    val line: String,
    /** What the ring last accepted when it differs from the choice ("On your ring now: every 10 minutes"), else null. */
    val ringLine: String?,
)

/** The quiet line that follows a change. */
sealed interface ApplyStatus {
    data object Idle : ApplyStatus
    data object Sending : ApplyStatus
    /** [ok]: the ring took the change; otherwise it is kept and retried. */
    data class Done(val message: String, val ok: Boolean) : ApplyStatus
}

data class AutomaticChecksUiState(
    val loaded: Boolean = false,
    /** Empty when no ring is paired, its capabilities are unknown, or it has no automatic checks ([empty] says why). */
    val rows: List<CheckRowUi> = emptyList(),
    val empty: String? = null,
    val source: ScheduleSource = ScheduleSource.NOT_SENT,
    val caption: String = "",
    val status: ApplyStatus = ApplyStatus.Idle,
    /** The readings this ring takes only on request ("blood pressure, HRV and stress"), said so the list isn't a puzzle. */
    val measureOnly: String? = null,
)

/** The screen's words and rules, pure so they can be tested on the JVM. */
object AutomaticChecksCopy {
    const val TITLE_EYEBROW = "My ring"
    const val LEAD = "Your ring takes these readings by itself, day and night. You can still measure whenever you like."
    const val BATTERY_NOTE = "More frequent checks use more battery."
    const val NO_RING = "Connect your ring to set automatic checks."
    const val NO_CHECKS = "Your ring doesn't take readings on its own. You can still measure whenever you like."
    const val NOT_SENT = "Not sent to your ring yet"
    const val FROM_RING = "Read from your ring just now"
    const val SENDING = "Sending to your ring…"
    const val NOT_IN_REACH = "Your ring isn't in reach right now. Your choice is saved and goes across the next time it connects."
    const val BUSY = "Another app is using your ring right now. Your choice is saved and goes across the next time Orbit Health connects."
    const val INVALID = "Orbit couldn't build that setting for your ring, so nothing was sent. Try a different option."
    const val TRY_AGAIN = "Your ring didn't take that just now. Orbit Health will try again at the next sync."

    /** Rows follow this order whatever order the ring declares them in. */
    val ORDER = listOf(MonitorKind.HEART_RATE, MonitorKind.SPO2, MonitorKind.BLOOD_PRESSURE, MonitorKind.HRV, MonitorKind.TEMPERATURE, MonitorKind.STRESS)

    fun title(kind: MonitorKind) = when (kind) {
        MonitorKind.HEART_RATE -> "Heart rate"
        MonitorKind.SPO2 -> "SpO₂"
        MonitorKind.BLOOD_PRESSURE -> "Blood pressure"
        MonitorKind.HRV -> "HRV"
        MonitorKind.TEMPERATURE -> "Temperature"
        MonitorKind.STRESS -> "Stress"
    }

    /** The name inside a sentence ("checking heart rate"). */
    fun noun(kind: MonitorKind) = when (kind) {
        MonitorKind.HEART_RATE -> "heart rate"
        MonitorKind.SPO2 -> "SpO₂"
        MonitorKind.BLOOD_PRESSURE -> "blood pressure"
        MonitorKind.HRV -> "HRV"
        MonitorKind.TEMPERATURE -> "temperature"
        MonitorKind.STRESS -> "stress"
    }

    /** "every 15 minutes", "every hour", "every 2 hours". */
    fun every(min: Int): String = when {
        min == 60 -> "every hour"
        min > 60 && min % 60 == 0 -> "every ${min / 60} hours"
        min == 1 -> "every minute"
        else -> "every $min minutes"
    }

    /** The short form for chips and captions: "10 min", "1 hr", "2 hr". Full sentences use [every]. */
    fun short(min: Int): String = chip(min).let { (n, u) -> "$n $u" }

    /** The chip's label: the number and its short unit ("15" "min", "1" "hr"). */
    fun chip(min: Int): Pair<String, String> = if (min >= 60 && min % 60 == 0) "${min / 60}" to "hr" else "$min" to "min"

    /** The ring's checks that can actually be set: a kind declared with no intervals counts as not supported. */
    fun settable(specs: Map<MonitorKind, MonitorSpec>?): Map<MonitorKind, MonitorSpec> =
        specs.orEmpty().filterValues { it.intervalsMin.isNotEmpty() }

    /** The human line under a row's title. */
    fun line(s: MonitorSetting): String = when {
        !s.enabled -> "Off. Only when you measure."
        s.overnightOnly -> "${every(s.intervalMin).cap()}, overnight"
        else -> "${every(s.intervalMin).cap()}, day and night"
    }

    /** What the ring has, said plainly ("every 10 minutes", "off", "every 30 minutes, overnight"). */
    fun onRing(s: MonitorSetting): String = when {
        !s.enabled -> "off"
        s.overnightOnly -> "${every(s.intervalMin)}, overnight"
        else -> every(s.intervalMin)
    }

    fun caption(source: ScheduleSource, acceptedAt: Instant?, now: Instant): String = when (source) {
        ScheduleSource.RING -> FROM_RING
        ScheduleSource.LAST_SET -> "Last set from Orbit Health" + (acceptedAt?.let { " · ${MeMath.ago(it, now)}" } ?: "")
        ScheduleSource.NOT_SENT -> NOT_SENT
    }

    /** What to say once the ring answered a change to [kind]; it always reports what the ring ACCEPTED. */
    fun result(result: MonitoringApplyResult, kind: MonitorKind): ApplyStatus.Done = when (result) {
        is MonitoringApplyResult.Applied -> accepted(result.accepted[kind], kind)
            ?: ApplyStatus.Done("Your ring turned down that ${noun(kind)} setting, so it stays as it was on the ring.", ok = false)
        is MonitoringApplyResult.Refused ->
            if (kind in result.refused) ApplyStatus.Done(TRY_AGAIN, ok = false)
            else accepted(result.accepted[kind], kind) ?: ApplyStatus.Done(TRY_AGAIN, ok = false)
        MonitoringApplyResult.NotConnected -> ApplyStatus.Done(NOT_IN_REACH, ok = false)
        MonitoringApplyResult.Busy -> ApplyStatus.Done(BUSY, ok = false)
        is MonitoringApplyResult.Invalid -> ApplyStatus.Done(INVALID, ok = false)
    }

    private fun accepted(s: MonitorSetting?, kind: MonitorKind): ApplyStatus.Done? = s?.let {
        ApplyStatus.Done(
            when {
                !it.enabled -> "Your ring has stopped checking ${noun(kind)} on its own"
                it.overnightOnly -> "Your ring is now checking ${noun(kind)} ${every(it.intervalMin)}, overnight"
                else -> "Your ring is now checking ${noun(kind)} ${every(it.intervalMin)}"
            },
            ok = true,
        )
    }

    /** A check the user has never set: the app's long-standing defaults for HR and SpO₂, else the gentlest option. */
    fun default(kind: MonitorKind, spec: MonitorSpec): MonitorSetting {
        val legacy = MonitoringPrefs(10, true).schedule[kind]
        val base = legacy ?: MonitorSetting(enabled = !spec.canTurnOff, intervalMin = spec.intervalsMin.lastOrNull() ?: 60)
        return base.copy(intervalMin = MonitoringPrefs.snapInterval(base.intervalMin, spec.intervalsMin))
    }

    private fun String.cap() = replaceFirstChar { it.uppercase() }

    /**
     * "Your ring can't check blood pressure, HRV or stress on a schedule. Measure them any time with Measure now on
     * Body › Heart." for every on-demand reading the ring has no automatic check for; null when there are none.
     */
    fun measureOnly(caps: RingCapabilities): String? {
        val scheduled = settable(caps.monitoring).keys.map { it.name }.toSet()
        val names = ORDER.filter { kind -> kind.name !in scheduled && caps.measure.keys.any { it.name == kind.name } }.map(::noun)
        if (names.isEmpty()) return null
        val list = if (names.size == 1) names[0] else names.dropLast(1).joinToString(", ") + " or " + names.last()
        val them = if (names.size == 1) "it" else "them"
        return "Your ring can't check $list on a schedule. Measure $them any time with Measure now on Body › Heart."
    }

    /**
     * The whole screen from settings, what the ring reported (null on the TK5), the user's latest choice on this screen
     * ([chosen], ahead of settings for a moment after a tap) and the apply status.
     */
    fun state(
        s: SettingsSnapshot,
        reported: MonitoringConfig?,
        status: ApplyStatus,
        now: Instant,
        chosen: MonitoringConfig? = null,
    ): AutomaticChecksUiState {
        val caps = s.ringCapabilities
        if (s.pairedRing == null || caps == null) return AutomaticChecksUiState(loaded = true, empty = NO_RING, caption = "", status = status)
        val specs = settable(caps.monitoring)
        if (specs.isEmpty()) return AutomaticChecksUiState(loaded = true, empty = NO_CHECKS, caption = "", status = status)
        val wanted = s.monitoringPrefs().migrated(caps.monitoring).schedule
        val accepted = s.ringMonitoringAccepted
        val source = when {
            reported != null -> ScheduleSource.RING
            accepted != null && accepted.settings.isNotEmpty() -> ScheduleSource.LAST_SET
            else -> ScheduleSource.NOT_SENT
        }
        val onRing = reported ?: accepted
        val rows = ORDER.mapNotNull { kind ->
            val spec = specs[kind] ?: return@mapNotNull null
            val ring = onRing?.get(kind)
            // A value the ring reports outside its own allowed list is shown at the nearest allowed one (the ring line
            // still says what it really has), so a chip is always selected and the switch always works.
            val reportedShown = if (reported != null) ring?.let { MonitoringPrefs.snapSetting(it, spec) } else null
            val want = chosen?.get(kind) ?: reportedShown ?: wanted[kind] ?: ring ?: default(kind, spec)
            CheckRowUi(
                kind = kind,
                title = title(kind),
                enabled = want.enabled,
                showSwitch = spec.canTurnOff,
                intervalMin = want.intervalMin,
                intervals = spec.intervalsMin,
                showOvernight = spec.supportsWindow,
                overnightOnly = want.overnightOnly && spec.supportsWindow,
                line = line(want),
                ringLine = ring?.takeIf { status !is ApplyStatus.Sending && !it.sameAs(want) }?.let { "On your ring now: ${onRing(it)}" },
            )
        }
        return AutomaticChecksUiState(
            loaded = true, rows = rows, source = source,
            caption = caption(source, s.ringMonitoringAcceptedAt, now), status = status,
            measureOnly = measureOnly(caps),
        )
    }

    /** Equal as far as the ring is concerned (the interval of a check that is off doesn't matter). */
    private fun MonitorSetting.sameAs(o: MonitorSetting) =
        enabled == o.enabled && (!enabled || (intervalMin == o.intervalMin && overnightOnly == o.overnightOnly))
}

/**
 * Me › My ring › Automatic checks: one row per check the paired ring declares, offering only its allowed intervals.
 * On open it asks the ring for its schedule (rings that can't say show what was last set from Orbit Health), snaps any
 * stored value the ring can't take to the nearest allowed one (and saves it), and sends each change through the sync
 * engine, then says what the ring accepted. A change the ring didn't take stays saved and goes again at the next sync.
 */
class AutomaticChecksViewModel(private val deps: AutomaticChecksDeps, scope: CoroutineScope? = null) : ViewModel() {
    private val scope: CoroutineScope = scope ?: viewModelScope
    private val reported = MutableStateFlow<MonitoringConfig?>(null)
    private val status = MutableStateFlow<ApplyStatus>(ApplyStatus.Idle)
    /** Every choice made on this screen, so the rows answer a tap at once and quick taps build on each other. */
    private val chosen = MutableStateFlow<MonitoringConfig?>(null)
    /**
     * Changes waiting to be saved and sent, each with the whole schedule chosen so far. One consumer saves then sends
     * them in order, and the channel is conflated, so the last tap always wins and a quick run sends only once.
     */
    private val pending = Channel<Pair<MonitorKind, MonitoringConfig>>(Channel.CONFLATED)
    /** Orders the opening save (snapping, adopting the ring's values) before any save from a tap. */
    private val saving = Mutex()

    val state: StateFlow<AutomaticChecksUiState> =
        combine(deps.settings, reported, status, deps.now, chosen) { s, r, st, now, c -> AutomaticChecksCopy.state(s, r, st, now, c) }
            .stateIn(this.scope, SharingStarted.Eagerly, AutomaticChecksUiState())

    init {
        this.scope.launch {
            val s = deps.settings.first()
            val caps = s.ringCapabilities?.monitoring
            if (caps != null && s.pairedRing != null) {
                val specs = AutomaticChecksCopy.settable(caps)
                // Stored values the ring can't take snap to the nearest allowed one, and that is what gets saved.
                val stored = s.monitoringPrefs()
                val snapped = stored.migrated(caps)
                if (snapped != stored) saving.withLock { deps.saveWanted(snapped.schedule) }
                val fromRing = runCatching { deps.readFromRing() }.getOrNull()
                if (fromRing != null && fromRing.settings.isNotEmpty()) {
                    reported.value = fromRing
                    // The ring is the truth: the next change starts from what it has. Only values it allows are
                    // adopted; one outside its own list is never saved as wanted.
                    val allowed = fromRing.settings.filter { (k, v) -> specs[k]?.let { MonitoringPrefs.snapSetting(v, it) == v } == true }
                    if (allowed.isNotEmpty()) saving.withLock { deps.saveWanted(MonitoringConfig(snapped.schedule.settings + allowed)) }
                }
            }
        }
        this.scope.launch {
            for ((kind, config) in pending) {
                status.value = ApplyStatus.Sending
                saving.withLock {
                    // Kinds this ring doesn't declare keep whatever was saved for them.
                    val saved = deps.settings.first().monitoringPrefs().schedule.settings
                    deps.saveWanted(MonitoringConfig(saved + config.settings))
                }
                val result = runCatching { deps.apply() }.getOrElse { MonitoringApplyResult.NotConnected }
                if (result is MonitoringApplyResult.Applied || result is MonitoringApplyResult.Refused) reported.value = null
                status.value = AutomaticChecksCopy.result(result, kind)
            }
        }
    }

    fun setEnabled(kind: MonitorKind, on: Boolean) = change(kind) { it.copy(enabled = on) }

    fun setInterval(kind: MonitorKind, min: Int) = change(kind) { it.copy(intervalMin = min) }

    fun setOvernightOnly(kind: MonitorKind, on: Boolean) = change(kind) { it.copy(overnightOnly = on) }

    private fun change(kind: MonitorKind, edit: (MonitorSetting) -> MonitorSetting) {
        val rows = state.value.rows
        val row = rows.firstOrNull { it.kind == kind } ?: return
        // The latest choices first: the state may not have caught up with a tap a moment ago.
        val shown = rows.associate { it.kind to MonitorSetting(it.enabled, it.intervalMin, it.overnightOnly) }
        val base = shown + chosen.value?.settings.orEmpty().filterKeys { it in shown }
        val current = base.getValue(kind)
        val next = edit(current)
        if (next == current || next.intervalMin !in row.intervals) return
        val config = MonitoringConfig(base + (kind to next))
        chosen.value = config
        status.value = ApplyStatus.Sending
        pending.trySend(kind to config) // conflated: always accepted, replacing a change not yet taken

    }
}
