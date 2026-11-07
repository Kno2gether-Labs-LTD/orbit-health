package dev.avijit.orbithealth.ui.me

import kotlinx.coroutines.flow.Flow

/**
 * Debug-build tools shown in Me's "Developer" section. Main code only knows this interface; `BuildDeveloperTools`
 * lives in the debug source set (real tools) and the release source set (null: no section), so release never
 * references debug classes.
 */
interface DeveloperTools {
    /** Settings.useMacBridge. */
    val useMacBridge: Flow<Boolean>
    /** Selects (or leaves) the Mac BLE bridge and drops the current link so the next sync uses the new transport. */
    suspend fun setUseMacBridge(on: Boolean)
    /** Writes 35 days of sample data (DebugSeeder). Returns a one-line result for the screen. */
    suspend fun seedSampleData(): String
    /** Wipes the database and every setting. The app returns to onboarding. */
    suspend fun reset()
}
