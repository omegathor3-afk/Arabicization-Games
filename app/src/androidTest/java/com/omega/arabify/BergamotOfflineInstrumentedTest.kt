package com.omega.arabify

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Debug
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileNotFoundException
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class BergamotOfflineInstrumentedTest {
    private val tag = "BergamotOfflineTest"

    @Test
    fun bothArabicDirectionsLoadAndTranslateWithNetworkDisabled() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val network = assertNoInternet(context)
        val root = File(context.filesDir, "bergamot-instrumented")
        root.deleteRecursively()
        root.mkdirs()
        copyAssetTree(instrumentation.context, "bergamot", root)

        val directions = listOf(
            Direction("ar-en", "مرحبا بالعالم", "صباح الخير"),
            Direction("en-ar", "The ocean is deep.", "The sea is dark.")
        )
        for (direction in directions) {
            val modelDir = File(root, direction.name)
            val config = File(modelDir, "config.yml")
            assertTrue("Missing ${direction.name} config", config.isFile)
            val configText = config.readText(Charsets.UTF_8)
            val referencedFiles = listOf("models", "vocabs", "shortlist").flatMap { configEntries(configText, it) }.distinct()
            assertTrue("${direction.name} config has no model/vocabulary/lexical references", referencedFiles.size >= 3)
            for (name in referencedFiles) {
                val referenced = File(modelDir, name)
                assertTrue("${direction.name} config references missing or empty file: $name", referenced.isFile && referenced.length() > 0L)
            }
            val packageBytes = modelDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            assertTrue("${direction.name} model package is unexpectedly small: $packageBytes", packageBytes > 30_000_000L)
            val packageFiles = modelDir.walkTopDown().filter { it.isFile }.associate { it.name to it.length() }
            val pssBeforeLoadKb = currentPssKb()
            var session = 0L
            try {
                val loadStartNs = SystemClock.elapsedRealtimeNanos()
                session = BergamotNative.nativeCreate(config.absolutePath)
                val loadMs = (SystemClock.elapsedRealtimeNanos() - loadStartNs) / 1_000_000.0
                val pssAfterLoadKb = currentPssKb()
                assertTrue("${direction.name} native session was not created", session != 0L)

                val firstStartNs = SystemClock.elapsedRealtimeNanos()
                val firstOutput = BergamotNative.nativeTranslate(session, direction.input)
                val firstTranslateMs = (SystemClock.elapsedRealtimeNanos() - firstStartNs) / 1_000_000.0
                val pssAfterFirstTranslateKb = currentPssKb()

                val subsequentStartNs = SystemClock.elapsedRealtimeNanos()
                val subsequentOutput = BergamotNative.nativeTranslate(session, direction.subsequentInput)
                val subsequentTranslateMs = (SystemClock.elapsedRealtimeNanos() - subsequentStartNs) / 1_000_000.0
                val pssAfterSubsequentTranslateKb = currentPssKb()

                val record = JSONObject()
                    .put("direction", direction.name)
                    .put("modelPackageBytes", packageBytes)
                    .put("modelFiles", JSONObject(packageFiles))
                    .put("loadMs", loadMs)
                    .put("firstTranslateMs", firstTranslateMs)
                    .put("subsequentTranslateMs", subsequentTranslateMs)
                    .put("pssBeforeLoadKb", pssBeforeLoadKb)
                    .put("pssAfterLoadKb", pssAfterLoadKb)
                    .put("pssAfterFirstTranslateKb", pssAfterFirstTranslateKb)
                    .put("pssAfterSubsequentTranslateKb", pssAfterSubsequentTranslateKb)
                    .put("firstInput", direction.input)
                    .put("firstOutput", firstOutput)
                    .put("subsequentInput", direction.subsequentInput)
                    .put("subsequentOutput", subsequentOutput)
                    .put("apiLevel", Build.VERSION.SDK_INT)
                    .put("androidRelease", Build.VERSION.RELEASE)
                    .put("supportedAbis", Build.SUPPORTED_ABIS.joinToString(","))
                    .put("networkAssertionsPassed", true)
                    .put("airplaneModeEnabled", network.airplaneModeEnabled)
                    .put("wifiEnabled", network.wifiEnabled)
                    .put("mobileDataEnabled", network.mobileDataEnabled)
                    .put("internetCapableNetworkCount", network.internetCapableNetworkCount)
                Log.i(tag, "METRIC_JSON:${record}")

                assertTrue("${direction.name} first translation is blank", firstOutput.isNotBlank())
                assertTrue("${direction.name} subsequent translation is blank", subsequentOutput.isNotBlank())
                assertSemanticTranslation(direction.name, firstOutput)
                assertLanguageScript(direction.name, subsequentOutput, direction.subsequentInput)
                assertTrue("${direction.name} model load measurement unavailable", loadMs > 0.0)
                assertTrue("${direction.name} first translation measurement unavailable", firstTranslateMs >= 0.0)
                assertTrue("${direction.name} subsequent translation measurement unavailable", subsequentTranslateMs >= 0.0)
                assertTrue("${direction.name} PSS measurement unavailable", pssAfterLoadKb > 0 && pssAfterFirstTranslateKb > 0 && pssAfterSubsequentTranslateKb > 0)
            } finally {
                if (session != 0L) BergamotNative.nativeDestroy(session)
            }
        }
    }

    private fun assertSemanticTranslation(direction: String, output: String) {
        val normalized = output.lowercase(Locale.ROOT)
        if (direction == "ar-en") {
            assertTrue("ar-en translation does not contain a greeting: $output", normalized.contains("hello") || normalized.contains("hi"))
            assertTrue("ar-en translation does not preserve the meaning of 'world': $output", normalized.contains("world"))
        } else {
            val hasSea = output.contains("المحيط") || output.contains("البحر")
            val hasDepth = output.contains("عميق") || output.contains("عميقة") || output.contains("عميقًا")
            assertTrue("en-ar translation does not preserve the ocean/sea meaning: $output", hasSea)
            assertTrue("en-ar translation does not preserve the meaning of 'deep': $output", hasDepth)
        }
    }

    private fun assertLanguageScript(direction: String, output: String, input: String) {
        assertTrue("${direction} subsequent output is unchanged or blank: $output", output.isNotBlank() && output != input)
        if (direction == "ar-en") {
            assertTrue("ar-en subsequent output lacks Latin text: $output", output.any { it.code in 65..90 || it.code in 97..122 })
        } else {
            assertTrue("en-ar subsequent output lacks Arabic script: $output", output.any { it.code in 0x0600..0x06FF || it.code in 0x0750..0x077F || it.code in 0x08A0..0x08FF })
        }
    }

    private fun assertNoInternet(context: Context): NetworkState {
        val airplaneModeEnabled = Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
        assertTrue("CI emulator must have airplane mode enabled", airplaneModeEnabled)
        val wifiEnabled = (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).isWifiEnabled
        assertFalse("CI emulator Wi-Fi must be disabled", wifiEnabled)
        val mobileDataEnabled = Settings.Global.getInt(context.contentResolver, "mobile_data", 0) == 1
        assertFalse("CI emulator mobile data must be disabled", mobileDataEnabled)

        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val internetCapableNetworks = connectivity.allNetworks.filter { network ->
            val capabilities = connectivity.getNetworkCapabilities(network)
            capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true ||
                capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        }
        assertTrue("CI emulator still exposes internet-capable or validated networks: ${internetCapableNetworks.size}", internetCapableNetworks.isEmpty())
        val activeNetwork = connectivity.activeNetwork
        val activeCapabilities = activeNetwork?.let(connectivity::getNetworkCapabilities)
        assertFalse(
            "CI emulator active network still has internet capability",
            activeCapabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true ||
                activeCapabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        )
        return NetworkState(airplaneModeEnabled, wifiEnabled, mobileDataEnabled, internetCapableNetworks.size)
    }

    private fun configEntries(config: String, section: String): List<String> {
        val lines = config.lines()
        val start = lines.indexOfFirst { it.trim() == "$section:" }
        if (start < 0) return emptyList()
        val result = mutableListOf<String>()
        for (line in lines.drop(start + 1)) {
            if (line.isBlank()) continue
            if (!line.first().isWhitespace()) break
            val trimmed = line.trim()
            if (trimmed.startsWith("-")) {
                val value = trimmed.removePrefix("-").trim().trim('"', '\'')
                if (value.isNotEmpty() && value != "false") result += value
            }
        }
        return result
    }

    private fun currentPssKb(): Int {
        val info = Debug.MemoryInfo()
        Debug.getMemoryInfo(info)
        return info.totalPss
    }

    private fun copyAssetTree(context: Context, assetPath: String, destination: File) {
        val fileStream = try { context.assets.open(assetPath) } catch (_: FileNotFoundException) { null }
        if (fileStream != null) {
            fileStream.use { input ->
                destination.parentFile?.mkdirs()
                destination.outputStream().use { output -> input.copyTo(output) }
            }
            return
        }
        destination.mkdirs()
        for (child in context.assets.list(assetPath).orEmpty()) {
            copyAssetTree(context, "$assetPath/$child", File(destination, child))
        }
    }

    private data class Direction(val name: String, val input: String, val subsequentInput: String)
    private data class NetworkState(
        val airplaneModeEnabled: Boolean,
        val wifiEnabled: Boolean,
        val mobileDataEnabled: Boolean,
        val internetCapableNetworkCount: Int
    )
}
