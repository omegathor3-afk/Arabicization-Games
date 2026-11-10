package com.omega.arabify

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
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
                val callback = Intent(context, MainActivity::class.java)
                    .setAction(ACTION_INSTALL_STATUS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
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
