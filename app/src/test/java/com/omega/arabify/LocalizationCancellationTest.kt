package com.omega.arabify

import java.io.File
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LocalizationCancellationTest {
    private fun writeApk(file: File, text: String) {
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("assets/strings.json"))
            zip.write(text.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
    }

    @Test
    fun assetRewritePropagatesCancellationDuringTranslationAndDoesNotCreateOutput() = runBlocking {
        val input = File.createTempFile("arabify-cancel-input-", ".apk")
        val output = File.createTempFile("arabify-cancel-output-", ".apk").apply { delete() }
        writeApk(input, """{"dialogue":"The ancient gate is locked","menu":"Start Game"}""")
        val operation = Job()
        var translationCalls = 0
        try {
            try {
                withContext(operation) {
                    AndroidAssetLocalizer.transformToUnsignedApk(
                        inputApk = input,
                        unsignedApk = output,
                        requireTranslations = true
                    ) {
                        translationCalls++
                        operation.cancel(CancellationException("cancel from test"))
                        "البوابة القديمة مغلقة"
                    }
                }
                fail("Expected cancellation to escape the archive rewrite.")
            } catch (_: CancellationException) {
                // Expected: cancellation must not be counted as a skipped string.
            }
            assertEquals(1, translationCalls)
            assertFalse(output.exists())
        } finally {
            input.delete()
            output.delete()
        }
    }

    @Test
    fun unityDeepInspectionRethrowsCancellationFromInsideBundleProcessing() {
        val apk = File.createTempFile("arabify-unity-deep-cancel-", ".apk")
        val scratch = kotlin.io.path.createTempDirectory("arabify-unity-deep-cancel-").toFile()
        val bundlePath = "assets/bin/Data/StreamingAssets/cancel.bundle"
        val candidate = UnityAssetInventoryItem(
            path = bundlePath,
            sizeBytes = 1L,
            kind = UnityAssetKind.UNITY_ARCHIVE,
            support = UnityAssetSupport.BUNDLE_HEADER_ONLY_READ_ONLY,
            detail = "test fixture"
        )
        val inventory = UnityAssetInventory(
            totalUnityFiles = 1,
            streamingTextCandidates = 0,
            serializedFiles = 0,
            bundleFiles = 1,
            externalStreams = 0,
            preservedUnsupported = 0,
            items = listOf(candidate),
            notes = emptyList(),
            bundleCandidates = listOf(candidate)
        )
        try {
            ZipOutputStream(apk.outputStream().buffered()).use { zip ->
                zip.putNextEntry(ZipEntry(bundlePath))
                zip.write(byteArrayOf(0x55))
                zip.closeEntry()
            }
            val requested = CancellationException("user cancelled deep inspection")
            var checks = 0
            try {
                UnityBundleDeepInspector.inspect(
                    apk = apk,
                    inventory = inventory,
                    scratchDirectory = scratch,
                    limits = UnityBundleDeepInspectionLimits(
                        maxSingleBundleBytes = 1L,
                        maxTotalBundleBytes = 1L,
                        maxTotalDecodedBytes = 1L,
                        maxTotalExtractedBytes = 1L,
                        maxAggregateExtractedBytes = 1L,
                        maxSingleNodeBytes = 1L,
                        maxNodes = 1,
                        maxZipEntriesToVerify = 2,
                        scratchReserveBytes = 0L
                    ),
                    cancellationCheck = {
                        checks++
                        if (checks == 4) throw requested
                    }
                )
                fail("Cancellation must not be converted into a per-bundle failure.")
            } catch (actual: CancellationException) {
                assertTrue(actual === requested)
            }
            assertEquals(4, checks)
            assertTrue(scratch.listFiles()!!.isEmpty())
        } finally {
            apk.delete()
            scratch.deleteRecursively()
        }
    }
}
