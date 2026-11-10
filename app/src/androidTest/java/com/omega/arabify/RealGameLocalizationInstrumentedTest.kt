package com.omega.arabify

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.android.apksig.ApkVerifier
import com.reandroid.apk.ApkModule
import com.reandroid.arsc.value.ResConfig
import kotlinx.coroutines.runBlocking
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
    fun localizesInstalledMinesweeperIntoArabicResourcesAndSignsOutput() = runBlocking {
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
            // Model download happens before translation; the localizer itself
            // then uses the on-device translator for every selected string.
            translator.prepareForOfflineUse()
            val report = AndroidResourceLocalizer.localizeEnglishStrings(
                inputApk = installedGameApk,
                outputApk = output,
                unsignedApk = unsigned,
                translator = translator,
                context = context
            )

            assertTrue("No Android strings were discovered in the game APK", report.discoveredStrings > 0)
            assertTrue("No game strings were translated", report.translatedStrings > 0)
            assertTrue("The translated APK was not created", output.isFile && output.length() > 0L)

            val module = ApkModule.loadApkFile(output)
            val arabicResourceCount = try {
                val table = module.getTableBlock() ?: error("Translated APK has no resources.arsc")
                val packageBlock = table.firstOrNull { it.getName() == report.packageName }
                    ?: error("Translated APK package block is missing")
                val stringType = packageBlock.getSpecTypePair("string")
                    ?: error("Translated APK has no string resources")
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
        } finally {
            translator.close()
            output.delete()
            unsigned.delete()
        }
    }
}
