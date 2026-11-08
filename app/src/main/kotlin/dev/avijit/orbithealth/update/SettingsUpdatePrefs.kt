package dev.avijit.orbithealth.update

import dev.avijit.orbithealth.prefs.Settings
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** [UpdatePrefs] in the app's settings DataStore. */
class SettingsUpdatePrefs(private val settings: Settings) : UpdatePrefs {
    override val autoCheck: Flow<Boolean> = settings.values.map { it.updateAutoCheck }.distinctUntilChanged()
    override suspend fun setAutoCheck(on: Boolean) { settings.setUpdateAutoCheck(on) }
    override suspend fun lastCheckMs(): Long? = settings.current().updateLastCheckAt?.toEpochMilli()
    override suspend fun lastSeenVersion(): String? = settings.current().updateLastSeenVersion
    override suspend fun recordCheck(atMs: Long, seenVersion: String?) { settings.recordUpdateCheck(Instant.ofEpochMilli(atMs), seenVersion) }
}
