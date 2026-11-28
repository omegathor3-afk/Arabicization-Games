package com.omega.arabify

import android.content.ContentValues
import android.provider.MediaStore
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
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
 * End-to-end test of the gettext PO tier against the REAL Pixel Wheels APK
 * (open-source, pinned F-Droid build com.agateau.tinywheels.android_33,
 * downloaded + SHA-256-checked by the CI workflow). Pixel Wheels ships
 * assets/po/messages.pot + assets/ui/languages.xml and NO Arabic-capable
 * font, so a real run must exercise the cross-font merge path that the
 * synthetic dispatch fixture honestly cannot.
 *
 * Translation mode comes from the instrumentation argument "translationMode":
 * "pseudo" (default) is deterministic; "real" translates through the
 * production OfflineArabicTranslator and runs the RoundTripVerifier audit
 * (quality evidence, never a rewrite). The pseudo leg additionally asserts
 * the planner dispatch selects the gettext wrapper on the real game.
 */
@RunWith(AndroidJUnit4::class)
class PixelWheelsGettextInstrumentedTest {

    @Test
    fun pixelWheelsGettextSlotBuildsSignsAndVerifies() {
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val gamePackage = "com.agateau.tinywheels.android"
            val gameInfo = context.packageManager.getApplicationInfo(gamePackage, 0)
            val installedGameApk = File(gameInfo.sourceDir)
            assertTrue("The real Pixel Wheels APK is not readable", installedGameApk.isFile && installedGameApk.canRead())

            val unsigned = File(context.cacheDir, "pixelwheels-gettext-unsigned.apk")
            val signed = File(context.cacheDir, "pixelwheels-gettext-ar.apk")
            unsigned.delete()
            signed.delete()
            val mode = InstrumentationRegistry.getArguments().getString("translationMode", "pseudo")
            val translator = if (mode == "real") OfflineArabicTranslator(context) else null
            val roundTrip = RoundTripVerifier()
            var roundTripAudited = 0
            var roundTripFlagged = 0
            val roundTripFlaggedSamples = mutableListOf<String>()
            try {
                if (translator != null) {
                    Log.i("PixelWheelsGettext", "translationMode=real: downloading the ML Kit English-Arabic model")
                    translator.prepareForOfflineUse(requireWifi = false)
                    assertTrue(
                        "ML Kit Arabic model is unavailable on this emulator; cannot produce a real-translation build",
                        translator.isArabicModelDownloaded()
                    )
                    translator.prepareReverseForOfflineUse(requireWifi = false)
                }
                val fonts = GettextPoLocalizer.FontInjection { f ->
                    context.assets.open(f.assetPath).use { it.readBytes() }
                }
                val report = GettextPoLocalizer.addArabicGettextSlot(installedGameApk, unsigned, fonts) { text ->
                    if (translator != null) {
                        if (text.isBlank()) {
                            text
                        } else {
                            val arabic = translator.translateEnglishToArabic(text)
                            // Round-trip audit on a sample of mid-length strings:
                            // translate back and compare content words. Flags are
                            // evidence for the CI report, never a rewrite.
                            if (roundTripAudited < 25 && text.trim().length in 10..80) {
                                try {
                                    val back = translator.translateArabicToEnglish(arabic)
                                    val verdict = roundTrip.verify(text.trim(), back)
                                    roundTripAudited++
                                    if (!verdict.trustworthy) {
                                        roundTripFlagged++
                                        if (roundTripFlaggedSamples.size < 5) {
                                            roundTripFlaggedSamples +=
                                                "$text => $arabic => $back (missing: ${verdict.missingWords.take(4).joinToString(", ")})"
                                        }
                                    }
                                } catch (error: Exception) {
                                    Log.w("PixelWheelsGettext", "round-trip audit sample failed", error)
                                }
                            }
                            arabic
                        }
                    } else {
                        val trimmed = text.trim()
                        if (trimmed.length >= 3) "العربية ${trimmed.length}" else text
                    }
                }
                Log.i("PixelWheelsGettext", "entry=${report.addedEntry} translated=${report.stringsTranslated} skipped=${report.stringsSkipped} merged=${report.fontsMerged} reason=${report.fontReason}")
                assertEquals("assets/po/ar.po", report.addedEntry)
                assertTrue("Too few Pixel Wheels strings translated: ${report.stringsTranslated}", report.stringsTranslated > 100)
                // The honest point of a real game: its fonts lack Arabic, so
                // the merge path MUST have run (synthetic fixtures skip it).
                assertTrue(
                    "Pixel Wheels ships no Arabic font; the cross-font merge path must have been exercised",
                    report.fontsMerged.isNotEmpty(),
                )
                assertTrue("Unsigned localized APK missing", unsigned.isFile && unsigned.length() > 0L)

                // Handoff for PixelWheelsVisualArabicInstrumentedTest: publish the
                // merged fonts to Downloads/ARABIFY so the visual leg can render
                // reference templates with the exact rasterizer input the game
                // uses. MediaStore is the proven channel across the visual leg's
                // locale reboot; filesDir did not survive it in CI.
                val handoffSelection = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?"
                val handoffArgs = arrayOf("Download/ARABIFY%", "merged-font-%.ttf")
                context.contentResolver.delete(MediaStore.Downloads.EXTERNAL_CONTENT_URI, handoffSelection, handoffArgs)
                ZipFile(unsigned).use { localized ->
                    report.fontsMerged.forEachIndexed { index, path ->
                        val entry = localized.getEntry(path) ?: error("merged font entry missing from output: $path")
                        val bytes = localized.getInputStream(entry).use { it.readBytes() }
                        val values = ContentValues().apply {
                            put(MediaStore.MediaColumns.DISPLAY_NAME, "merged-font-$index.ttf")
                            put(MediaStore.MediaColumns.MIME_TYPE, "font/ttf")
                            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/ARABIFY")
                            put(MediaStore.MediaColumns.IS_PENDING, 1)
                        }
                        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                            ?: error("Could not create Downloads entry for merged-font-$index.ttf")
                        try {
                            context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                                ?: error("Could not open Downloads output for merged-font-$index.ttf")
                            val ready = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                            context.contentResolver.update(uri, ready, null, null)
                        } catch (handoffError: Throwable) {
                            context.contentResolver.delete(uri, null, null)
                            throw handoffError
                        }
                    }
                }
                Log.i("PixelWheelsGettext", "visual font handoff: ${report.fontsMerged}")

                ZipFile(unsigned).use { localized ->
                    val arPo = localized.getEntry(report.addedEntry) ?: error("ar.po entry missing")
                    val arPoText = localized.getInputStream(arPo).use { it.readBytes() }.toString(Charsets.UTF_8)
                    assertTrue(
                        "ar.po must be pre-shaped to presentation forms for the game's unshaped renderer",
                        arPoText.any { it.code in 0xFE70..0xFEFF },
                    )
                    assertTrue("ar.po must carry the Arabic plural rules", arPoText.contains("nplurals=6"))
                    // Existing languages untouched: spot-check the French po survives byte-identical.
                    val frEntry = localized.getEntry("assets/po/fr.po") ?: error("existing fr.po missing")
                    ZipFile(installedGameApk).use { original ->
                        val originalFr = original.getEntry("assets/po/fr.po") ?: error("original fr.po missing")
                        assertTrue(
                            "Existing fr.po bytes changed",
                            digest(original, "assets/po/fr.po").contentEquals(digest(localized, "assets/po/fr.po")),
                        )
                        assertTrue(originalFr.size == frEntry.size)
                    }
                }

                ApkSigningService.signAndVerify(context, unsigned, signed)
                val verification = ApkVerifier.Builder(signed).build().verify()
                assertTrue("Signed Pixel Wheels APK failed verification: ${verification.errors}", verification.isVerified)

                if (mode == "pseudo") {
                    // Dispatch evidence on the REAL game: the planner must pick
                    // the gettext wrapper by itself (no slot, no fallback).
                    val stage = File(context.cacheDir, "pixelwheels-dispatch-stage")
                    stage.deleteRecursively()
                    stage.mkdirs()
                    val deps = AdapterRuntimeDeps(
                        stagingDir = stage,
                        fontBytes = { f -> context.assets.open(f.assetPath).use { it.readBytes() } },
                        translate = { text ->
                            val trimmed = text.trim()
                            if (trimmed.length >= 3) "العربية ${trimmed.length}" else text
                        },
                        onProgress = { _, _ -> },
                    )
                    val registry = ArabifyAdapters.runtimeChainRegistry(deps)
                    val artifact = ArtifactRef(
                        id = installedGameApk.name,
                        displayName = installedGameApk.name,
                        uri = installedGameApk.absolutePath,
                        kind = ArtifactKind.APK,
                        sizeBytes = installedGameApk.length(),
                    )
                    val dispatch = PlannerDispatch(registry).dispatch(artifact, ResourceBudget())
                    assertEquals("adapter.gettext.po", dispatch.adapterId)
                    assertEquals(listOf("adapter.gettext.po"), dispatch.attempted)
                    Log.i("PixelWheelsGettext", "dispatch on the real game selected ${dispatch.adapterId} with no fallback")
                    stage.deleteRecursively()
                }

                val evidenceDir = File(context.filesDir, "pixelwheels-gettext-test")
                check(evidenceDir.mkdirs() || evidenceDir.isDirectory)
                val suffix = if (mode == "real") "-real" else ""
                val preservedApk = File(evidenceDir, "pixelwheels-arabic$suffix.apk")
                signed.copyTo(preservedApk, overwrite = true)
                val reportFile = File(evidenceDir, "pixelwheels-report$suffix.txt")
                reportFile.writeText(
                    "gamePackage=$gamePackage\n" +
                        "translationMode=$mode\n" +
                        "addedEntry=${report.addedEntry}\n" +
                        "translatedStrings=${report.stringsTranslated}\n" +
                        "skippedStrings=${report.stringsSkipped}\n" +
                        "skipReasons=" + report.skipReasons.entries.sortedByDescending { it.value }
                            .joinToString(", ") { (reason, count) -> "$reason:$count" } + "\n" +
                        "repairedStrings=${report.stringsRepaired}\n" +
                        "repairReasons=" + report.repairReasons.entries.sortedByDescending { it.value }
                            .joinToString(", ") { (reason, count) -> "$reason:$count" } + "\n" +
                        "fontsMerged=${report.fontsMerged}\n" +
                        "fontReason=${report.fontReason}\n" +
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
                publishToDownloads(preservedApk, "pixelwheels-arabic$suffix.apk", "application/vnd.android.package-archive")
                publishToDownloads(reportFile, "pixelwheels-report$suffix.txt", "text/plain")
                if (translator != null) {
                    val roundTripFile = File(evidenceDir, "pixelwheels-roundtrip$suffix.txt")
                    roundTripFile.writeText(
                        "audited=$roundTripAudited\nflagged=$roundTripFlagged\n" +
                            roundTripFlaggedSamples.joinToString("\n") + "\n"
                    )
                    publishToDownloads(roundTripFile, "pixelwheels-roundtrip$suffix.txt", "text/plain")
                }
                Log.i("PixelWheelsGettext", "Published signed localized Pixel Wheels APK and report to Downloads/ARABIFY (mode=$mode)")
            } finally {
                translator?.close()
                unsigned.delete()
                signed.delete()
            }
        }
    }

    private fun digest(zip: ZipFile, entryName: String): ByteArray {
        val entry = zip.getEntry(entryName) ?: return ByteArray(0)
        val digest = MessageDigest.getInstance("SHA-256")
        zip.getInputStream(entry).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest()
    }
}
