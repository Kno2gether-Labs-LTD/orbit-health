package dev.avijit.orbithealth.sync

import dev.avijit.orbithealth.prefs.SettingsSnapshot

/**
 * The user's wanted automatic-check schedule from settings: the per-kind schedule saved by the Automatic checks screen,
 * or, on installs from before it, the heart-rate interval and SpO₂ switch.
 */
fun SettingsSnapshot.monitoringPrefs(): MonitoringPrefs =
    ringMonitoringWanted?.let(::MonitoringPrefs) ?: MonitoringPrefs(hrIntervalMin, spo2Overnight)
