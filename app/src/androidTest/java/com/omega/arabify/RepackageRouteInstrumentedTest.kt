package com.omega.arabify

import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device evidence for the repackaging route (Omega's decision, 2026-11-27):
 * the app hands the re-signed Arabized APK to the NORMAL Android installer,
 * and when the original is still installed (different signature) the app
 * asks the user to uninstall it first.
 *
 * Runs in the Unciv slot job while the ORIGINAL Unciv (F-Droid signed) is
 * still installed. What this proves on-device:
 *  - signature-conflict detection is true against the original signature and
 *    false against an ARABIFY-signed copy of the same game;
 *  - the uninstall intent resolves to the system uninstall dialog;
 *  - a full PackageInstaller session (create, write, fsync) works, then is
 *    abandoned without commit.
 *
 * What this deliberately does NOT do: commit the session. Committing would
 * surface the system's install-confirmation UI, and tapping that dialog is
 * the one step no instrumented test can perform; it is the user's own
 * approval, by design.
 */
@RunWith(AndroidJUnit4::class)
class RepackageRouteInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val packageName = "com.unciv.app"

    @Test
    fun installedOriginalBlocksInstall_signatureMatrix() {
        val pm = context.packageManager
        val original = File(pm.getApplicationInfo(packageName, 0).sourceDir)
        assertTrue("original Unciv APK must be readable from its installed sourceDir", original.isFile && original.length() > 0)

        // Not blocked against the original file itself: same signer as the
        // installed app (also covers a future re-install of our signed copy).
        assertFalse(
            "same-signature copy must not be flagged as blocking",
            RepackageInstallHelper.installedOriginalBlocksInstall(context, packageName, original),
        )

        // Re-sign with ARABIFY's key: now the signature differs and the
        // helper must flag the conflict (Android would refuse the update).
        val resigned = File(context.cacheDir, "unciv-repackage-resigned.apk")
        resigned.delete()
        ApkSigningService.signAndVerify(context, original, resigned)
        try {
            assertTrue(
                "ARABIFY-signed copy must be flagged as blocked while the original is installed",
                RepackageInstallHelper.installedOriginalBlocksInstall(context, packageName, resigned),
            )
        } finally {
            resigned.delete()
        }

        // A package that is not installed at all is never "blocked".
        assertFalse(
            "a package that is not installed cannot block",
            RepackageInstallHelper.installedOriginalBlocksInstall(
                context, "com.omega.arabify.definitely.not.installed", original),
        )
    }

    @Test
    fun uninstallIntentResolvesToSystemDialog() {
        val intent = RepackageInstallHelper.uninstallIntent(packageName)
        assertEquals(Intent.ACTION_DELETE, intent.action)
        assertEquals("package:$packageName", intent.dataString)
        val resolved = intent.resolveActivity(context.packageManager)
        assertNotNull("no activity handles the system uninstall dialog", resolved)
    }

    @Test
    fun installSessionCreateWriteAbandon() {
        val pm = context.packageManager
        val original = File(pm.getApplicationInfo(packageName, 0).sourceDir)
        val resigned = File(context.cacheDir, "unciv-repackage-resigned.apk")
        resigned.delete()
        ApkSigningService.signAndVerify(context, original, resigned)
        val installer = pm.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(packageName)
            setInstallReason(PackageManager.INSTALL_REASON_USER)
            setSize(resigned.length())
        }
        val sessionId = installer.createSession(params)
        assertTrue("PackageInstaller returned an invalid session id", sessionId > 0)
        try {
            installer.openSession(sessionId).use { session ->
                session.openWrite("arabified-$packageName.apk", 0, resigned.length()).use { output ->
                    resigned.inputStream().use { input -> input.copyTo(output) }
                    session.fsync(output)
                }
                // Abandon without commit: writing the whole APK into a live
                // session is proven; the commit's system-confirmation dialog
                // is the user-approval step and stays untested on purpose.
            }
        } finally {
            installer.abandonSession(sessionId)
            resigned.delete()
        }
    }
}
