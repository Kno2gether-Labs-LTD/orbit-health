package dev.avijit.orbithealth.sync

import dev.avijit.orbithealth.ring.MonitoringConfig

/** The prefs a ring config was built from by [MonitoringPrefs.toRingConfig] (for fakes that record what was sent). */
fun MonitoringConfig.toPrefs() = MonitoringPrefs(this)
