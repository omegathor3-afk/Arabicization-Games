package com.omega.arabify

import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.ContentValues
import android.provider.MediaStore
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.android.apksig.ApkVerifier
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * End-to-end test of the translation-folder locale-slot tier against the
 * real Unciv game (F-Droid build) installed on the emulator by the CI
 * script. Unciv reads jsons/translations/<settings.language>.properties, and
 * its registry names Arabic بالعربية, so the slot file must be
 * العربية.properties (its language picker hides Arabic entirely:
 * LocaleCode.Arabic is flagged unused upstream - activation happens via the
 * GameSettings.json the CI script seeds, not the picker).
 *
 * Translation uses a deterministic pseudo-Arabic stand-in (see
 * MindustryLocaleSlotInstrumentedTest for the rationale).
 */
@RunWith(AndroidJUnit4::class)
class UncivLocaleSlotInstrumentedTest {

    private fun digest(zip: ZipFile, entryName: String): ByteArray {
        val entry = zip.getEntry(entryName) ?: error("ZIP entry missing: $entryName")
        val digest = MessageDigest.getInstance("SHA-256")
        zip.getInputStream(entry).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest()
    }

    @Test
    fun addsArabicTranslationFileToRealUncivAndSignsOutput() {
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val gamePackage = "com.unciv.app"
            val gameInfo = context.packageManager.getApplicationInfo(gamePackage, 0)
            val installedGameApk = File(gameInfo.sourceDir)
            assertTrue("The real Unciv APK is not readable", installedGameApk.isFile && installedGameApk.canRead())

            val unsigned = File(context.cacheDir, "unciv-slot-unsigned.apk")
            val signed = File(context.cacheDir, "unciv-slot-ar.apk")
            unsigned.delete()
            signed.delete()
            val mode = InstrumentationRegistry.getArguments().getString("translationMode", "pseudo")
            val translator = if (mode == "real") OfflineArabicTranslator(context) else null
            try {
                if (translator != null) {
                    Log.i("UncivSlot", "translationMode=real: downloading the ML Kit English-Arabic model")
                    translator.prepareForOfflineUse(requireWifi = false)
                    assertTrue(
                        "ML Kit Arabic model is unavailable on this emulator; cannot produce a real-translation build",
                        translator.isArabicModelDownloaded()
                    )
                }
                // Font tier + Presentation Forms-B shaping: on-device evidence
                // (2026-11-25) shows Unciv renders slot text reversed without it.
                val fonts = LocaleSlotLocalizer.FontInjection { f ->
                    context.assets.open(f.assetPath).use { it.readBytes() }
                }
                val report = LocaleSlotLocalizer.addArabicLocaleSlotWithFont(installedGameApk, unsigned, fonts) { text ->
                    if (translator != null) {
                        if (text.isBlank()) text else translator.translateEnglishToArabic(text)
                    } else {
                        val trimmed = text.trim()
                        if (trimmed.length >= 3) "عر${trimmed.length}" else text
                    }
                }
                Log.i("UncivSlot", "font=${report.fontReplaced} reason=${report.fontReason}")
                Log.i("UncivSlot", "slot=${report.addedEntry} translated=${report.stringsTranslated} skipped=${report.stringsSkipped}")
                assertEquals("assets/jsons/translations/العربية.properties", report.addedEntry)
                assertTrue("Too few Unciv strings translated: ${report.stringsTranslated}", report.stringsTranslated > 1000)
                assertTrue("Unsigned localized APK missing", unsigned.isFile && unsigned.length() > 0L)

                ZipFile(installedGameApk).use { original ->
                    ZipFile(unsigned).use { localized ->
                        val slotEntry = localized.getEntry(report.addedEntry) ?: error("Arabic slot entry missing")
                        val slotText = localized.getInputStream(slotEntry).use { it.readBytes() }.toString(Charsets.UTF_8)
                        val baseText = original.getInputStream(original.getEntry(report.baseEntry)).use { it.readBytes() }.toString(Charsets.UTF_8)
                        fun keysOf(text: String): Set<String> = text.split('\n')
                            .mapNotNull { line ->
                                val index = line.indexOf('=')
                                if (index <= 0) null else line.substring(0, index).trim()
                            }
                            .toSet()
                        assertEquals("Template keys were not preserved in the Arabic file", keysOf(baseText), keysOf(slotText))
                        assertTrue("Arabic directives missing", slotText.contains("StartWithCapitalLetter = false"))
                        // Values are pre-shaped to visual-order Presentation Forms-B for
                        // the bidi-less renderer, so Arabic shows up in the FE70-FEFF
                        // block rather than (or alongside) the base 0600-06FF block.
                        assertTrue("Slot contains no Arabic text", slotText.any { it.code in 0x0600..0x06FF || it.code in 0xFB50..0xFEFF })
                        val originalNames = original.entries().asSequence().map { it.name }.toList()
                        var compared = 0
                        for (name in originalNames) {
                            if (AndroidAssetLocalizer.isOldSignature(name)) continue
                            val originalEntry = original.getEntry(name)
                            if (originalEntry.isDirectory) continue
                            assertTrue(
                                "Existing entry bytes changed: $name",
                                digest(original, name).contentEquals(digest(localized, name))
                            )
                            compared++
                        }
                        assertTrue("Too few entries compared: $compared", compared > 100)
                    }
                }

                ApkSigningService.signAndVerify(context, unsigned, signed)
                val verification = ApkVerifier.Builder(signed).build().verify()
                assertTrue("Signed Unciv APK failed verification: ${verification.errors}", verification.isVerified)

                val evidenceDir = File(context.filesDir, "unciv-slot-test")
                check(evidenceDir.mkdirs() || evidenceDir.isDirectory)
                val preservedApk = File(evidenceDir, "unciv-arabic.apk")
                signed.copyTo(preservedApk, overwrite = true)
                val reportFile = File(evidenceDir, "unciv-report.txt")
                reportFile.writeText(
                    "gamePackage=$gamePackage\n" +
                        "slotEntry=${report.addedEntry}\n" +
                        "translatedStrings=${report.stringsTranslated}\n" +
                        "skippedStrings=${report.stringsSkipped}\n" +
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
                publishToDownloads(preservedApk, "unciv-arabic.apk", "application/vnd.android.package-archive")
                publishToDownloads(reportFile, "unciv-report.txt", "text/plain")
                Log.i("UncivSlot", "Published signed localized Unciv APK and report to Downloads/ARABIFY")
            } finally {
                unsigned.delete()
                signed.delete()
            }
        }
    }
}
