package dev.avijit.orbithealth.ui.me

import dev.avijit.orbithealth.ui.components.VendorAppCopy
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.avijit.orbithealth.prefs.Settings
import dev.avijit.orbithealth.prefs.SettingsSnapshot
import dev.avijit.orbithealth.ring.UserProfile
import dev.avijit.orbithealth.sync.SyncMode
import dev.avijit.orbithealth.sync.monitoringPrefs
import dev.avijit.orbithealth.sync.SyncState
import dev.avijit.orbithealth.ui.today.minuteTicks
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What Me writes. Backed by [Settings] in the app; faked in tests. */
interface MeStore {
    suspend fun setStepGoalBase(v: Int)
    suspend fun setSleepGoalMin(v: Int)
    suspend fun setBedtimeTarget(v: LocalTime)
    suspend fun setProfile(p: UserProfile)
    suspend fun setAppLockEnabled(v: Boolean)
    /** The greeting name on Today; null clears it. */
    suspend fun setName(name: String?)
}

class MeDeps(
    val settings: Flow<SettingsSnapshot>,
    val syncState: Flow<SyncState>,
    /** True while the app holds a handshaken link to the ring (SyncEngine.linked). */
    val linked: Flow<Boolean>,
    val mode: Flow<SyncMode>,
    val store: MeStore,
    val clock: Clock,
    /** Drives "2 min ago". */
    val now: Flow<Instant> = minuteTicks(clock),
    /** The paired Orbit Server's name, or null (the Agent & sync row). */
    val serverName: Flow<String?> = flowOf(null),
    /** Bytes this phone sent to its server since [fromMs] (network log); Me asks from the start of the current month. */
    val sentSince: (fromMs: Long) -> Flow<Long> = { flowOf(0L) },
    /** The installed vendor app's name (e.g. "SmartHealth"), or null when unknown; names the coexistence caption. */
    val vendorAppName: Flow<String?> = flowOf(null),
    /** True while the ring is reached through the Mac bridge (developer switch): the ring card says so. */
    val bridgeActive: Flow<Boolean> = flowOf(false),
)

/** "Keep connected in background": driven by the sync mode, not a user switch, in Plan 1. */
data class KeepConnected(val on: Boolean, val enabled: Boolean, val caption: String?)

data class MeUiState(
    val loaded: Boolean = false,
    /** First name for Today's greeting; null when not set. */
    val name: String? = null,
    val ringName: String = MeMath.DEFAULT_RING_NAME,
    /** "Firmware 1.18 · connected", or just the connection when no handshake has been seen. */
    val ringStatus: String = "",
    val batteryPct: Int? = null,
    val lastSync: String = "",
    val keepConnected: KeepConnected = KeepConnected(on = false, enabled = false, caption = null),
    /** The Automatic checks row's caption ("Heart rate every 10 min · SpO₂ every 30 min"); null until a ring is known. */
    val autoChecks: String? = null,
    val stepGoal: Int = 8000,
    val sleepGoalMin: Int = 450,
    val bedtime: LocalTime = LocalTime.of(23, 15),
    val profile: UserProfile = Settings.DEFAULT_PROFILE,
    val hasProfile: Boolean = false,
    val appLockEnabled: Boolean = true,
    val lockAfterSec: Int = 60,
    /** Agent & sync row value: the server's name or "Not set up". */
    val agentSync: String = MeMath.AGENT_NOT_SET_UP,
    /** Data & privacy: "Sent to your server this month: 12 KB". */
    val sentThisMonth: String = MeMath.sentThisMonth(0),
)

object MeMath {
    const val DEFAULT_RING_NAME = "Your ring"
    const val AGENT_NOT_SET_UP = "Not set up"
    /** Leads the ring card's status while the developer Mac bridge carries the ring's data. */
    const val USING_BRIDGE = "Using Mac bridge"

    /** Start of [now]'s month in [zone], in epoch ms. */
    fun monthStartMs(now: Instant, zone: java.time.ZoneId): Long =
        now.atZone(zone).toLocalDate().withDayOfMonth(1).atStartOfDay(zone).toInstant().toEpochMilli()

