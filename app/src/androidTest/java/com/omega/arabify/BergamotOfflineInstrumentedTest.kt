package com.omega.arabify

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Debug
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class BergamotOfflineInstrumentedTest {
    private val tag = "BergamotOfflineTest"

    @Test
    fun bothArabicDirectionsLoadAndTranslateWithNetworkDisabled() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertNoInternet(context)

        val root = File(context.filesDir, "bergamot-instrumented")
        root.deleteRecursively()
        root.mkdirs()
        copyAssetTree(instrumentation.context, "bergamot", root)

        val directions = listOf(
            Direction("ar-en", "مرحبا بالعالم"),
            Direction("en-ar", "The ocean is deep.")
        )
        for (direction in directions) {
            val modelDir = File(root, direction.name)
            val config = File(modelDir, "config.yml")
            assertTrue("Missing ${direction.name} config", config.isFile)
            val packageBytes = modelDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            assertTrue("${direction.name} model package is unexpectedly small: $packageBytes", packageBytes > 30_000_000L)

            val beforePssKb = currentPssKb()
            val loadStart = SystemClock.elapsedRealtime()
            val session = BergamotNative.nativeCreate(config.absolutePath)
            val loadMs = SystemClock.elapsedRealtime() - loadStart
            val afterLoadPssKb = currentPssKb()
            try {
                assertTrue("${direction.name} native session was not created", session != 0L)
                val translateStart = SystemClock.elapsedRealtime()
                val translated = BergamotNative.nativeTranslate(session, direction.input)
                val translateMs = SystemClock.elapsedRealtime() - translateStart
                assertTrue("${direction.name} returned blank output", translated.isNotBlank())
                assertNotEquals("${direction.name} returned unchanged source text", direction.input, translated)
                if (direction.name == "ar-en") {
                    assertTrue("ar-en output lacks Latin text: $translated", translated.any { it.code in 65..90 || it.code in 97..122 })
                } else {
                    assertTrue("en-ar output lacks Arabic script: $translated", translated.any { it.code in 0x0600..0x06FF || it.code in 0x0750..0x077F || it.code in 0x08A0..0x08FF })
                }

                val afterTranslatePssKb = currentPssKb()
                Log.i(
                    tag,
                    "direction=${direction.name} packageBytes=$packageBytes loadMs=$loadMs " +
                        "translateMs=$translateMs pssBeforeKb=$beforePssKb " +
                        "pssAfterLoadKb=$afterLoadPssKb pssAfterTranslateKb=$afterTranslatePssKb " +
                        "translated=${translated.replace('\n', ' ')}"
                )
                assertTrue("${direction.name} load time was not measured", loadMs > 0L)
                assertTrue("${direction.name} translation time was not measured", translateMs >= 0L)
                assertTrue("${direction.name} memory measurement unavailable", afterLoadPssKb > 0 && afterTranslatePssKb > 0)

            } finally {
                BergamotNative.nativeDestroy(session)
            }
        }
    }

    private fun assertNoInternet(context: Context) {
        val airplaneModeEnabled = Settings.Global.getInt(
            context.contentResolver,
            Settings.Global.AIRPLANE_MODE_ON,
            0
        ) == 1
        assertTrue("CI emulator must have airplane mode enabled", airplaneModeEnabled)

        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val internetCapableNetworks = connectivity.allNetworks.filter { network ->
            val capabilities = connectivity.getNetworkCapabilities(network)
            capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true ||
                capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        }
        assertTrue(
            "CI emulator still exposes internet-capable networks: ${internetCapableNetworks.size}",
            internetCapableNetworks.isEmpty()
        )
        val activeNetwork = connectivity.activeNetwork
        val activeCapabilities = activeNetwork?.let(connectivity::getNetworkCapabilities)
        assertFalse(
            "CI emulator active network still has internet capability",
            activeCapabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        )
    }

    private fun currentPssKb(): Int {
        val info = Debug.MemoryInfo()
        Debug.getMemoryInfo(info)
        return info.totalPss
    }

    private fun copyAssetTree(context: Context, assetPath: String, destination: File) {
        val children = context.assets.list(assetPath).orEmpty()
        if (children.isEmpty()) {
            destination.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input ->
                destination.outputStream().use { output -> input.copyTo(output) }
            }
            return
        }
        destination.mkdirs()
        for (child in children) {
            copyAssetTree(context, "$assetPath/$child", File(destination, child))
        }
    }

    private data class Direction(val name: String, val input: String)
}
