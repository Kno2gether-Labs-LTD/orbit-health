package dev.avijit.orbithealth.prefs

import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.avijit.orbithealth.ring.Metric
import dev.avijit.orbithealth.ring.MonitorKind
import dev.avijit.orbithealth.ring.MonitorSetting
import dev.avijit.orbithealth.ring.MonitoringConfig
import dev.avijit.orbithealth.ring.RingCapabilities
import dev.avijit.orbithealth.ring.MonitorSpec
import dev.avijit.orbithealth.sync.MonitoringPrefs
import dev.avijit.orbithealth.sync.monitoringPrefs
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class PairedRingTest {
    private val address = stringPreferencesKey("ring_address")
    private val family = stringPreferencesKey("ring_family_id")
    private val model = stringPreferencesKey("ring_model_name")

    @Test
    fun oldPairedRingMigratesToYcbt() {
        // An install from before multi-ring support stored only the address.
        val s = Settings.snapshotOf(preferencesOf(address to "C3:1A:00:00:A1:B2"))
        assertEquals("C3:1A:00:00:A1:B2", s.ringAddress)
        assertEquals("ycbt", s.ringFamilyId)
        assertNull(s.ringModelName)
        assertEquals(PairedRing("ycbt", "C3:1A:00:00:A1:B2", null), s.pairedRing)
        assertEquals(PairedRing("ycbt", "AA", null), PairedRing.fromStored("AA", null, null))
    }

    @Test
    fun storedFamilyAndModelAreKept() {
        val s = Settings.snapshotOf(preferencesOf(address to "AA:BB", family to "colmi", model to "R02"))
        assertEquals(PairedRing("colmi", "AA:BB", "R02"), s.pairedRing)
    }

    @Test
    fun unpairedHasNoRingAndNoFamily() {
        val s = Settings.snapshotOf(preferencesOf())
        assertNull(s.pairedRing)
        assertNull(s.ringFamilyId)
        assertNull(PairedRing.fromStored(null, "ycbt", null))
    }

    @Test
    fun withPairedRingSetsAllThree() {
        val s = SettingsSnapshot().withPairedRing(PairedRing("ycbt", "AA:BB", "TK5"))
        assertEquals("AA:BB", s.ringAddress)
        assertEquals("ycbt", s.ringFamilyId)
        assertEquals("TK5", s.ringModelName)
    }

    @Test
    fun monitoringPrefsMapToHeartRateAndSpo2() {
        val c = MonitoringPrefs(15, spo2Overnight = false).toRingConfig()
        assertEquals(MonitorSetting(enabled = true, intervalMin = 15), c[MonitorKind.HEART_RATE])
        assertEquals(MonitorSetting(enabled = false, intervalMin = 30), c[MonitorKind.SPO2])
        assertEquals(2, c.settings.size)
        // The old overnight switch on = SpO₂ every 30 min.
        assertEquals(MonitorSetting(enabled = true, intervalMin = 30), MonitoringPrefs(10, true).toRingConfig()[MonitorKind.SPO2])
    }

    @Test
    fun defaultsAreHeartRateEvery10AndSpo2On() {
        val s = Settings.snapshotOf(preferencesOf())
        assertEquals(
            MonitoringConfig(mapOf(MonitorKind.HEART_RATE to MonitorSetting(true, 10), MonitorKind.SPO2 to MonitorSetting(true, 30))),
            MonitoringPrefs(s.hrIntervalMin, s.spo2Overnight).toRingConfig(),
        )
    }

    @Test
    fun retiredFiveMinuteIntervalMigratesToTen() {
        assertEquals(MonitoringPrefs(10, false), MonitoringPrefs(5, false).migrated())
        assertEquals(MonitoringPrefs(15, true), MonitoringPrefs(15, true).migrated())
    }

    @Test
    fun setPairedRingClearsCachedCapabilitiesAndAcceptedSchedule() {
        val caps = RingCapabilities(setOf(Metric.HEART_RATE), sleep = true, workouts = false, realtime = true)
        val accepted = MonitoringConfig(mapOf(MonitorKind.HEART_RATE to MonitorSetting(true, 10)))
        val p = mutablePreferencesOf(
            address to "AA:BB",
            stringPreferencesKey("ring_capabilities_json") to CapabilitiesCodec.encode(caps),
            stringPreferencesKey("ring_monitoring_accepted_json") to MonitoringCodec.encode(accepted),
        )
        assertEquals(caps, Settings.snapshotOf(p).ringCapabilities)
        assertEquals(accepted, Settings.snapshotOf(p).ringMonitoringAccepted)

        Settings.pairRing(p, PairedRing("ycbt", "CC:DD", "TK5"))
        val s = Settings.snapshotOf(p)
        assertEquals(PairedRing("ycbt", "CC:DD", "TK5"), s.pairedRing)
        assertNull(p[stringPreferencesKey("ring_capabilities_json")])
        assertNull(s.ringCapabilities)
        assertNull(s.ringMonitoringAccepted)

        p[stringPreferencesKey("ring_capabilities_json")] = CapabilitiesCodec.encode(caps)
        Settings.pairRing(p, null)
        assertNull(Settings.snapshotOf(p).ringCapabilities)
        assertNull(Settings.snapshotOf(p).pairedRing)
    }

    @Test
    fun acceptedScheduleRoundTripsAndSkipsUnknownKinds() {
        val c = MonitoringConfig(mapOf(MonitorKind.HEART_RATE to MonitorSetting(false, 15), MonitorKind.SPO2 to MonitorSetting(true, 30)))
        assertEquals(c, MonitoringCodec.decode(MonitoringCodec.encode(c)))
        assertEquals(MonitoringConfig.EMPTY, MonitoringCodec.decode(MonitoringCodec.encode(MonitoringConfig.EMPTY)))
        val unknown = MonitoringCodec.encode(c).replace("\"SPO2\"", "\"GLUCOSE\"")
        assertEquals(MonitoringConfig(mapOf(MonitorKind.HEART_RATE to MonitorSetting(false, 15))), MonitoringCodec.decode(unknown))
        assertNull(MonitoringCodec.decode("{oops"))
        assertNull(MonitoringCodec.decode(null))
    }

    @Test
    fun valuesOutsideCapsSnapToTheNearestAllowedOne() {
        val specs = mapOf(
            MonitorKind.HEART_RATE to MonitorSpec(listOf(10, 15, 20, 30, 60), canTurnOff = true, supportsWindow = false),
            MonitorKind.SPO2 to MonitorSpec(listOf(30, 60), canTurnOff = true, supportsWindow = false),
        )
        // 5 -> 10, 25 -> 30 (tie between 20 and 30 goes to the longer, battery-kinder one), 45 -> 60 (tie), 50 -> 60.
        assertEquals(10, MonitoringPrefs.snapInterval(5, specs.getValue(MonitorKind.HEART_RATE).intervalsMin))
        assertEquals(30, MonitoringPrefs.snapInterval(25, specs.getValue(MonitorKind.HEART_RATE).intervalsMin))
        assertEquals(60, MonitoringPrefs.snapInterval(45, specs.getValue(MonitorKind.SPO2).intervalsMin))
        assertEquals(30, MonitoringPrefs.snapInterval(40, specs.getValue(MonitorKind.SPO2).intervalsMin))
        assertEquals(15, MonitoringPrefs.snapInterval(15, emptyList()))

        val stored = MonitoringPrefs(
            MonitoringConfig(
                mapOf(
                    MonitorKind.HEART_RATE to MonitorSetting(true, 25),
                    MonitorKind.SPO2 to MonitorSetting(true, 10, overnightOnly = true),
                ),
            ),
        )
        val snapped = stored.migrated(specs).schedule
        assertEquals(MonitorSetting(true, 30), snapped[MonitorKind.HEART_RATE])
        // No time window on this ring: overnight-only is dropped.
        assertEquals(MonitorSetting(true, 30), snapped[MonitorKind.SPO2])
        // Allowed values are left alone (same instance, so nothing is re-saved).
        val ok = MonitoringPrefs(15, true)
        assertSame(ok, ok.migrated(specs))
    }

    @Test
    fun aCheckThatCantBeTurnedOffIsOn() {
        val specs = mapOf(MonitorKind.HEART_RATE to MonitorSpec(listOf(10, 30), canTurnOff = false, supportsWindow = false))
        val off = MonitoringPrefs(MonitoringConfig(mapOf(MonitorKind.HEART_RATE to MonitorSetting(false, 30))))
        assertEquals(MonitorSetting(true, 30), off.migrated(specs).schedule[MonitorKind.HEART_RATE])
    }

    @Test
    fun wantedScheduleWinsOverTheOldKeysAndRoundTripsOvernight() {
        val wanted = MonitoringConfig(
            mapOf(
                MonitorKind.HEART_RATE to MonitorSetting(false, 20),
                MonitorKind.SPO2 to MonitorSetting(true, 60, overnightOnly = true),
            ),
        )
        assertEquals(wanted, MonitoringCodec.decode(MonitoringCodec.encode(wanted)))
        // Older values without the field still decode (overnight off).
        assertEquals(
            MonitorSetting(true, 30),
            MonitoringCodec.decode("""{"v":1,"settings":{"SPO2":{"enabled":true,"intervalMin":30}}}""")!![MonitorKind.SPO2],
        )
        val p = mutablePreferencesOf(
            address to "AA:BB",
            stringPreferencesKey("ring_monitoring_wanted_json") to MonitoringCodec.encode(wanted),
        )
        assertEquals(MonitoringPrefs(wanted), Settings.snapshotOf(p).monitoringPrefs())
        // Without it, the pre-M4 keys (defaults here) give HR every 10 and SpO₂ on.
        assertEquals(MonitoringPrefs(10, true), Settings.snapshotOf(preferencesOf()).monitoringPrefs())
    }

    @Test
    fun acceptedKindsMergeOverWhatTheRingHadBefore() {
        val p = mutablePreferencesOf(address to "AA:BB")
        val t1 = Instant.parse("2026-11-06T10:00:00Z")
        val t2 = Instant.parse("2026-11-06T11:00:00Z")
        Settings.recordAccepted(p, MonitoringConfig(mapOf(MonitorKind.HEART_RATE to MonitorSetting(true, 10), MonitorKind.SPO2 to MonitorSetting(true, 30))), t1)
        // Next time only SpO₂ went through (heart rate refused): the ring still has heart rate every 10.
        Settings.recordAccepted(p, MonitoringConfig(mapOf(MonitorKind.SPO2 to MonitorSetting(false, 30))), t2)
        val s = Settings.snapshotOf(p)
        assertEquals(MonitorSetting(true, 10), s.ringMonitoringAccepted!![MonitorKind.HEART_RATE])
        assertEquals(MonitorSetting(false, 30), s.ringMonitoringAccepted!![MonitorKind.SPO2])
        assertEquals(t2, s.ringMonitoringAcceptedAt)
        // Nothing accepted changes nothing.
        Settings.recordAccepted(p, MonitoringConfig.EMPTY, Instant.parse("2026-11-06T12:00:00Z"))
        assertEquals(t2, Settings.snapshotOf(p).ringMonitoringAcceptedAt)
        // Re-pairing forgets it.
        Settings.pairRing(p, PairedRing("ycbt", "CC:DD", "TK5"))
        assertNull(Settings.snapshotOf(p).ringMonitoringAcceptedAt)
    }
}