    /** "Sent to your server this month: 13 KB" (KB below 1 MB, then MB with one decimal). */
    fun sentThisMonth(bytes: Long): String {
        val b = bytes.coerceAtLeast(0)
        val amount = if (b < 1024L * 1024) "${java.text.NumberFormat.getIntegerInstance(java.util.Locale.UK).format((b + 512) / 1024)} KB" else String.format(java.util.Locale.UK, "%.1f MB", b / (1024.0 * 1024))
        return "Sent to your server this month: $amount"
    }
    /**
     * The Automatic checks row's caption: each check the paired ring declares, as wanted ("Heart rate every 10 min ·
     * SpO₂ off"); null when no ring or capabilities are known. The screen itself shows what the ring accepted.
     */
    fun autoChecks(s: SettingsSnapshot): String? {
        val specs = AutomaticChecksCopy.settable(s.ringCapabilities?.monitoring).takeIf { s.pairedRing != null && it.isNotEmpty() } ?: return null
        val wanted = s.monitoringPrefs().migrated(specs).schedule
        return AutomaticChecksCopy.ORDER.filter { it in specs }.joinToString(" · ") { kind ->
            val w = wanted[kind] ?: AutomaticChecksCopy.default(kind, specs.getValue(kind))
            val name = AutomaticChecksCopy.title(kind)
            if (!w.enabled) "$name off" else "$name every ${AutomaticChecksCopy.short(w.intervalMin)}"
        }
    }
    /** Same cap as onboarding: long enough for any first name; the greeting has one line. */
    const val MAX_NAME = dev.avijit.orbithealth.ui.onboarding.OnboardingViewModel.MAX_NAME

    /** Trimmed and capped; blank means no name. */
    fun cleanName(raw: String): String? = raw.trim().take(MAX_NAME).trim().ifEmpty { null }

    /**
     * Caption under "App lock". On API 31–32 recents privacy needs FLAG_SECURE, which also blocks screenshots, so say so.
     */
    fun appLockCaption(lockAfterSec: Int, sdkInt: Int): String {
        val after = if (lockAfterSec % 60 == 0) "${lockAfterSec / 60} min" else "$lockAfterSec s"
        val recents = if (sdkInt >= 33) "Hides Orbit Health in recent apps." else "Hides Orbit Health in recent apps and blocks screenshots."
        return "Fingerprint or phone PIN after $after away. $recents"
    }

    /** Lower-case connection words for the ring card ("connected", "last synced 2 min ago"). */
    fun connection(state: SyncState, linked: Boolean, lastSyncedAt: Instant?, now: Instant): String = when {
        state is SyncState.Syncing || state is SyncState.Connecting -> "syncing"
        linked -> "connected"
        state is SyncState.RingBusy -> "ring busy"
        lastSyncedAt != null -> "last synced ${ago(lastSyncedAt, now)}"
        else -> "not synced yet"
    }

    /** [text] with the spaces inside each " · "-separated phrase made no-break, so lines only wrap at the dots. */
    fun unbreakablePhrases(text: String): String = text.split(" · ").joinToString(" · ") { it.replace(' ', '\u00A0') }

    /** "just now", "2 min ago", "3 hr ago", "4 days ago". */
    fun ago(at: Instant, now: Instant): String {
        val min = Duration.between(at, now).toMinutes().coerceAtLeast(0)
        return when {
            min < 1 -> "just now"
            min < 60 -> "$min min ago"
            min < 24 * 60 -> "${min / 60} hr ago"
            min < 48 * 60 -> "1 day ago"
            else -> "${min / (24 * 60)} days ago"
        }
    }

    /** "7h 30m", "8h". */
    fun hoursMinutes(min: Int): String = if (min % 60 == 0) "${min / 60}h" else "${min / 60}h ${"%02d".format(min % 60)}m"

