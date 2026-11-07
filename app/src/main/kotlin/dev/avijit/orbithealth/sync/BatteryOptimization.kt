package dev.avijit.orbithealth.sync

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/** Helpers for the one-time battery-optimisation exemption request (shown from onboarding). */
object BatteryOptimization {
    fun isExempt(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    @SuppressLint("BatteryLife") // a continuously syncing companion device app is an accepted use case
    fun requestIntent(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
}
