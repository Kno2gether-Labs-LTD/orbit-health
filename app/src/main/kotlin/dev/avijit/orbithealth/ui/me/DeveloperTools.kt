package dev.avijit.orbithealth.ui.me

import kotlinx.coroutines.flow.Flow

/**
 * The tools in Me's "Developer" group, in every build, revealed only by shaking the phone on Me (and hidden again when
 * the app closes). Implemented by [dev.avijit.orbithealth.devtools.AppDeveloperTools]; faked in tests. Seed and reset
 * each ask for confirmation on screen first. Nothing here is reachable from outside the app: the adb receiver that can
 * do the same lives in the debug source set only.
 */
interface DeveloperTools {
    /** Settings.useMacBridge. */
    val useMacBridge: Flow<Boolean>
    /** The code to start `ble_bridge.py --code` with while the bridge is on (this process only); null when off. */
    val bridgeCode: Flow<String?> get() = kotlinx.coroutines.flow.flowOf(null)
    /** Selects (or leaves) the Mac BLE bridge and drops the current link so the next sync uses the new transport. */
    suspend fun setUseMacBridge(on: Boolean)
    /** Writes 35 days of sample data (SampleDataSeeder). Returns a one-line result for the screen. */
    suspend fun seedSampleData(): String
    /** Wipes the database and every setting. The app returns to onboarding. */
    suspend fun reset()
}
