package dev.avijit.orbithealth.prefs

import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.avijit.orbithealth.ring.MeasureKind
import dev.avijit.orbithealth.ring.MeasureSpec
import dev.avijit.orbithealth.ring.Metric
import dev.avijit.orbithealth.ring.MonitorKind
import dev.avijit.orbithealth.ring.MonitorSpec
import dev.avijit.orbithealth.ring.RingCapabilities
import dev.avijit.orbithealth.ycbt.decodeCapabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CapabilitiesCodecTest {
    private val full = RingCapabilities(
        history = setOf(Metric.HEART_RATE, Metric.SPO2, Metric.BLOOD_PRESSURE_SYS, Metric.BLOOD_PRESSURE_DIA),
        sleep = true,
        workouts = false,
        realtime = true,
        measure = mapOf(
            MeasureKind.HEART_RATE to MeasureSpec(30, live = true, estimate = false),
            MeasureKind.BLOOD_PRESSURE to MeasureSpec(45, live = false, estimate = true),
        ),
        monitoring = mapOf(
            MonitorKind.HEART_RATE to MonitorSpec(listOf(10, 15, 20, 30, 60), canTurnOff = true, supportsWindow = false),
            MonitorKind.SPO2 to MonitorSpec(listOf(60), canTurnOff = true, supportsWindow = true),
        ),
        canClearHistory = true,
    )

    @Test
    fun capabilitiesRoundTrip() {
        assertEquals(full, CapabilitiesCodec.decode(CapabilitiesCodec.encode(full)))
        val empty = RingCapabilities(emptySet(), sleep = false, workouts = false, realtime = false)
        assertEquals(empty, CapabilitiesCodec.decode(CapabilitiesCodec.encode(empty)))
        // The real TK5 bitmap shape (0x0201 payload with HR, SpO₂, sleep, realtime bits).
        val tk5 = decodeCapabilities(byteArrayOf(0xF9.toByte(), 0x0B))
        assertEquals(tk5, CapabilitiesCodec.decode(CapabilitiesCodec.encode(tk5)))
    }

    @Test
    fun cachedCapabilitiesReadBackThroughSettings() {
        val p = preferencesOf(
            stringPreferencesKey("ring_address") to "AA:BB",
            stringPreferencesKey("ring_capabilities_json") to CapabilitiesCodec.encode(full),
        )
        assertEquals(full, Settings.snapshotOf(p).ringCapabilities)
        assertNull(Settings.snapshotOf(preferencesOf(stringPreferencesKey("ring_address") to "AA:BB")).ringCapabilities)
    }

    @Test
    fun unknownKindsAreSkippedAndGarbageIsNull() {
        val json = CapabilitiesCodec.encode(full).replace("\"BLOOD_PRESSURE\"", "\"GLUCOSE\"")
        assertEquals(full.measure - MeasureKind.BLOOD_PRESSURE, CapabilitiesCodec.decode(json)!!.measure)
        assertNull(CapabilitiesCodec.decode("{not json"))
        assertNull(CapabilitiesCodec.decode("{\"v\":99}"))
        assertNull(CapabilitiesCodec.decode(null))
    }
}
