package com.omega.arabify

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import java.io.File

internal object ApkRestoreInstaller {
    fun install(context: Context, backup: VerifiedApkBackup) {
        require(backup.apks.isNotEmpty()) { "لا توجد ملفات APK للاستعادة." }
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(backup.packageName)
            setInstallReason(PackageManager.INSTALL_REASON_USER)
            setSize(backup.apks.sumOf { it.sizeBytes })
        }
        val sessionId = installer.createSession(params)
        try {
            installer.openSession(sessionId).use { session ->
                backup.apks.forEach { apk ->
                    require(apk.file.isFile && apk.file.length() == apk.sizeBytes) {
                        "ملف الاستعادة غير موجود أو تغير حجمه: ${apk.name}"
                    }
                    session.openWrite(apk.name.substringAfterLast('/'), 0, apk.sizeBytes).use { output ->
                        apk.file.inputStream().use { input -> input.copyTo(output) }
                        session.fsync(output)
                    }
                }
                // Keep the mutable PackageInstaller callback on a private activity.
                // MainActivity is exported for the launcher and must never forward
                // an arbitrary EXTRA_INTENT supplied by another application.
                val callback = Intent(context, ApkRestoreStatusActivity::class.java)
                    .setAction(ACTION_INSTALL_STATUS)
                val pending = PendingIntent.getActivity(
                    context, sessionId, callback,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                )
                session.commit(pending.intentSender)
            }
        } catch (error: Exception) {
            runCatching { installer.abandonSession(sessionId) }
            throw error
        }
    }

    const val ACTION_INSTALL_STATUS = "com.omega.arabify.ACTION_INSTALL_STATUS"
}

/**
 * PackageInstaller returns a confirmation Intent through the mutable PendingIntent.
 * This callback is deliberately non-exported; additionally, only an explicit
 * component belonging to a system installer package is allowed to be launched.
 */
internal class ApkRestoreStatusActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val callback = intent
        if (callback.action != ApkRestoreInstaller.ACTION_INSTALL_STATUS) {
            finish()
            return
        }

        when (callback.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> launchSystemConfirmation(callback)
            PackageInstaller.STATUS_SUCCESS -> {
                Toast.makeText(this, "تم تثبيت النسخة الاحتياطية بنجاح.", Toast.LENGTH_LONG).show()
                finish()
            }
            else -> {
                val message = callback.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                    ?: "تعذر تثبيت النسخة الاحتياطية."
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    private fun launchSystemConfirmation(callback: Intent) {
        @Suppress("DEPRECATION")
        val confirmation = callback.getParcelableExtra(Intent.EXTRA_INTENT) as? Intent
        val component = confirmation?.component
        val installerFlags = component?.packageName?.let { packageName ->
            runCatching { packageManager.getApplicationInfo(packageName, 0).flags }.getOrNull()
        }
        if (confirmation == null || component == null || installerFlags == null ||
            !isTrustedInstallerConfirmation(component.packageName, installerFlags)
        ) {
            Toast.makeText(this, "رفض فتح شاشة تثبيت غير موثوقة.", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        try {
            startActivity(confirmation)
        } catch (_: Exception) {
            Toast.makeText(this, "تعذر فتح تأكيد تثبيت النسخة الاحتياطية.", Toast.LENGTH_LONG).show()
        } finally {
            finish()
        }
    }
}

internal fun isTrustedInstallerConfirmation(componentPackageName: String?, applicationFlags: Int): Boolean {
    if (componentPackageName.isNullOrBlank()) return false
    val systemFlags = ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
    return applicationFlags and systemFlags != 0
}
