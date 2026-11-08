package dev.avijit.orbithealth.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.avijit.orbithealth.ring.DeviceInfo
import dev.avijit.orbithealth.ring.MonitoringConfig
import dev.avijit.orbithealth.ring.RingCapabilities
import dev.avijit.orbithealth.ring.Sex
import dev.avijit.orbithealth.ring.UserProfile
import java.time.Instant
import java.time.LocalTime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.orbitPrefs: DataStore<Preferences> by preferencesDataStore(name = "orbit_settings")

data class SettingsSnapshot(
    /** Null until onboarding collects it. */
    val profile: UserProfile? = null,
    /** What the user asked to be called; used only for Today's greeting. Null when they left it blank. */
    val name: String? = null,
    val ringAddress: String? = null,
    /** Family of the paired ring; read as [PairedRing.LEGACY_FAMILY_ID] for installs that stored only an address. */
    val ringFamilyId: String? = null,
    /** Model name the family matched at pairing ("TK5"); null for installs paired before it was stored. */
    val ringModelName: String? = null,
    /** What the paired ring declared at its latest handshake (cached for offline screens); null until the first one. */
    val ringCapabilities: RingCapabilities? = null,
    /** The ring's advertised name from the pairing scan ("TK5 A1B2"); null if it had none. */
    val ringName: String? = null,
    /** From the most recent handshake; null until the first one. */
    val ringFirmware: String? = null,
    val ringBatteryPct: Int? = null,
    /** Automatic heart-rate check interval (min). Default 10 (also before M2); a stored 5 is migrated to 10. */
    val hrIntervalMin: Int = 10,
    /** Automatic SpO₂ checks on/off (every 30 min since M2; was every 60 min). Default on. */
    val spo2Overnight: Boolean = true,
    /** The automatic-check schedule the paired ring last accepted from Orbit; null until it accepted one. */
    val ringMonitoringAccepted: MonitoringConfig? = null,
    /** When the ring last accepted any of [ringMonitoringAccepted] ("Last set from Orbit Health · 2 hr ago"). */
    val ringMonitoringAcceptedAt: Instant? = null,
    /**
     * The automatic-check schedule the user wants (Automatic checks screen), per kind; null on installs that only have
     * [hrIntervalMin] and [spo2Overnight] (see `sync.monitoringPrefs()`).
     */
    val ringMonitoringWanted: MonitoringConfig? = null,
    val stepGoalBase: Int = 8000,
    val sleepGoalMin: Int = 450,
    val bedtimeTarget: LocalTime = LocalTime.of(23, 15),
    /** Developer switch (shake on Me): talk to the ring through the loopback-only Mac BLE bridge. */
    val useMacBridge: Boolean = false,
    /** Onboarding asked for the battery-optimisation exemption once; never ask again. */
    val batteryPromptAsked: Boolean = false,
    /** When the last sync finished successfully; null until the first one. Survives process restarts. */
    val lastSyncedAt: Instant? = null,
    /** When the SmartHealth coexistence banner was last dismissed; it returns 7 days later. */
    val coexistenceBannerDismissedAt: Instant? = null,
    /** Local app lock (biometric or phone PIN); on by default. Gates the UI only, see security/AppLock. */
    val appLockEnabled: Boolean = true,
    /** Seconds in the background before the app locks again. */
    val lockAfterSec: Int = 60,
    /** "Check for updates automatically" (GitHub Releases, at most once a day). On by default. */
    val updateAutoCheck: Boolean = true,
    /** When the update check last ran (automatic or manual); null before the first. */
    val updateLastCheckAt: Instant? = null,
    /** The newest release version that check saw ("0.7.1"); null when it saw none. */
    val updateLastSeenVersion: String? = null,
) {
    /** The paired ring, or null before onboarding. */
    val pairedRing: PairedRing? get() = PairedRing.fromStored(ringAddress, ringFamilyId, ringModelName)

    /** A copy with [ring] as the paired ring (null = unpaired). */
    fun withPairedRing(ring: PairedRing?) =
        copy(ringAddress = ring?.address, ringFamilyId = ring?.familyId, ringModelName = ring?.modelName)
}

