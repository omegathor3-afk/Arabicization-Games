package com.omega.arabify

import androidx.test.ext.junit.runners.AndroidJUnit4
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.android.apksig.ApkVerifier
import com.reandroid.apk.ApkModule
import com.reandroid.arsc.value.ResConfig
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
                Log.i("RealGameLocalization", "Model ready; translating installed Minesweeper APK")
                AndroidResourceLocalizer.localizeEnglishStrings(
                    inputApk = installedGameApk,
                    outputApk = output,
                    unsignedApk = unsigned,
                    translator = translator,
                    context = context
                )
            }
            Log.i("RealGameLocalization", "Localized ${report.translatedStrings}/${report.discoveredStrings} strings")

            assertTrue("No Android strings were discovered in the game APK", report.discoveredStrings > 0)
            assertTrue("No game strings were translated", report.translatedStrings > 0)
            assertTrue("The translated APK was not created", output.isFile && output.length() > 0L)

            val module = ApkModule.loadApkFile(output)
            var newGameArabic: String? = null
            val arabicResourceCount = try {
                val table = module.getTableBlock() ?: error("Translated APK has no resources.arsc")
                val packageBlock = table.firstOrNull { it.getName() == report.packageName }
                    ?: error("Translated APK package block is missing")
                val stringType = packageBlock.getSpecTypePair("string")
                    ?: error("Translated APK has no string resources")
                val arabicConfig = ResConfig.parse("-ar")
                newGameArabic = packageBlock.getEntry(arabicConfig, "string", "new_game")?.getValueAsString()
                assertTrue(
                    "The known 'new_game' string was not translated into Arabic: $newGameArabic",
                    !newGameArabic.isNullOrBlank() &&
                        newGameArabic.any { it.code in 0x0600..0x06FF } &&
                        (newGameArabic.contains("لعبة") || newGameArabic.contains("جديد"))
                )
                Log.i("RealGameLocalization", "new_game Arabic value: $newGameArabic")
                var count = 0
                val blocks = stringType.getTypeBlocks()
                while (blocks.hasNext()) {
                    val block = blocks.next()
                    if (block.getResConfig().getQualifiers().contains("ar")) {
                        count += block.listEntries(true).count()
                    }
                }
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
            val evidenceBase = context.getExternalFilesDir(null)
                ?: error("External app-specific storage is unavailable on the test device")
            val evidenceDir = File(evidenceBase, "real-game-test")
            evidenceDir.mkdirs()
            val preservedApk = File(evidenceDir, "minesweeper-arabic.apk")
            output.copyTo(preservedApk, overwrite = true)
            File(evidenceDir, "report.txt").writeText(
                "gamePackage=${report.packageName}\n" +
                    "discoveredStrings=${report.discoveredStrings}\n" +
                    "translatedStrings=${report.translatedStrings}\n" +
                    "arabicStringResources=$arabicResourceCount\n" +
                    "new_gameArabic=$newGameArabic\n" +
                    "signatureVerified=${signature.isVerified}\n"
            )
            Log.i("RealGameLocalization", "Preserved translated APK at ${preservedApk.absolutePath}")
        } finally {
            translator.close()
            output.delete()
            unsigned.delete()
        }
        }
    }
}