    fun keepConnected(mode: SyncMode, vendorAppName: String? = null) = when (mode) {
        SyncMode.EXCLUSIVE -> KeepConnected(on = true, enabled = true, caption = null)
        SyncMode.COEXISTENCE ->
            KeepConnected(on = false, enabled = false, caption = VendorAppCopy.keepConnectedCaption(vendorAppName))
    }

    fun state(
        s: SettingsSnapshot, sync: SyncState, linked: Boolean, mode: SyncMode, now: Instant, vendorAppName: String? = null,
        bridge: Boolean = false,
    ): MeUiState {
        val conn = connection(sync, linked, s.lastSyncedAt, now)
        val status = s.ringFirmware?.let { "Firmware $it · $conn" } ?: conn.replaceFirstChar { it.uppercase() }
        return MeUiState(
            loaded = true,
            name = s.name,
            ringName = s.ringName?.takeIf { it.isNotBlank() } ?: DEFAULT_RING_NAME,
            ringStatus = if (bridge) "$USING_BRIDGE · $conn" else status,
            batteryPct = s.ringBatteryPct,
            lastSync = when {
                sync is SyncState.Syncing || sync is SyncState.Connecting -> "Syncing…"
                s.lastSyncedAt != null -> ago(s.lastSyncedAt, now).let { if (it == "just now") "Just now" else it }
                else -> "Not yet"
            },
            keepConnected = keepConnected(mode, vendorAppName),
            autoChecks = autoChecks(s),
            stepGoal = s.stepGoalBase,
            sleepGoalMin = s.sleepGoalMin,
            bedtime = s.bedtimeTarget,
            profile = s.profile ?: Settings.DEFAULT_PROFILE,
            hasProfile = s.profile != null,
            appLockEnabled = s.appLockEnabled,
            lockAfterSec = s.lockAfterSec,
        )
    }
}

/**
 * Me & my ring: the ring's health and sync, the door to its automatic checks (their own screen), goals and profile.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MeViewModel(private val deps: MeDeps, scope: CoroutineScope? = null) : ViewModel() {
    private val scope: CoroutineScope = scope ?: viewModelScope

    val state: StateFlow<MeUiState> =
        combine(
            combine(
                deps.settings, deps.syncState, deps.linked,
                combine(deps.mode, deps.vendorAppName, deps.bridgeActive) { m, v, b -> Triple(m, v, b) }, deps.now,
            ) { s, sync, linked, (mode, vendor, bridge), now ->
                MeMath.state(s, sync, linked, mode, now, vendor, bridge)
            },
            deps.serverName,
            // The month start follows the clock (each tick), so the count restarts on the 1st without a restart.
            deps.now.map { MeMath.monthStartMs(it, deps.clock.zone) }.distinctUntilChanged().flatMapLatest { deps.sentSince(it) },
        ) { st, server, sent ->
            st.copy(agentSync = server ?: MeMath.AGENT_NOT_SET_UP, sentThisMonth = MeMath.sentThisMonth(sent))
        }.stateIn(this.scope, SharingStarted.Eagerly, MeUiState())

    fun setStepGoal(v: Int) { scope.launch { deps.store.setStepGoalBase(v) } }
    fun setSleepGoalMin(v: Int) { scope.launch { deps.store.setSleepGoalMin(v) } }
    fun setBedtime(v: LocalTime) { scope.launch { deps.store.setBedtimeTarget(v) } }
    fun setProfile(p: UserProfile) { scope.launch { deps.store.setProfile(p) } }
    fun setName(raw: String) { scope.launch { deps.store.setName(MeMath.cleanName(raw)) } }
    /**
     * Turning the lock on is saved at once; turning it off is saved only after [authenticate] succeeds (biometric or
     * phone PIN), so someone holding an unlocked phone cannot quietly remove it. Returns whether it was saved.
     * Suspends in the caller's scope (the screen's), so a prompt left open is dropped with the screen.
     */
    suspend fun setAppLockEnabled(on: Boolean, authenticate: suspend () -> Boolean): Boolean {
        if (!on && !authenticate()) return false
        deps.store.setAppLockEnabled(on)
        return true
    }
}
