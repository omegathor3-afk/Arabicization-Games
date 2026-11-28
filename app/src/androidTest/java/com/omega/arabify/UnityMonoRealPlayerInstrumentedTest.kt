package com.omega.arabify

import android.content.ContentValues
import android.provider.MediaStore
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.android.apksig.ApkVerifier
import kotlinx.coroutines.runBlocking
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Unity Mono issue #6 evidence on the REAL Car Runner game (MIT-licensed,
 * pinned GitHub release v1.2.0, downloaded + SHA-256-checked by the CI
 * workflow). Car Runner is Unity 2019 Mono (libmonobdwgc-2.0.so, Managed
 * DLLs, assets/bin/Data layout).
 *
 * Honest scope: the game keeps its UI text in serialized scenes and ships
 * NO loose text files and NO StreamingAssets bundles, so the production
 * localizer must REFUSE with the honest no-translatable-text error rather
 * than emit a no-op APK. What this test proves on the real game:
 *  - UnityMonoPackageDetector recognizes a real Unity Mono player (not a
 *    synthetic fixture);
 *  - the production localizer's refusal is honest (right error, nothing
 *    silently produced);
 *  - the repackaging route (re-sign with ARABIFY's key, verified) works on a
 *    real Mono game - install/launch of that signed copy is asserted by the
 *    CI script leg that follows this test.
 *
 * Translating this game's actual UI requires serialized-scene MonoBehaviour
 * text rewriting (UnityEngine.UI.Text / TMP m_Text), which is the proposed
 * next coverage item in the handoff - not claimed here.
 */
@RunWith(AndroidJUnit4::class)
class UnityMonoRealPlayerInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val gamePackage = "com.PotneAEiI.CarRunner"

    @Test
    fun realMonoPlayerDetectedRefusedHonestlyAndRepackaged() {
        runBlocking {
            val pm = context.packageManager
            val original = File(pm.getApplicationInfo(gamePackage, 0).sourceDir)
            assertTrue("real Car Runner APK not readable", original.isFile && original.length() > 0)

            // Real-game Mono detection (no synthetic fixture involved).
            val kind = UnityMonoPackageDetector.detect(original)
            assertEquals("Car Runner must be detected as Unity Mono", UnityRuntimeKind.MONO, kind)
            Log.i("UnityMonoReal", "detector=$kind on the real game")

            // Honest refusal: no loose text, no StreamingAssets bundles, so
            // the production path must fail loudly with the no-text error and
            // produce nothing.
            val translator = OfflineArabicTranslator(context)
            try {
                val unsigned = File(context.cacheDir, "carrunner-unsigned.apk")
                val signed = File(context.cacheDir, "carrunner-signed.apk")
                unsigned.delete()
                signed.delete()
                try {
                    UnityMonoLocalizer.localize(context, original, unsigned, signed, translator)
                    fail("localizer must refuse a Mono game with no in-scope text instead of producing a no-op APK")
                } catch (expected: IllegalArgumentException) {
                    val message = expected.message ?: ""
                    assertTrue(
                        "refusal must be the honest no-translatable-text error, was: $message",
                        message.startsWith("لم يتم العثور على نصوص قابلة للترجمة"),
                    )
                    Log.i("UnityMonoReal", "honest refusal verified: $message")
                }
                assertTrue("a refused localization must not leave an output APK", !signed.isFile)
            } finally {
                translator.close()
            }

            // Repackaging route on a real Mono game: re-sign the untouched
            // original with ARABIFY's key and verify, exactly what the user
            // installs through the normal package installer.
            val resigned = File(context.cacheDir, "carrunner-resigned.apk")
            resigned.delete()
            ApkSigningService.signAndVerify(context, original, resigned)
            val verification = ApkVerifier.Builder(resigned).build().verify()
            assertTrue("re-signed Car Runner failed verification: ${verification.errors}", verification.isVerified)

            // Publish for the script's install/launch leg.
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "carrunner-resigned.apk")
                put(MediaStore.MediaColumns.MIME_TYPE, "application/vnd.android.package-archive")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/ARABIFY")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("Could not create Downloads entry for the re-signed Car Runner APK")
            try {
                context.contentResolver.openOutputStream(uri)?.use { stream ->
                    resigned.inputStream().use { input -> input.copyTo(stream) }
                } ?: error("Could not open Downloads output for the re-signed Car Runner APK")
                val ready = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                context.contentResolver.update(uri, ready, null, null)
            } catch (error: Throwable) {
                context.contentResolver.delete(uri, null, null)
                throw error
            }
            resigned.delete()
            Log.i("UnityMonoReal", "re-signed real Mono game published for the install/launch leg")
        }
    }
}
