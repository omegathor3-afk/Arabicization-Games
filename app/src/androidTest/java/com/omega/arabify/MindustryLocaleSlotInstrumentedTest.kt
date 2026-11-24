package com.omega.arabify

import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.ContentValues
import android.provider.MediaStore
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.android.apksig.ApkVerifier
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * End-to-end test of the libGDX locale-slot tier against the real Mindustry
 * game (F-Droid build) installed on the emulator by the CI script.
 *
 * Translation mode comes from the instrumentation argument "translationMode":
 * "pseudo" (default) uses a deterministic pseudo-Arabic stand-in so the
 * mechanics test is reproducible; "real" translates every string through the
 * production OfflineArabicTranslator (ML Kit English-Arabic model downloaded
 * on the emulator) so CI can screenshot the real translation quality.
 * This instrumentation test proves APK-level mechanics only:
 * locale-slot creation, byte-identical preservation of unrelated entries
 * including the recognized icon font, and signature verification. It does not launch Mindustry
 * or prove that Arabic glyphs render on screen; the CI script separately
 * launches the exact output and only captures a screenshot while the game is
 * confirmed as the resumed foreground activity. Visual glyph verification
 * remains a separate human review gate because libGDX renders through OpenGL.
 */
@RunWith(AndroidJUnit4::class)
class MindustryLocaleSlotInstrumentedTest {

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
    fun addsArabicLocaleSlotToRealMindustryAndSignsOutput() {
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val gamePackage = "io.anuke.mindustry"
            val gameInfo = context.packageManager.getApplicationInfo(gamePackage, 0)
            val installedGameApk = File(gameInfo.sourceDir)
            assertTrue("The real Mindustry APK is not readable", installedGameApk.isFile && installedGameApk.canRead())

            val unsigned = File(context.cacheDir, "mindustry-slot-unsigned.apk")
            val signed = File(context.cacheDir, "mindustry-slot-ar.apk")
            unsigned.delete()
            signed.delete()
            val mode = InstrumentationRegistry.getArguments().getString("translationMode", "pseudo")
            val translator = if (mode == "real") OfflineArabicTranslator(context) else null
            try {
                if (translator != null) {
                    Log.i("MindustrySlot", "translationMode=real: downloading the ML Kit English-Arabic model")
                    translator.prepareForOfflineUse(requireWifi = false)
                    assertTrue(
                        "ML Kit Arabic model is unavailable on this emulator; cannot produce a real-translation build",
                        translator.isArabicModelDownloaded()
                    )
                }
                val fonts = LocaleSlotLocalizer.FontInjection { f ->
                    context.assets.open(f.assetPath).use { it.readBytes() }
                }
                val report = LocaleSlotLocalizer.addArabicLocaleSlotWithFont(installedGameApk, unsigned, fonts) { text ->
                    if (translator != null) {
                        if (text.isBlank()) text else translator.translateEnglishToArabic(text)
                    } else {
                        val trimmed = text.trim()
                        if (trimmed.length >= 3) "العربية ${trimmed.length}" else text
                    }
                }
                Log.i("MindustrySlot", "font=${report.fontReplaced} reason=${report.fontReason}")
                assertEquals("Fontello icon font must be merged in place, never replaced",
                    "assets/fonts/font.woff", report.fontReplaced)
                assertTrue("Icon-font merge reason missing", report.fontReason?.contains("دمج") == true)
                Log.i("MindustrySlot", "slot=${report.addedEntry} translated=${report.stringsTranslated} skipped=${report.stringsSkipped}")
                assertEquals("assets/bundles/bundle_ar.properties", report.addedEntry)
                assertTrue("Too few Mindustry strings translated: ${report.stringsTranslated}", report.stringsTranslated > 1000)
                assertTrue("Unsigned localized APK missing", unsigned.isFile && unsigned.length() > 0L)

                ZipFile(installedGameApk).use { original ->
                    ZipFile(unsigned).use { localized ->
                        val slotEntry = localized.getEntry(report.addedEntry) ?: error("Arabic slot entry missing")
                        val slotText = localized.getInputStream(slotEntry).use { it.readBytes() }.toString(Charsets.UTF_8)
                        val baseText = original.getInputStream(original.getEntry(report.baseEntry)).use { it.readBytes() }.toString(Charsets.UTF_8)
                        fun bundleKeys(text: String): Set<String> = text.split('\n')
                            .map { it.trim() }
                            .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("!") }
                            .mapNotNull { line ->
                                val index = line.indexOfFirst { it == '=' || it == ':' }
                                if (index < 0) null else line.substring(0, index).trim()
                            }
                            .toSet()
                        assertEquals("Bundle keys were not preserved in the slot", bundleKeys(baseText), bundleKeys(slotText))
                        assertTrue("Merged-font slot must be pre-shaped to presentation forms",
                            slotText.any { it.code in 0xFE70..0xFEFF })
                        val fontEntry = localized.getEntry("assets/fonts/font.woff") ?: error("font entry missing")
                        val mergedFont = localized.getInputStream(fontEntry).use { it.readBytes() }
                        val mergedProfile = FontAnalyzer.analyze(mergedFont)
                        assertEquals("Merged font must keep the Fontello family", "fontello", mergedProfile.familyName)
                        assertTrue("Merged font must cover Arabic", mergedProfile.hasArabicCore)
                        assertTrue("Merged font must cover presentation forms B", mergedProfile.presentationFormsB >= 80)
                        assertTrue("Merged font must keep PUA icons", mergedProfile.supports(0xE800))
                        val originalNames = original.entries().asSequence().map { it.name }.toList()
                        var compared = 0
                        for (name in originalNames) {
                            if (AndroidAssetLocalizer.isOldSignature(name)) continue
                            val originalEntry = original.getEntry(name)
                            if (originalEntry.isDirectory) continue
                            if (name == report.fontReplaced) continue
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
                assertTrue("Signed Mindustry APK failed verification: ${verification.errors}", verification.isVerified)

                // Quote the translated menu labels in the report so translation
                // quality gets human review. Real mode re-translates the base
                // bundle's English values with the production translator (logical
                // order, readable); pseudo mode quotes the pre-shaped slot values.
                val menuKeys = listOf("play", "newgame", "loadgame", "joingame", "customgame", "campaign",
                    "tutorial", "editor", "maps", "mods", "database", "schematics", "settings", "credits", "load", "quit")
                fun bundleValues(zip: ZipFile, entryName: String): Map<String, String> {
                    val entry = zip.getEntry(entryName) ?: error("bundle entry missing: $entryName")
                    return zip.getInputStream(entry).use { it.readBytes() }.toString(Charsets.UTF_8)
                        .split('\n')
                        .mapNotNull { line ->
                            val index = line.indexOfFirst { it == '=' || it == ':' }
                            if (index < 0) null else line.substring(0, index).trim() to line.substring(index + 1).trim()
                        }
                        .toMap()
                }
                val menuTranslations = if (translator != null) {
                    val base = ZipFile(installedGameApk).use { bundleValues(it, report.baseEntry) }
                    menuKeys.mapNotNull { key ->
                        base[key]?.let { english ->
                            // Report what the pipeline actually ships: the reviewed
                            // glossary owns its terms, everything else is raw ML Kit.
                            val reviewed = ArabicUiGlossary.translateExact(english)
                            val arabic = reviewed ?: try {
                                translator.translateEnglishToArabic(english)
                            } catch (error: Exception) {
                                "<translation failed: ${error.message}>"
                            }
                            val marker = if (reviewed != null) " [glossary]" else ""
                            "$key | $english => $arabic$marker"
                        }
                    }
                } else {
                    val slot = ZipFile(unsigned).use { bundleValues(it, report.addedEntry) }
                    menuKeys.mapNotNull { key -> slot[key]?.let { "$key => $it" } }
                }

                // Publish evidence for the CI script: it installs this exact APK,
                // switches the emulator to ar-EG and checks Arabic on screen.
                val evidenceDir = File(context.filesDir, "mindustry-slot-test")
                check(evidenceDir.mkdirs() || evidenceDir.isDirectory)
                val suffix = if (mode == "real") "-real" else ""
                val preservedApk = File(evidenceDir, "mindustry-arabic$suffix.apk")
                signed.copyTo(preservedApk, overwrite = true)
                val reportFile = File(evidenceDir, "mindustry-report$suffix.txt")
                reportFile.writeText(
                    "gamePackage=$gamePackage\n" +
                        "translationMode=$mode\n" +
                        "slotEntry=${report.addedEntry}\n" +
                        "translatedStrings=${report.stringsTranslated}\n" +
                        "skippedStrings=${report.stringsSkipped}\n" +
                        "skipReasons=" + report.skipReasons.entries.sortedByDescending { it.value }
                            .joinToString(", ") { (reason, count) -> "$reason:$count" } + "\n" +
                        "fontReplaced=${report.fontReplaced}\n" +
                        "fontReason=${report.fontReason}\n" +
                        "signatureVerified=${verification.isVerified}\n" +
                        "menuTranslations:\n" + menuTranslations.joinToString("\n") + "\n"
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
                publishToDownloads(preservedApk, "mindustry-arabic$suffix.apk", "application/vnd.android.package-archive")
                publishToDownloads(reportFile, "mindustry-report$suffix.txt", "text/plain")
                Log.i("MindustrySlot", "Published signed localized Mindustry APK and report to Downloads/ARABIFY (mode=$mode)")
            } finally {
                translator?.close()
                unsigned.delete()
                signed.delete()
            }
        }
    }
}
