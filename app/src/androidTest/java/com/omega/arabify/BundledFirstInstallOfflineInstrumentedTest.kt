package com.omega.arabify

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.provider.Settings
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves Omega's 2026-11-27 decision end to end: a FRESH install of the app
 * APK (which must carry the bundled en-ar Bergamot package) translates
 * English to Arabic through the PRODUCTION translation class with airplane
 * mode on and zero internet-capable networks — no ML Kit model download, no
 * pre-seeded state.
 *
 * Runs in the bergamot-native workflow's offline job, whose script enforces
 * airplane mode / Wi-Fi off / mobile data off before any test starts. On the
 * fresh CI emulator the ML Kit Arabic model is not downloaded, so the
 * production fallback must select the bundled engine, and the test asserts
 * [OfflineArabicTranslator.lastEngine] instead of assuming the selection.
 */
@RunWith(AndroidJUnit4::class)
class BundledFirstInstallOfflineInstrumentedTest {
    private val tag = "BundledFirstInstall"

    @Test
    fun freshInstallTranslatesOfflineThroughProductionPath() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertNoInternet(context)

        val bundled = BundledBergamotTranslator(context)
        assertTrue(
            "APK does not carry the bundled en-ar model package (assets/arabify-models/en-ar)",
            bundled.isPackaged(),
        )
        Log.i(tag, "bundled-package:present")

        val translator = OfflineArabicTranslator(context)
        try {
            val mlKitReady = translator.isArabicModelDownloaded()
            Log.i(tag, "mlkit-arabic-model-downloaded=$mlKitReady (fresh install expects false)")

            val output = translator.translateEnglishToArabic("The ocean is deep.")
            Log.i(tag, "production-translate engine=${translator.lastEngine} outputLength=${output.length}")
            assertTrue("production translation returned blank offline", output.isNotBlank())
            assertTrue(
                "production translation returned the source text unchanged offline",
                !output.contains("ocean", ignoreCase = true),
            )
            assertTrue(
                "production translation carries no Arabic script offline",
                output.any { it.code in 0x0600..0x06FF },
            )
            if (!mlKitReady) {
                assertEquals(
                    "with no ML Kit model and no network, production must use the bundled engine",
                    "bundled-bergamot",
                    translator.lastEngine,
                )
            }

            // A second string must also translate (session reuse, not a one-shot).
            val second = translator.translateEnglishToArabic("The sea is dark.")
            assertTrue("second bundled translation is blank", second.isNotBlank())
            assertTrue(
                "second translation carries no Arabic script",
                second.any { it.code in 0x0600..0x06FF },
            )
        } finally {
            translator.close()
        }
    }

    private fun assertNoInternet(context: Context) {
        val airplane = Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
        assertTrue("CI emulator must have airplane mode enabled", airplane)
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val internetCapable = connectivity.allNetworks.count { network ->
            connectivity.getNetworkCapabilities(network)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        }
        assertTrue("CI emulator still exposes $internetCapable internet-capable networks", internetCapable == 0)
        assertFalse(
            "active network still has internet capability",
            connectivity.getNetworkCapabilities(connectivity.activeNetwork)
                ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true,
        )
    }
}
