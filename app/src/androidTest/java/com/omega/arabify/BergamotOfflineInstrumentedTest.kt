package com.omega.arabify

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Debug
import android.os.SystemClock
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
            val modelBytes = modelDir.listFiles()
                ?.filter { it.isFile && it.name != "config.yml" }
                ?.sumOf { it.length() } ?: 0L
            assertTrue("${direction.name} model package is unexpectedly small: $modelBytes", modelBytes > 30_000_000L)

            val beforePssKb = Debug.MemoryInfo().let { info ->
                Debug.getMemoryInfo(info)
                info.totalPss
            }
            val loadStart = SystemClock.elapsedRealtime()
            val session = BergamotNative.nativeCreate(config.absolutePath)
            val loadMs = SystemClock.elapsedRealtime() - loadStart
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

                val afterPssKb = Debug.MemoryInfo().let { info ->
                    Debug.getMemoryInfo(info)
                    info.totalPss
                }
                Log.i(
                    tag,
                    "direction=${direction.name} packageBytes=$modelBytes loadMs=$loadMs " +
                        "translateMs=$translateMs pssBeforeKb=$beforePssKb pssAfterKb=$afterPssKb"
                )
                assertTrue("${direction.name} load time was not measured", loadMs > 0L)
                assertTrue("${direction.name} translation time was not measured", translateMs >= 0L)
                assertTrue("${direction.name} memory measurement unavailable", afterPssKb > 0)
            } finally {
                BergamotNative.nativeDestroy(session)
            }
        }
    }

    private fun assertNoInternet(context: Context) {
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivity.activeNetwork
        val hasInternet = network?.let {
            connectivity.getNetworkCapabilities(it)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } ?: false
        assertFalse("CI emulator must have networking disabled for this offline test", hasInternet)
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
