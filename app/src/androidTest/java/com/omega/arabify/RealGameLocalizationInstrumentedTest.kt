package com.omega.arabify

import androidx.test.ext.junit.runners.AndroidJUnit4
import android.util.Log
import android.content.ContentValues
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import com.android.apksig.ApkVerifier
import com.reandroid.apk.ApkModule
import com.reandroid.arsc.value.ResConfig
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * End-to-end package test against the separately installed, open-source
 * Minesweeper game supplied to the test device as an additional APK.
 */
@RunWith(AndroidJUnit4::class)
class RealGameLocalizationInstrumentedTest {
    private fun runShell(instrumentation: android.app.Instrumentation, command: String) {
        val descriptor = instrumentation.uiAutomation.executeShellCommand(command)
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
    }

    private fun hasValidatedInternet(context: android.content.Context): Boolean {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val network = connectivity.activeNetwork ?: return false
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    @Test
    fun localizesInstalledMinesweeperIntoArabicResourcesAndSignsOutput() {
        runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val gamePackage = "com.joeld.minesweeper"
        val gameInfo = context.packageManager.getApplicationInfo(gamePackage, 0)
        val installedGameApk = File(gameInfo.sourceDir)
        assertTrue("The real test game APK is not readable", installedGameApk.isFile && installedGameApk.canRead())

        val output = File(context.cacheDir, "minesweeper-arabify-ar.apk")
        val unsigned = File(context.cacheDir, "minesweeper-arabify-unsigned.apk")
        output.delete()
        unsigned.delete()
        val translator = OfflineArabicTranslator(context)
        try {
            // Firebase/CI test devices may expose an internet-capable transport
            // without reporting it as Wi-Fi. The app's normal UI keeps Wi-Fi-only
            // model downloads; this test permits the available test-device network.
            Log.i("RealGameLocalization", "Downloading English-Arabic ML Kit model")
            val report = withTimeout(12 * 60 * 1000L) {
                translator.prepareForOfflineUse(requireWifi = false)
                Log.i("RealGameLocalization", "Model ready; disabling network before translation")
                try {
                    runShell(instrumentation, "svc wifi disable")
                    runShell(instrumentation, "svc data disable")
                    Thread.sleep(2000)
                    assertTrue(
                        "Validated internet is still available; offline translation was not isolated",
                        !hasValidatedInternet(context)
                    )
                    Log.i("RealGameLocalization", "Network is offline; translating installed Minesweeper APK")
                    AndroidResourceLocalizer.localizeEnglishStrings(
                        inputApk = installedGameApk,
                        outputApk = output,
                        unsignedApk = unsigned,
                        translator = translator,
                        context = context
                    )
                } finally {
                    // Restore emulator connectivity even when localization or an assertion fails.
                    runShell(instrumentation, "svc data enable")
                    runShell(instrumentation, "svc wifi enable")
                }
            }
            Log.i("RealGameLocalization", "Localized ${report.translatedStrings}/${report.discoveredStrings} strings")

            assertTrue("No Android strings were discovered in the game APK", report.discoveredStrings > 0)
            assertTrue("No game strings were translated", report.translatedStrings > 0)
            assertTrue("The translated APK was not created", output.isFile && output.length() > 0L)

            val module = ApkModule.loadApkFile(output)
            var newGameArabic: String? = null
            var createModeArabic: String? = null
            var noGuessShortArabic: String? = null
            val arabicResourceCount = try {
                val table = module.getTableBlock() ?: error("Translated APK has no resources.arsc")
                val packageBlock = table.firstOrNull { it.getName() == report.packageName }
                    ?: error("Translated APK package block is missing")
                val stringType = packageBlock.getSpecTypePair("string")
                    ?: error("Translated APK has no string resources")
                val arabicConfig = ResConfig.parse("-ar")
                newGameArabic = packageBlock.getEntry(arabicConfig, "string", "new_game")?.getValueAsString()
                createModeArabic = packageBlock.getEntry(arabicConfig, "string", "create_mode")?.getValueAsString()
                noGuessShortArabic = packageBlock.getEntry(arabicConfig, "string", "no_guess_short")?.getValueAsString()
                assertEquals("The common Create button must use the reviewed UI term", "إنشاء", createModeArabic)
                assertEquals("The compact no-guess badge must be localized too", "دون تخمين", noGuessShortArabic)
                assertTrue(
                    "The known 'new_game' string was not translated into Arabic: $newGameArabic",
                    !newGameArabic.isNullOrBlank() &&
                        newGameArabic.any { it.code in 0x0600..0x06FF } &&
                        (newGameArabic.contains("لعبة") || newGameArabic.contains("جديد"))
                )
                Log.i("RealGameLocalization", "new_game Arabic value: $newGameArabic")
                var count = 0
                val arabicValues = mutableListOf<String>()
                for (block in stringType.getTypeBlocks()) {
                    if (block.getResConfig().getQualifiers().contains("ar")) {
                        for (entry in block.listEntries(true)) {
                            entry.getValueAsString()?.let(arabicValues::add)
                            count++
                        }
                    }
                }
                assertTrue("Easy difficulty was not localized", arabicValues.contains("سهل"))
                assertTrue("Medium difficulty was not localized", arabicValues.contains("متوسط"))
                assertTrue("Hard difficulty was not localized", arabicValues.contains("صعب"))
                count
            } finally {
                module.close()
            }
            assertTrue("No Arabic-qualified string resources were written", arabicResourceCount > 0)
            assertTrue(
                "Arabic resource count is lower than the reported translated string count",
                arabicResourceCount >= report.translatedStrings
            )

            val signature = ApkVerifier.Builder(output).build().verify()
            assertTrue("Generated game APK signature is invalid: ${signature.errors}", signature.isVerified)

            // Preserve the generated APK outside cache so CI and Test Lab can
            // pull the exact artifact and perform a separate install/launch check.
            // Keep CI evidence in the app's private files directory. Android 15
            // may deny adb shell direct traversal of /sdcard/Android/data even
            // though the app itself can write there; CI retrieves this via run-as.
            val evidenceDir = File(context.filesDir, "real-game-test")
            check(evidenceDir.mkdirs() || evidenceDir.isDirectory)
            val preservedApk = File(evidenceDir, "minesweeper-arabic.apk")
            output.copyTo(preservedApk, overwrite = true)
            val reportFile = File(evidenceDir, "report.txt")
            reportFile.writeText(
                "gamePackage=${report.packageName}\n" +
                    "discoveredStrings=${report.discoveredStrings}\n" +
                    "translatedStrings=${report.translatedStrings}\n" +
                    "arabicStringResources=$arabicResourceCount\n" +
                    "new_gameArabic=$newGameArabic\n" +
                    "create_modeArabic=$createModeArabic\n" +
                    "no_guess_shortArabic=$noGuessShortArabic\n" +
                    "signatureVerified=${signature.isVerified}\n"
            )

            // Export evidence through MediaStore Downloads. The connected-test
            // runner may uninstall the target APK after instrumentation ends,
            // making run-as unavailable to the subsequent CI shell script.
            fun publishToDownloads(source: File, displayName: String, mimeType: String) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/ARABIFY")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: error("Could not create Downloads evidence entry: $displayName")
                try {
                    context.contentResolver.openOutputStream(uri)?.use { stream ->
                        source.inputStream().use { input -> input.copyTo(stream) }
                    } ?: error("Could not open Downloads evidence output: $displayName")
                    val ready = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                    context.contentResolver.update(uri, ready, null, null)
                } catch (error: Throwable) {
                    context.contentResolver.delete(uri, null, null)
                    throw error
                }
            }
            publishToDownloads(preservedApk, "minesweeper-arabic.apk", "application/vnd.android.package-archive")
            publishToDownloads(reportFile, "report.txt", "text/plain")
            Log.i("RealGameLocalization", "Published signed translated APK and report to Downloads/ARABIFY")
        } finally {
            translator.close()
            output.delete()
            unsigned.delete()
        }
        }
    }
}
