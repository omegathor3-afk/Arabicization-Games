package com.omega.arabify

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import java.io.File

/**
 * The repackaging route (Omega's decision, 2026-11-27): the app Arabizes a
 * game APK, re-signs it with ARABIFY's key, and hands it to the NORMAL
 * Android package installer. Because the Arabized copy is signed with a
 * different key than the original, Android refuses it as an update; the
 * original must be uninstalled first. This helper detects that conflict,
 * builds the system uninstall dialog intent, and commits the install
 * session. Every destructive step is confirmed by the user in a system
 * dialog — the app never uninstalls or installs silently.
 */
internal object RepackageInstallHelper {

    /**
     * True when [packageName] is currently installed AND its signing
     * certificate differs from [signedApk]'s, so the system installer would
     * reject the Arabized copy until the original is removed. False when the
     * game is not installed at all, or when signatures already match
     * (re-installing a copy we signed earlier).
     */
    fun installedOriginalBlocksInstall(context: Context, packageName: String, signedApk: File): Boolean {
        val pm = context.packageManager
        val installed = try {
            pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        } catch (notInstalled: PackageManager.NameNotFoundException) {
            return false
        }
        val archive = pm.getPackageArchiveInfo(signedApk.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
            ?: return false
        return !sameSigners(installed, archive)
    }

    private fun sameSigners(installed: PackageInfo, archive: PackageInfo): Boolean {
        val installedSigners = installed.signingInfo?.apkContentsSigners?.map { it.toByteArray().toList() }?.toSet()
        val archiveSigners = archive.signingInfo?.apkContentsSigners?.map { it.toByteArray().toList() }?.toSet()
        if (installedSigners == null || archiveSigners == null) return false
        return installedSigners == archiveSigners
    }

    /** System dialog asking the user to confirm uninstalling the original game. */
    fun uninstallIntent(packageName: String): Intent =
        Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName"))

    /**
     * Sends the signed Arabized APK to the system package installer. The
     * status callback lands on a private activity that only launches the
     * system's own confirmation screen (same hardening as the restore
     * installer: mutable PendingIntent never crosses into an exported
     * component, and only a system package's confirmation is launched).
     */
    fun install(context: Context, apk: File, packageName: String) {
        require(apk.isFile && apk.length() > 0L) { "ملف APK المعرب غير موجود." }
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(packageName)
            setInstallReason(PackageManager.INSTALL_REASON_USER)
            setSize(apk.length())
        }
        val sessionId = installer.createSession(params)
        try {
            installer.openSession(sessionId).use { session ->
                session.openWrite("arabified-$packageName.apk", 0, apk.length()).use { output ->
                    apk.inputStream().use { input -> input.copyTo(output) }
                    session.fsync(output)
                }
                val callback = Intent(context, RepackageInstallStatusActivity::class.java)
                    .setAction(ACTION_REPACKAGE_INSTALL_STATUS)
                val pending = PendingIntent.getActivity(
                    context, sessionId, callback,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
                session.commit(pending.intentSender)
            }
        } catch (error: Exception) {
            runCatching { installer.abandonSession(sessionId) }
            throw error
        }
    }

    const val ACTION_REPACKAGE_INSTALL_STATUS = "com.omega.arabify.ACTION_REPACKAGE_INSTALL_STATUS"
}

/**
 * Receives the PackageInstaller status for an Arabized-game install.
 * Non-exported by contract; launches only a system package's confirmation
 * screen. Copy is for the repackaging route (not backup restore).
 */
internal class RepackageInstallStatusActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val callback = intent
        if (callback.action != RepackageInstallHelper.ACTION_REPACKAGE_INSTALL_STATUS) {
            finish()
            return
        }

        when (callback.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> launchSystemConfirmation(callback)
            PackageInstaller.STATUS_SUCCESS -> {
                Toast.makeText(this, "تم تثبيت النسخة المعربة بنجاح.", Toast.LENGTH_LONG).show()
                finish()
            }
            else -> {
                val message = callback.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                    ?: "تعذر تثبيت النسخة المعربة."
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
            Toast.makeText(this, "تعذر فتح تأكيد تثبيت النسخة المعربة.", Toast.LENGTH_LONG).show()
        } finally {
            finish()
        }
    }
}
