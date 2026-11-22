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
import java.io.RandomAccessFile
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

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

    @Test
    fun signingPreservesStoredArscAlignmentAfterAssetRepack() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val gameInfo = context.packageManager.getApplicationInfo("com.joeld.minesweeper", 0)
        val sourceApk = File(gameInfo.sourceDir)
        val unsignedApk = File(context.cacheDir, "alignment-policy-unsigned.apk")
        val signedApk = File(context.cacheDir, "alignment-policy-signed.apk")
        unsignedApk.delete()
        signedApk.delete()

        try {
            ZipFile(sourceApk).use { input ->
                assertTrue("The real game fixture has no resources.arsc", input.getEntry("resources.arsc") != null)
                val counting = CountingOutputStream(unsignedApk.outputStream().buffered())
                ZipOutputStream(counting).use { output ->
                    val entries = input.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        if (isOldSignatureEntry(entry.name)) continue
                        val target = ApkZipEntryPolicy.create(entry, null, counting.bytesWritten)
                        output.putNextEntry(target)
                        if (!entry.isDirectory) {
                            input.getInputStream(entry).use { it.copyTo(output) }
                        }
                        output.closeEntry()
                    }
                }
            }

            ApkSigningService.signAndVerify(context, unsignedApk, signedApk)
            assertTrue("Signed alignment fixture was not created", signedApk.isFile && signedApk.length() > 0L)
            ZipFile(signedApk).use { output ->
                val resources = requireNotNull(output.getEntry("resources.arsc"))
                assertEquals("resources.arsc must remain uncompressed after signing", ZipEntry.STORED, resources.method)
            }
            assertEquals(
                "ApkSigner must preserve the four-byte resources.arsc data alignment",
                0L,
                localDataOffset(signedApk, "resources.arsc") % 4L
            )
        } finally {
            unsignedApk.delete()
            signedApk.delete()
        }
    }

    private fun isOldSignatureEntry(name: String): Boolean {
        val normalized = name.uppercase(java.util.Locale.ROOT)
        if (!normalized.startsWith("META-INF/")) return false
        val fileName = normalized.substringAfterLast('/')
        return fileName == "MANIFEST.MF" ||
            fileName.endsWith(".SF") ||
            fileName.endsWith(".RSA") ||
            fileName.endsWith(".DSA") ||
            fileName.endsWith(".EC")
    }

    private fun localDataOffset(apk: File, wantedName: String): Long {
        RandomAccessFile(apk, "r").use { input ->
            val lowerBound = maxOf(0L, input.length() - 65_557L)
            var endOfCentralDirectory = -1L
            var position = input.length() - 22L
            while (position >= lowerBound) {
                input.seek(position)
                if (readLittleEndianInt(input) == 0x06054B50L) {
                    input.seek(position + 20L)
                    val commentLength = readLittleEndianShort(input)
                    if (position + 22L + commentLength == input.length()) {
                        endOfCentralDirectory = position
                        break
                    }
                }
                position--
            }
            check(endOfCentralDirectory >= 0L) { "ZIP end-of-central-directory record was not found" }

            input.seek(endOfCentralDirectory + 10L)
            val entryCount = readLittleEndianShort(input)
            input.seek(endOfCentralDirectory + 16L)
            val centralDirectoryOffset = readLittleEndianInt(input)
            check(entryCount != 0xFFFF && centralDirectoryOffset != 0xFFFF_FFFFL) {
                "ZIP64 APK fixture is outside this test's bounded parser"
            }

            var cursor = centralDirectoryOffset
            repeat(entryCount) {
                input.seek(cursor)
                check(readLittleEndianInt(input) == 0x02014B50L) {
                    "Malformed ZIP central-directory record"
                }
                input.seek(cursor + 28L)
                val nameLength = readLittleEndianShort(input)
                val extraLength = readLittleEndianShort(input)
                val commentLength = readLittleEndianShort(input)
                input.seek(cursor + 42L)
                val localHeaderOffset = readLittleEndianInt(input)
                input.seek(cursor + 46L)
                val nameBytes = ByteArray(nameLength)
                input.readFully(nameBytes)
                val name = String(nameBytes, Charsets.UTF_8)
                if (name == wantedName) {
                    input.seek(localHeaderOffset + 26L)
                    val localNameLength = readLittleEndianShort(input)
                    val localExtraLength = readLittleEndianShort(input)
                    return localHeaderOffset + 30L + localNameLength + localExtraLength
                }
                cursor += 46L + nameLength + extraLength + commentLength
            }
        }
        error("ZIP local header was not found for the requested entry")
    }

    private fun readLittleEndianShort(input: RandomAccessFile): Int {
        val low = input.read()
        val high = input.read()
        check(low >= 0 && high >= 0) { "Unexpected end of ZIP file" }
        return low or (high shl 8)
    }

    private fun readLittleEndianInt(input: RandomAccessFile): Long {
        val b0 = input.read()
        val b1 = input.read()
        val b2 = input.read()
        val b3 = input.read()
        check(b0 >= 0 && b1 >= 0 && b2 >= 0 && b3 >= 0) { "Unexpected end of ZIP file" }
        return b0.toLong() or
            (b1.toLong() shl 8) or
            (b2.toLong() shl 16) or
            (b3.toLong() shl 24)
    }

}
