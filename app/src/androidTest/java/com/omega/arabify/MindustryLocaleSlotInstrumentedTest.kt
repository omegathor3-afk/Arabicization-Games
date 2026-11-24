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
 * End-to-end test of the libGDX locale-slot tier against the real Mindustry
 * game (F-Droid build) installed on the emulator by the CI script.
 *
 * Translation uses a deterministic pseudo-Arabic stand-in so the test is
 * reproducible: ML Kit offline translation of Android resources is covered by
 * RealGameLocalizationInstrumentedTest, while this test proves the slot
 * mechanics on a real game - slot addition, byte-identical preservation of
 * every other entry, signing, install and Arabic rendering under ar-EG.
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
            try {
                val report = LocaleSlotLocalizer.addArabicLocaleSlot(installedGameApk, unsigned) { text ->
                    val trimmed = text.trim()
                    if (trimmed.length >= 3) "عر${trimmed.length}" else text
                }
                Log.i("MindustrySlot", "slot=${report.addedEntry} translated=${report.stringsTranslated} skipped=${report.stringsSkipped}")
                assertEquals("assets/bundles/bundle_ar.properties", report.addedEntry)
                assertTrue("Too few Mindustry strings translated: ${report.stringsTranslated}", report.stringsTranslated > 1000)
                assertTrue("Unsigned localized APK missing", unsigned.isFile && unsigned.length() > 0L)

                ZipFile(installedGameApk).use { original ->
                    ZipFile(unsigned).use { localized ->
                        val slotEntry = localized.getEntry(report.addedEntry) ?: error("Arabic slot entry missing")
                        val slotText = localized.getInputStream(slotEntry).use { it.readBytes() }.toString(Charsets.UTF_8)
                        assertTrue("Bundle keys were not preserved in the slot", slotText.contains("menu.new"))
                        assertTrue("Slot contains no Arabic text", slotText.any { it.code in 0x0600..0x06FF })
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
                assertTrue("Signed Mindustry APK failed verification: ${verification.errors}", verification.isVerified)

                // Publish evidence for the CI script: it installs this exact APK,
                // switches the emulator to ar-EG and checks Arabic on screen.
                val evidenceDir = File(context.filesDir, "mindustry-slot-test")
                check(evidenceDir.mkdirs() || evidenceDir.isDirectory)
                val preservedApk = File(evidenceDir, "mindustry-arabic.apk")
                signed.copyTo(preservedApk, overwrite = true)
                val reportFile = File(evidenceDir, "mindustry-report.txt")
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
                publishToDownloads(preservedApk, "mindustry-arabic.apk", "application/vnd.android.package-archive")
                publishToDownloads(reportFile, "mindustry-report.txt", "text/plain")
                Log.i("MindustrySlot", "Published signed localized Mindustry APK and report to Downloads/ARABIFY")
            } finally {
                unsigned.delete()
                signed.delete()
            }
        }
    }
}
