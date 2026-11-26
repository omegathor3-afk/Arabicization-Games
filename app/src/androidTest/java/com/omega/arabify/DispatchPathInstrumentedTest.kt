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
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

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

    /**
     * The same dispatch path on a synthetic gettext fixture (Pixel Wheels
     * convention: po/messages.pot + ui/languages.xml), built on-device with
     * the app's real Amiri TTF bytes so Android's own font stack parses the
     * fonts. The gettext wrapper must win (slot probe does not match), the
     * font gate must pass, ar.po must be pre-shaped, and the unsigned output
     * must survive host signing. Synthetic fixture: the cross-font glyph
     * MERGE path stays unit-fixture evidence; this run covers probe ->
     * extract -> translate -> shape -> ar.po -> languages.xml patch -> sign
     * on the Android runtime.
     */
    @Test
    fun plannerDispatchLocalizesGettextFixtureThroughTheGettextWrapper() {
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val stage = File(context.cacheDir, "dispatch-gettext-stage")
            stage.deleteRecursively()
            stage.mkdirs()
            val signed = File(context.cacheDir, "gettext-dispatch-ar.apk")
            signed.delete()

            // apksig requires a real binary AndroidManifest.xml to determine minSdk.
            // Reuse the installed target APK's manifest rather than making a fake
            // ZIP-only fixture that cannot pass the host signing step.
            val manifestBytes = ZipFile(context.applicationInfo.sourceDir).use { apk ->
                val manifest = apk.getEntry("AndroidManifest.xml")
                    ?: error("Installed ARABIFY APK is missing AndroidManifest.xml")
                apk.getInputStream(manifest).use { it.readBytes() }
            }
            val amiri = context.assets.open("fonts/Amiri-Regular.ttf").use { it.readBytes() }
            // Signing needs a binary manifest to derive minSdk from (apksig
            // throws MinSdkVersionException without one); borrow the host
            // app's real binary manifest - signing/verification never check
            // the package name, and the fixture is never installed.
            val hostApk = File(context.packageManager.getApplicationInfo(context.packageName, 0).sourceDir)
            val manifestBytes = ZipFile(hostApk).use { z ->
                z.getEntry("AndroidManifest.xml")?.let { z.getInputStream(it).readBytes() }
                    ?: error("host APK has no AndroidManifest.xml")
            }
            val pot = "msgid \"\"\nmsgstr \"\"\n\"Content-Type: text/plain; charset=UTF-8\\n\"\n\n" +
                "#: core/src/Menu.java:1\nmsgid \"New Game\"\nmsgstr \"\"\n\n" +
                "#: core/src/Reward.java:2\nmsgid \"Hit one vehicle\"\nmsgid_plural \"Hit %# vehicles\"\nmsgstr[0] \"\"\nmsgstr[1] \"\"\n\n" +
                "#: core/src/Menu.java:3\nmsgctxt \"menu\"\nmsgid \"Quit\"\nmsgstr \"\"\n"
            val languagesXml = "<Languages>\n" +
                "    <Language id=\"en\" name=\"English\" fontSet=\"xolonium-kwajong\"/>\n" +
                "    <FontSet id=\"xolonium-kwajong\"\n" +
                "        defaultFontName=\"Xolonium-Regular.ttf\"\n" +
                "        defaultBoldFontName=\"Xolonium-Bold.ttf\"\n" +
                "        defaultFontSize=\"24\"\n" +
                "        hudFontName=\"Xolonium-Regular.ttf\"\n" +
                "    />\n" +
                "</Languages>\n"
            val fixture = File(stage, "pw.apk")
            ZipOutputStream(fixture.outputStream().buffered()).use { zip ->
                linkedMapOf(
                    "AndroidManifest.xml" to manifestBytes,
                    "classes.dex" to byteArrayOf(100, 101, 120, 10),
                    "assets/po/messages.pot" to pot.toByteArray(),
                    "assets/po/de.po" to "msgid \"\"\nmsgstr \"\"\n".toByteArray(),
                    "assets/ui/languages.xml" to languagesXml.toByteArray(),
                    "assets/fonts/Xolonium-Regular.ttf" to amiri,
                    "assets/fonts/Xolonium-Bold.ttf" to amiri,
                ).forEach { (name, bytes) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }

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
                id = fixture.name,
                displayName = fixture.name,
                uri = fixture.absolutePath,
                kind = ArtifactKind.APK,
                sizeBytes = fixture.length(),
            )
            val dispatch = PlannerDispatch(registry).dispatch(artifact, ResourceBudget())

            assertEquals("adapter.gettext.po", dispatch.adapterId)
            assertEquals(listOf("adapter.gettext.po"), dispatch.attempted)

            val gettextAdapter = registry.find(dispatch.adapterId) as GettextPoAdapter
            val output = gettextAdapter.lastLocalizationOutput
            assertNotNull("gettext wrapper must expose its native report", output)
            val poReport = output!!.report
            assertEquals("assets/po/ar.po", poReport.addedEntry)
            assertEquals(3, poReport.stringsTranslated)

            val unsigned = output.outputApk
            assertTrue("unsigned dispatch output missing", unsigned.isFile && unsigned.length() > 0L)
            ZipFile(unsigned).use { zip ->
                val arEntry = zip.getEntry("assets/po/ar.po") ?: error("ar.po missing in dispatch output")
                val arPo = zip.getInputStream(arEntry).use { it.readBytes() }.toString(Charsets.UTF_8)
                assertTrue("ar.po must declare Arabic", arPo.contains("\"Language: ar\\n\""))
                assertTrue("ar.po must carry the Arabic plural rules", arPo.contains("nplurals=6"))
                assertTrue(
                    "ar.po must be pre-shaped to Presentation Forms-B for the bidi-less renderer",
                    arPo.any { it.code in 0xFE70..0xFEFF }
                )
            }

            ApkSigningService.signAndVerify(context, unsigned, signed)
            val verification = ApkVerifier.Builder(signed).build().verify()
            assertTrue("gettext dispatch output failed verification after host signing: ${verification.errors}", verification.isVerified)

            stage.deleteRecursively()
        }
    }
}
