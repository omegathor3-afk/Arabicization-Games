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
                        extractNativeLibs = false,
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
    fun unityDeepInspectionRethrowsCancellationInsteadOfReportingBundleFailure() {
        val apk = File.createTempFile("arabify-unity-deep-cancel-", ".apk")
        val scratch = kotlin.io.path.createTempDirectory("arabify-unity-deep-cancel-").toFile()
        val inventory = UnityAssetInventory(
            totalUnityFiles = 0,
            streamingTextCandidates = 0,
            serializedFiles = 0,
            bundleFiles = 0,
            externalStreams = 0,
            preservedUnsupported = 0,
            items = emptyList(),
            notes = emptyList()
        )
        try {
            val requested = CancellationException("user cancelled deep inspection")
            try {
                UnityBundleDeepInspector.inspect(
                    apk = apk,
                    inventory = inventory,
                    scratchDirectory = scratch,
                    cancellationCheck = { throw requested }
                )
                fail("Cancellation must not be converted into a diagnostic result.")
            } catch (actual: CancellationException) {
                assertTrue(actual === requested)
            }
            assertTrue(scratch.listFiles()!!.isEmpty())
        } finally {
            apk.delete()
            scratch.deleteRecursively()
        }
    }
}