/** App settings in DataStore (not sensitive health data; that lives in the encrypted DB). */
class Settings(context: Context) {
    private val store = context.applicationContext.orbitPrefs

    val values: Flow<SettingsSnapshot> = store.data.map(::snapshotOf)

    suspend fun current(): SettingsSnapshot = values.first()

    /** The paired ring's cached capabilities (latest handshake), for capability-driven screens; null until known. */
    val ringCapabilities: Flow<RingCapabilities?> = values.map { it.ringCapabilities }.distinctUntilChanged()

    /** What the paired ring last accepted from Orbit's automatic-check settings ("last set from Orbit"). */
    val ringMonitoringAccepted: Flow<MonitoringConfig?> = values.map { it.ringMonitoringAccepted }.distinctUntilChanged()

    /** Stores the schedule the ring accepted (from [dev.avijit.orbithealth.sync.SyncEngine]), replacing the old one. */
    suspend fun setRingMonitoringAccepted(c: MonitoringConfig, at: Instant = Instant.now()) = store.edit {
        it[RING_MONITORING] = MonitoringCodec.encode(c)
        it[RING_MONITORING_AT] = at.toEpochMilli()
    }

    /**
     * Records what the ring just accepted on top of what it accepted before: a kind it didn't take this time (refused,
     * or not sent) keeps its last accepted value, because the ring still has that. Nothing accepted changes nothing.
     */
    suspend fun recordRingMonitoringAccepted(c: MonitoringConfig, at: Instant = Instant.now()) = store.edit { recordAccepted(it, c, at) }

    /**
     * Saves the schedule the user wants. The heart-rate interval and SpO₂ switch are mirrored to their pre-M4 keys, so
     * those stay meaningful.
     */
    suspend fun setRingMonitoringWanted(c: MonitoringConfig) = store.edit {
        it[RING_MONITORING_WANTED] = MonitoringCodec.encode(c)
        c[dev.avijit.orbithealth.ring.MonitorKind.HEART_RATE]?.let { hr -> it[HR_INTERVAL] = hr.intervalMin }
        c[dev.avijit.orbithealth.ring.MonitorKind.SPO2]?.let { spo2 -> it[SPO2_OVERNIGHT] = spo2.enabled }
    }

    /** Caches [caps] from a handshake with the paired ring. */
    suspend fun setRingCapabilities(caps: RingCapabilities) = store.edit { it[RING_CAPS] = CapabilitiesCodec.encode(caps) }

    suspend fun setProfile(p: UserProfile) = store.edit {
        it[HEIGHT] = p.heightCm; it[WEIGHT] = p.weightKg; it[SEX] = p.sex.name; it[AGE] = p.age
    }
    /** Trimmed; blank clears it (the greeting then has no name). */
    suspend fun setName(name: String?) = store.edit {
        val n = name?.trim()
        if (n.isNullOrEmpty()) it.remove(NAME) else it[NAME] = n
    }
    /**
     * Pairs [ring] (family, address and model together) and drops the old ring's cached capabilities and accepted
     * schedule; null forgets the ring.
     */
    suspend fun setPairedRing(ring: PairedRing?) = store.edit { pairRing(it, ring) }
    suspend fun setRingName(name: String?) = store.edit { if (name.isNullOrBlank()) it.remove(RING_NAME) else it[RING_NAME] = name }
    /** Firmware and battery from a handshake, shown on Me. */
    suspend fun setDeviceInfo(info: DeviceInfo) = store.edit {
        it[RING_FIRMWARE] = info.firmware
        it[RING_BATTERY] = info.batteryPct
    }
    suspend fun setStepGoalBase(v: Int) = store.edit { it[STEP_GOAL] = v }
    suspend fun setSleepGoalMin(v: Int) = store.edit { it[SLEEP_GOAL] = v }
    suspend fun setBedtimeTarget(v: LocalTime) = store.edit { it[BEDTIME] = v.toString() }
    suspend fun setUseMacBridge(v: Boolean) = store.edit { it[USE_MAC_BRIDGE] = v }
    suspend fun setBatteryPromptAsked() = store.edit { it[BATTERY_PROMPT_ASKED] = true }
    suspend fun setLastSyncedAt(at: Instant) = store.edit { it[LAST_SYNCED_AT] = at.toEpochMilli() }
    suspend fun setCoexistenceBannerDismissedAt(at: Instant) = store.edit { it[BANNER_DISMISSED_AT] = at.toEpochMilli() }
    suspend fun setAppLockEnabled(v: Boolean) = store.edit { it[APP_LOCK_ENABLED] = v }
    suspend fun setLockAfterSec(v: Int) = store.edit { it[LOCK_AFTER_SEC] = v }
    suspend fun setUpdateAutoCheck(v: Boolean) = store.edit { it[UPDATE_AUTO_CHECK] = v }
    /** Records an update check at [at] that saw [seenVersion] as the latest release (null: none). */
    suspend fun recordUpdateCheck(at: Instant, seenVersion: String?) = store.edit {
        it[UPDATE_LAST_CHECK] = at.toEpochMilli()
        if (seenVersion == null) it.remove(UPDATE_SEEN_VERSION) else it[UPDATE_SEEN_VERSION] = seenVersion
    }

