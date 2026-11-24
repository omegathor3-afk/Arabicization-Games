package com.omega.arabify

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs the app's real Unity Mono production path on-device: build a
 * real-format Unity Mono game APK (UnityPy-validated fixture builders),
 * localize it with UnityMonoLocalizer (the exact function MainActivity
 * calls) with the network DISABLED after the model download, then decode
 * the signed output and read the Arabic TextAsset back through the app's
 * own strict parsers. Covers AndroidKeyStore signing, which JVM tests
 * cannot. Installing and launching is not possible for a synthetic APK,
 * so install/launch coverage stays with the real-game Minesweeper test.
 */
@RunWith(AndroidJUnit4::class)
class UnityMonoPipelineInstrumentedTest {

    private val tag = "UnityMonoPipeline"

    private fun runShell(instrumentation: android.app.Instrumentation, command: String) {
        instrumentation.uiAutomation.executeShellCommand(command).close()
    }

    private fun hasValidatedInternet(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun containsArabic(text: String): Boolean =
        text.any { it.code in 0x0600..0x06FF }

    @Test
    fun unityMonoApkLocalizesOfflineOnDeviceThroughProductionPath() {
        runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext

        // Build a real-format Unity Mono game APK: Managed/ marker for engine
        // detection, a UnityFS bundle holding a TextAsset SerializedFile, and
        // one loose StreamingAssets text file for the second pipeline stage.
        val serialized = UnityTestFixtures.serializedFileV17(
            listOf(UnityTestFixtures.TextAssetSpec(1L, "menu.txt", "Start Game\nOptions"))
        )
        val bundle = UnityTestFixtures.unityFs(
            listOf(UnityTestFixtures.NodeSpec("CAB-menu", 4, serialized))
        )
        val input = File(context.cacheDir, "unity-fixture-game.apk")
        ZipOutputStream(input.outputStream().buffered()).use { out ->
            fun add(name: String, bytes: ByteArray) {
                out.putNextEntry(ZipEntry(name))
                out.write(bytes)
                out.closeEntry()
            }
            add("classes.dex", byteArrayOf(100, 101, 120, 10))
            add("assets/bin/Data/Managed/Assembly-CSharp.dll", byteArrayOf(77, 90, 1, 2))
            add("assets/bin/Data/StreamingAssets/pack.unity3d", bundle)
            add("assets/bin/Data/StreamingAssets/notes.txt", "Daily Reward".toByteArray())
        }

        val unsigned = File(context.cacheDir, "unity-fixture-unsigned.apk")
        val signed = File(context.cacheDir, "unity-fixture-signed.apk")
        unsigned.delete(); signed.delete()
        val translator = OfflineArabicTranslator(context)
        try {
            val report = withTimeout(12 * 60 * 1000L) {
                translator.prepareForOfflineUse(requireWifi = false)
                Log.i(tag, "Model ready; disabling network before translation")
                try {
                    runShell(instrumentation, "svc wifi disable")
                    runShell(instrumentation, "svc data disable")
                    Thread.sleep(2000)
                    assertTrue(
                        "Validated internet is still available; offline translation was not isolated",
                        !hasValidatedInternet(context)
                    )
                    UnityMonoLocalizer.localize(context, input, unsigned, signed, translator)
                } finally {
                    runShell(instrumentation, "svc data enable")
                    runShell(instrumentation, "svc wifi enable")
                }
            }
            Log.i(tag, "Localized ${report.stringsTranslated} strings, bundle share=${report.bundleStringsTranslated}")
            assertTrue("No strings translated on device", report.stringsTranslated > 0)
            assertNotNull("Bundle stage did not run", report.bundleStringsTranslated)
            assertTrue("No bundled strings translated", (report.bundleStringsTranslated ?: 0) > 0)
            assertTrue("Signed APK missing", signed.isFile && signed.length() > 0L)

            // Loose text stage: notes.txt now holds Arabic.
            val notes = ZipFile(signed).use { zip ->
                zip.getInputStream(zip.getEntry("assets/bin/Data/StreamingAssets/notes.txt")).use {
                    String(it.readBytes(), Charsets.UTF_8)
                }
            }
            assertTrue("Loose StreamingAssets text was not Arabized: $notes", containsArabic(notes))

            // Bundle stage: decode the signed bundle and read the TextAsset
            // back through the app's strict parsers.
            val signedBundle = File(context.cacheDir, "unity-fixture-signed.unity3d")
            ZipFile(signed).use { zip ->
                zip.getInputStream(zip.getEntry("assets/bin/Data/StreamingAssets/pack.unity3d")).use {
                    signedBundle.writeBytes(it.readBytes())
                }
            }
            val stream = ByteArrayOutputStream()
            val decoded = UnityFsBundleDecoder.decode(signedBundle, stream)
            assertNotNull("Signed bundle no longer decodes", decoded)
            val cabNode = decoded!!.blockInfo.nodes.single { it.path == "CAB-menu" }
            val streamBytes = stream.toByteArray()
            val cabFile = File(context.cacheDir, "unity-fixture-final.assets")
            cabFile.writeBytes(
                streamBytes.copyOfRange(cabNode.offset.toInt(), (cabNode.offset + cabNode.size).toInt())
            )
            val inspection = cabFile.inputStream().use {
                UnitySerializedFileInspector.inspect(it, cabFile.length())
            }!!
            val content = UnityTextAssetReader.read(
                cabFile, inspection.header, inspection.typeTable!!,
                inspection.objectTable!!.objects.single { it.pathId == 1L }
            )!!
            Log.i(tag, "Read back on device: ${content.script}")
            assertTrue(
                "Bundled TextAsset is not Arabic on device: ${content.script}",
                containsArabic(content.script)
            )
        } finally {
            translator.close()
        }
        }
    }
}
