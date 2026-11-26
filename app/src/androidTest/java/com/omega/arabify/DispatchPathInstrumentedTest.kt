package com.omega.arabify

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.android.apksig.ApkVerifier
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipFile

/**
 * On-device proof of the Phase B planner dispatch path: the exact registry
 * the MainActivity asset chain now runs (runtimeChainRegistry: slot ->
 * gettext -> in-place) is executed through PlannerDispatch against the real
 * Unciv APK installed by CI. The libGDX locale-slot wrapper must win FIRST
 * with no fallback, expose its native report through the sealed
 * AdapterLocalizationOutput, and its unsigned output must survive the host's
 * signing step - lifting the wrapper evidence from unit fixtures to an
 * emulator run. Uses the deterministic pseudo-Arabic stand-in (no ML Kit
 * download), so this proves dispatch mechanics, not translation quality;
 * real-translation evidence stays with UncivLocaleSlotInstrumentedTest.
 */
@RunWith(AndroidJUnit4::class)
class DispatchPathInstrumentedTest {

    @Test
    fun plannerDispatchLocalizesRealUncivThroughTheSlotWrapper() {
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val gameInfo = context.packageManager.getApplicationInfo("com.unciv.app", 0)
            val installedGameApk = File(gameInfo.sourceDir)
            assertTrue("The real Unciv APK is not readable", installedGameApk.isFile && installedGameApk.canRead())

            val stage = File(context.cacheDir, "dispatch-stage")
            stage.deleteRecursively()
            stage.mkdirs()
            val signed = File(context.cacheDir, "unciv-dispatch-ar.apk")
            signed.delete()

            val deps = AdapterRuntimeDeps(
                stagingDir = stage,
                fontBytes = { f -> context.assets.open(f.assetPath).use { it.readBytes() } },
                translate = { text ->
                    val trimmed = text.trim()
                    if (trimmed.length >= 3) "عر${trimmed.length}" else text
                },
            )
            val registry = ArabifyAdapters.runtimeChainRegistry(deps)
            val artifact = ArtifactRef(
                id = installedGameApk.name,
                displayName = "Unciv (installed)",
                uri = installedGameApk.absolutePath,
                kind = ArtifactKind.APK,
                sizeBytes = installedGameApk.length(),
            )
            val dispatch = PlannerDispatch(registry).dispatch(artifact, ResourceBudget())

            // The chain's first adapter must win on a slot game, with no fallback.
            assertEquals("adapter.libgdx.localeslot", dispatch.adapterId)
            assertEquals(listOf("adapter.libgdx.localeslot"), dispatch.attempted)

            val slotAdapter = registry.find(dispatch.adapterId) as LibGdxLocaleSlotAdapter
            val output = slotAdapter.lastLocalizationOutput
            assertNotNull("slot wrapper must expose its native report", output)
            val slotReport = output!!.report
            assertEquals("assets/jsons/translations/العربية.properties", slotReport.addedEntry)
            assertTrue(
                "Too few Unciv strings translated: ${slotReport.stringsTranslated}",
                slotReport.stringsTranslated > 1000
            )
            // The wrapper summary must reflect the native report, not replace it.
            assertEquals(slotReport.stringsTranslated, output.summary.succeeded)

            val unsigned = output.outputApk
            assertTrue("unsigned dispatch output missing", unsigned.isFile && unsigned.length() > 0L)
            ZipFile(unsigned).use { zip ->
                assertNotNull("Arabic slot missing in dispatch output", zip.getEntry(slotReport.addedEntry))
            }

            // Host behavior: the dispatch host signs the unsigned output.
            ApkSigningService.signAndVerify(context, unsigned, signed)
            val verification = ApkVerifier.Builder(signed).build().verify()
            assertTrue("Dispatch output failed verification after host signing: ${verification.errors}", verification.isVerified)

            stage.deleteRecursively()
        }
    }
}