    /** Forgets every setting (ring, profile, goals). */
    suspend fun clearAll() = store.edit { it.clear() }

    /** The latest Orbit Server push outcome as JSON (no health values or tokens), or null. */
    suspend fun serverSyncResult(): String? = store.data.first()[SERVER_SYNC_RESULT]

    suspend fun setServerSyncResult(json: String?) = store.edit { if (json == null) it.remove(SERVER_SYNC_RESULT) else it[SERVER_SYNC_RESULT] = json }

    companion object {
        /** Sent in the handshake until onboarding has collected the real profile. */
        val DEFAULT_PROFILE = UserProfile(170, 70, Sex.UNSPECIFIED, 35)

        /** The edit behind [setPairedRing]. */
        internal fun pairRing(p: MutablePreferences, ring: PairedRing?) {
            p.remove(RING_CAPS)
            p.remove(RING_MONITORING)
            p.remove(RING_MONITORING_AT)
            if (ring == null) {
                p.remove(RING_ADDRESS); p.remove(RING_FAMILY); p.remove(RING_MODEL)
            } else {
                p[RING_ADDRESS] = ring.address
                p[RING_FAMILY] = ring.familyId
                if (ring.modelName == null) p.remove(RING_MODEL) else p[RING_MODEL] = ring.modelName
            }
        }

        /** The edit behind [recordRingMonitoringAccepted]. */
        internal fun recordAccepted(p: MutablePreferences, c: MonitoringConfig, at: Instant) {
            if (c.settings.isEmpty()) return
            val before = MonitoringCodec.decode(p[RING_MONITORING])?.settings.orEmpty()
            p[RING_MONITORING] = MonitoringCodec.encode(MonitoringConfig(before + c.settings))
            p[RING_MONITORING_AT] = at.toEpochMilli()
        }

        /** Maps stored preferences to a snapshot. Rings paired before family ids were stored read as YCBT. */
        internal fun snapshotOf(p: Preferences): SettingsSnapshot {
            val d = SettingsSnapshot()
            val address = p[RING_ADDRESS]
            return SettingsSnapshot(
                profile = profileOf(p),
                name = p[NAME]?.takeIf { it.isNotBlank() },
                ringAddress = address,
                ringFamilyId = p[RING_FAMILY] ?: address?.let { PairedRing.LEGACY_FAMILY_ID },
                ringModelName = p[RING_MODEL],
                ringCapabilities = CapabilitiesCodec.decode(p[RING_CAPS]),
                ringName = p[RING_NAME],
                ringFirmware = p[RING_FIRMWARE],
                ringBatteryPct = p[RING_BATTERY],
                hrIntervalMin = p[HR_INTERVAL] ?: d.hrIntervalMin,
                spo2Overnight = p[SPO2_OVERNIGHT] ?: d.spo2Overnight,
                ringMonitoringAccepted = MonitoringCodec.decode(p[RING_MONITORING]),
                ringMonitoringAcceptedAt = p[RING_MONITORING_AT]?.let(Instant::ofEpochMilli),
                ringMonitoringWanted = MonitoringCodec.decode(p[RING_MONITORING_WANTED]),
                stepGoalBase = p[STEP_GOAL] ?: d.stepGoalBase,
                sleepGoalMin = p[SLEEP_GOAL] ?: d.sleepGoalMin,
                bedtimeTarget = p[BEDTIME]?.let { runCatching { LocalTime.parse(it) }.getOrNull() } ?: d.bedtimeTarget,
                useMacBridge = p[USE_MAC_BRIDGE] ?: d.useMacBridge,
                batteryPromptAsked = p[BATTERY_PROMPT_ASKED] ?: d.batteryPromptAsked,
                lastSyncedAt = p[LAST_SYNCED_AT]?.let(Instant::ofEpochMilli),
                coexistenceBannerDismissedAt = p[BANNER_DISMISSED_AT]?.let(Instant::ofEpochMilli),
                appLockEnabled = p[APP_LOCK_ENABLED] ?: d.appLockEnabled,
                lockAfterSec = p[LOCK_AFTER_SEC] ?: d.lockAfterSec,
                updateAutoCheck = p[UPDATE_AUTO_CHECK] ?: d.updateAutoCheck,
                updateLastCheckAt = p[UPDATE_LAST_CHECK]?.let(Instant::ofEpochMilli),
                updateLastSeenVersion = p[UPDATE_SEEN_VERSION],
            )
        }

        private fun profileOf(p: Preferences): UserProfile? {
            val h = p[HEIGHT] ?: return null
            val w = p[WEIGHT] ?: return null
            val a = p[AGE] ?: return null
            val sex = p[SEX]?.let { runCatching { Sex.valueOf(it) }.getOrNull() } ?: Sex.UNSPECIFIED
            return UserProfile(h, w, sex, a)
        }

        private val NAME = stringPreferencesKey("profile_name")
        private val SERVER_SYNC_RESULT = stringPreferencesKey("server_sync_result")
        private val HEIGHT = intPreferencesKey("profile_height_cm")
        private val WEIGHT = intPreferencesKey("profile_weight_kg")
        private val SEX = stringPreferencesKey("profile_sex")
        private val AGE = intPreferencesKey("profile_age")
        private val RING_ADDRESS = stringPreferencesKey("ring_address")
        private val RING_FAMILY = stringPreferencesKey("ring_family_id")
        private val RING_MODEL = stringPreferencesKey("ring_model_name")
        private val RING_CAPS = stringPreferencesKey("ring_capabilities_json")
        private val RING_MONITORING = stringPreferencesKey("ring_monitoring_accepted_json")
        private val RING_MONITORING_AT = longPreferencesKey("ring_monitoring_accepted_at_ms")
        private val RING_MONITORING_WANTED = stringPreferencesKey("ring_monitoring_wanted_json")
        private val RING_NAME = stringPreferencesKey("ring_name")
        private val RING_FIRMWARE = stringPreferencesKey("ring_firmware")
        private val RING_BATTERY = intPreferencesKey("ring_battery_pct")
        private val HR_INTERVAL = intPreferencesKey("hr_interval_min")
        private val SPO2_OVERNIGHT = booleanPreferencesKey("spo2_overnight")
        private val STEP_GOAL = intPreferencesKey("step_goal_base")
        private val SLEEP_GOAL = intPreferencesKey("sleep_goal_min")
        private val BEDTIME = stringPreferencesKey("bedtime_target")
        private val USE_MAC_BRIDGE = booleanPreferencesKey("use_mac_bridge")
        private val BATTERY_PROMPT_ASKED = booleanPreferencesKey("battery_prompt_asked")
        private val LAST_SYNCED_AT = longPreferencesKey("last_synced_at_ms")
        private val BANNER_DISMISSED_AT = longPreferencesKey("coexistence_banner_dismissed_at_ms")
        private val APP_LOCK_ENABLED = booleanPreferencesKey("app_lock_enabled")
        private val LOCK_AFTER_SEC = intPreferencesKey("lock_after_sec")
        private val UPDATE_AUTO_CHECK = booleanPreferencesKey("update_auto_check")
        private val UPDATE_LAST_CHECK = longPreferencesKey("update_last_check_ms")
        private val UPDATE_SEEN_VERSION = stringPreferencesKey("update_last_seen_version")
    }
}
