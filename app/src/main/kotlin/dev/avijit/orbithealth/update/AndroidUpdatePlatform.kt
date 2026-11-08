package dev.avijit.orbithealth.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import dev.avijit.orbithealth.OrbitApp
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** PackageManager and PackageInstaller behind [UpdatePlatform]. */
class AndroidUpdatePlatform(context: Context) : UpdatePlatform {
    private val app = context.applicationContext
    private val pm get() = app.packageManager

    override fun installedFacts(): ApkFacts = facts(
        if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageInfo(app.packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
        } else {
            @Suppress("DEPRECATION") pm.getPackageInfo(app.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        },
    ) ?: ApkFacts(app.packageName, Long.MAX_VALUE, emptySet())

    override fun inspect(apk: File): ApkFacts? = runCatching {
        val info = if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageArchiveInfo(apk.path, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
        } else {
            @Suppress("DEPRECATION") pm.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNING_CERTIFICATES)
        }
        info?.let(::facts)
    }.getOrNull()

    override fun canInstall(): Boolean = pm.canRequestPackageInstalls()

    /** Android's "Install unknown apps" page for Orbit Health. */
    override fun openInstallPermission() {
        app.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    override suspend fun install(apk: File) = withContext(Dispatchers.IO) {
        val installer = pm.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(app.packageName)
            setSize(apk.length())
            if (Build.VERSION.SDK_INT >= 33) setPackageSource(PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            try {
                session.openWrite("base.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                // Explicit to our own non-exported receiver; mutable so the installer can add its status extras.
                val status = PendingIntent.getBroadcast(
                    app, id, Intent(app, InstallStatusReceiver::class.java).setPackage(app.packageName),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
                session.commit(status.intentSender)
            } catch (e: Exception) {
                session.abandon()
                throw e
            }
        }
    }

    private fun facts(info: PackageInfo): ApkFacts? {
        val signing = info.signingInfo ?: return ApkFacts(info.packageName, info.longVersionCode, emptySet())
        val current: Array<Signature> = if (signing.hasMultipleSigners()) signing.apkContentsSigners else
            signing.signingCertificateHistory?.takeLast(1)?.toTypedArray() ?: emptyArray()
        val history = if (signing.hasMultipleSigners()) emptyArray() else signing.signingCertificateHistory ?: emptyArray()
        return ApkFacts(info.packageName, info.longVersionCode, current.map(::sha256).toSet(), history.map(::sha256).toSet())
    }

    private fun sha256(sig: Signature): String = Sha256.hex(MessageDigest.getInstance("SHA-256").digest(sig.toByteArray()))
}

/**
 * The installer session's status (not exported: only the system, through our own PendingIntent, can send it). When
 * Android needs the user to confirm, its confirmation screen is opened; every other outcome goes to [UpdateManager].
 */
class InstallStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val updates = (context.applicationContext as OrbitApp).graph.updates
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                if (confirm == null) {
                    updates.onInstallResult(InstallResult.FAILED)
                } else {
                    try {
                        context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (e: Exception) {
                        Log.w("OrbitUpdate", "couldn't open the install confirmation: ${e.javaClass.simpleName}")
                        updates.onInstallResult(InstallResult.FAILED)
                    }
                }
            }
            PackageInstaller.STATUS_SUCCESS -> updates.onInstallResult(InstallResult.SUCCESS)
            PackageInstaller.STATUS_FAILURE_ABORTED -> updates.onInstallResult(InstallResult.CANCELLED)
            else -> updates.onInstallResult(InstallResult.FAILED, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
        }
    }
}
