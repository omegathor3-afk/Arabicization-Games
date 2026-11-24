package com.omega.arabify

import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.ContentValues
import android.provider.MediaStore
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.android.apksig.ApkVerifier
import com.reandroid.apk.ApkModule
import com.reandroid.arsc.value.ResConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * End-to-end test of the Android resources tier against the real Andor's
 * Trail game (F-Droid build) installed on the emulator by the CI script.
 * The game is a story-driven RPG with ~22k strings in resources.arsc.
 *
 * Translation uses a deterministic pseudo-Arabic stand-in (see
 * MindustryLocaleSlotInstrumentedTest for the rationale); ML Kit offline
 * translation on this tier is covered by RealGameLocalizationInstrumentedTest.
 */
@RunWith(AndroidJUnit4::class)
class AndorsTrailResourceInstrumentedTest {

    @Test
    fun localizesRealAndorsTrailResourcesAndSignsOutput() {
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val gamePackage = "com.gpl.rpg.AndorsTrail"
            val gameInfo = context.packageManager.getApplicationInfo(gamePackage, 0)
            val installedGameApk = File(gameInfo.sourceDir)
            assertTrue("The real Andor's Trail APK is not readable", installedGameApk.isFile && installedGameApk.canRead())

            val output = File(context.cacheDir, "andorstrail-arabify-ar.apk")
            val unsigned = File(context.cacheDir, "andorstrail-arabify-unsigned.apk")
            output.delete()
            unsigned.delete()
            try {
                val report = AndroidResourceLocalizer.localizeEnglishStrings(
                    inputApk = installedGameApk,
                    outputApk = output,
                    unsignedApk = unsigned,
                    translate = { text -> if (text.trim().length >= 3) "عر${text.trim().length}" else text },
                    context = context
                )
                Log.i("AndorsTrail", "Localized ${report.translatedStrings}/${report.discoveredStrings} strings")
                assertEquals(gamePackage, report.packageName)
                // Andor's Trail already ships real Arabic values-ar entries
                // for ~612 of its ~671 own strings; the localizer must
                // RESPECT them (existingArabic) and only complete the rest.
                // Story text lives in res/raw XML - a separate future tier.
                assertTrue("Too few Andor's Trail strings discovered: ${report.discoveredStrings}", report.discoveredStrings > 300)
                assertTrue("Shipped Arabic entries were not respected: existing=${report.existingArabicStrings}", report.existingArabicStrings > 500)
                assertTrue("The translated APK was not created", output.isFile && output.length() > 0L)

                val module = ApkModule.loadApkFile(output)
                val arabicResourceCount = try {
                    val table = module.getTableBlock() ?: error("Translated APK has no resources.arsc")
                    val packageBlock = table.firstOrNull { it.getName() == report.packageName }
                        ?: error("Translated APK package block is missing")
                    val stringType = packageBlock.getSpecTypePair("string")
                        ?: error("Translated APK has no string resources")
                    val arabicConfig = ResConfig.parse("-ar")
                    var count = 0
                    var arabicValues = 0
                    for (block in stringType.getTypeBlocks()) {
                        if (block.getResConfig().getQualifiers().contains("ar")) {
                            for (entry in block.listEntries(true)) {
                                val value = entry.getValueAsString()
                                if (value != null) {
                                    count++
                                    if (value.any { it.code in 0x0600..0x06FF }) arabicValues++
                                }
                            }
                        }
                    }
                    assertTrue("Arabic-qualified values contain no Arabic script", arabicValues > 100)
                    count
                } finally {
                    module.close()
                }
                assertTrue("No Arabic-qualified string resources were written", arabicResourceCount > 100)

                val verification = ApkVerifier.Builder(output).build().verify()
                assertTrue("Signed Andor's Trail APK failed verification: ${verification.errors}", verification.isVerified)

                val evidenceDir = File(context.filesDir, "andorstrail-resource-test")
                check(evidenceDir.mkdirs() || evidenceDir.isDirectory)
                val preservedApk = File(evidenceDir, "andorstrail-arabic.apk")
                output.copyTo(preservedApk, overwrite = true)
                val reportFile = File(evidenceDir, "andorstrail-report.txt")
                reportFile.writeText(
                    "gamePackage=${report.packageName}\n" +
                        "discoveredStrings=${report.discoveredStrings}\n" +
                        "translatedStrings=${report.translatedStrings}\n" +
                        "skippedStrings=${report.skippedStrings}\n" +
                        "arabicStringResources=$arabicResourceCount\n" +
                        "signatureVerified=${verification.isVerified}\n"
                )

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
                publishToDownloads(preservedApk, "andorstrail-arabic.apk", "application/vnd.android.package-archive")
                publishToDownloads(reportFile, "andorstrail-report.txt", "text/plain")
                Log.i("AndorsTrail", "Published signed translated APK and report to Downloads/ARABIFY")
            } finally {
                output.delete()
                unsigned.delete()
            }
        }
    }
}
