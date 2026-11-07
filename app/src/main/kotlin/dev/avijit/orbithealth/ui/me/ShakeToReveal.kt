package dev.avijit.orbithealth.ui.me

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import kotlin.math.sqrt

/**
 * Counts deliberate shakes: a jolt above [THRESHOLD_G] counts once per [MIN_GAP_MS], and [SHAKES] of them within
 * [WINDOW_MS] is a shake gesture. A pocket bump or a single flick never is. Pure logic, fed accelerometer readings.
 */
class ShakeDetector(private val onShake: () -> Unit) {
    private val jolts = ArrayDeque<Long>()

    /** One accelerometer reading in m/s² at [timeMs]. */
    fun feed(timeMs: Long, x: Float, y: Float, z: Float) {
        val g = sqrt(x * x + y * y + z * z) / SensorManager.GRAVITY_EARTH
        if (g < THRESHOLD_G) return
        if (jolts.isNotEmpty() && timeMs - jolts.last() < MIN_GAP_MS) return
        jolts.addLast(timeMs)
        while (jolts.isNotEmpty() && timeMs - jolts.first() > WINDOW_MS) jolts.removeFirst()
        if (jolts.size >= SHAKES) {
            jolts.clear()
            onShake()
        }
    }

    companion object {
        const val THRESHOLD_G = 2.5f
        const val MIN_GAP_MS = 250L
        const val WINDOW_MS = 2_000L
        const val SHAKES = 3
    }
}

/** Calls [onShake] on a shake gesture while this is composed and [enabled]; listens to nothing otherwise. */
@Composable
fun ShakeListener(enabled: Boolean, onShake: () -> Unit) {
    val context = LocalContext.current
    val latest by rememberUpdatedState(onShake)
    DisposableEffect(enabled, context) {
        val sensors = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val accel = sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (!enabled || sensors == null || accel == null) return@DisposableEffect onDispose {}
        val detector = ShakeDetector { latest() }
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) = detector.feed(e.timestamp / 1_000_000, e.values[0], e.values[1], e.values[2])
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        sensors.registerListener(listener, accel, SensorManager.SENSOR_DELAY_UI)
        onDispose { sensors.unregisterListener(listener) }
    }
}
