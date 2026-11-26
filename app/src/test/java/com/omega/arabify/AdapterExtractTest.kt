package com.omega.arabify

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase B step 1: wrapper extract() delegates to the wrapped localizers and
 * exposes the native report through the sealed AdapterLocalizationOutput.
 */
class AdapterExtractTest {

    private fun writeApk(apk: File, entries: Map<String, ByteArray>) {
        ZipOutputStream(apk.outputStream().buffered()).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
        }
    }

    private fun readEntry(apk: File, name: String): ByteArray? =
        ZipFile(apk).use { zip -> zip.getEntry(name)?.let { zip.getInputStream(it).readBytes() } }

    private fun ref(apk: File): ArtifactRef =
        ArtifactRef(id = apk.name, displayName = apk.name, uri = apk.absolutePath, kind = ArtifactKind.APK, sizeBytes = apk.length())

    private fun deps(dir: File): AdapterRuntimeDeps {
        val assets = File("src/main/assets")
        return AdapterRuntimeDeps(
            stagingDir = dir,
            fontBytes = { f -> File(assets, f.assetPath).readBytes() },
            translate = { text -> "العربية ${text.length}" },
        )
    }

    private fun slotApk(dir: File): File {
        val apk = File(dir, "slot-game.apk")
        writeApk(apk, mapOf(
            "classes.dex" to byteArrayOf(100, 101, 120, 10),
            "assets/jsons/translations/English.properties" to "New Game = New Game\n".toByteArray(),
        ))
        return apk
    }

    private fun gettextApk(dir: File): File {
        val apk = File(dir, "po-game.apk")
        val pot = """msgid ""
msgstr ""
"Content-Type: text/plain; charset=UTF-8\n"

#: core/src/Menu.java:1
msgid "New Game"
msgstr ""

#: core/src/Menu.java:3
msgctxt "menu"
msgid "Quit"
msgstr ""
"""
        val languagesXml = """<Languages>
    <Language id="en" name="English" fontSet="xolonium-kwajong"/>
    <Language id="de" name="Deutsch" fontSet="xolonium-kwajong"/>

    <FontSet id="xolonium-kwajong"
        defaultFontName="Xolonium-Regular.ttf"
        defaultBoldFontName="Xolonium-Bold.ttf"
        defaultFontSize="24"
        hudFontName="Xolonium-Regular.ttf"
    />
</Languages>
"""
        writeApk(apk, mapOf(
            "classes.dex" to byteArrayOf(100, 101, 120, 10),
            "assets/po/messages.pot" to pot.toByteArray(),
            "assets/po/de.po" to "msgid \"\"\nmsgstr \"\"\n".toByteArray(),
            "assets/ui/languages.xml" to languagesXml.toByteArray(),
            "assets/fonts/Xolonium-Regular.ttf" to TinyFont.build("Xolonium"),
            "assets/fonts/Xolonium-Bold.ttf" to TinyFont.build("Xolonium Bold"),
        ))
        return apk
    }

    @Test
    fun slotExtractDelegatesAndCarriesNativeReport() {
        runBlocking {
            val dir = createTempDir()
            val adapter = LibGdxLocaleSlotAdapter(deps(dir))
            val summary = adapter.extract(ref(slotApk(dir)), ResourceBudget())
            assertEquals("adapter.libgdx.localeslot", summary.adapterId)
            assertEquals(AdapterOperation.EXTRACT, summary.operation)
            assertEquals(1, summary.succeeded)
            // Slot extract is emulator-verified via DispatchPathInstrumentedTest.
            assertEquals("EMULATOR_CI", summary.evidenceLevel)
            val out = adapter.lastLocalizationOutput
            assertNotNull(out)
            val slotText = readEntry(out!!.outputApk, out.report.addedEntry)!!.toString(Charsets.UTF_8)
            assertTrue("slot output is pre-shaped Arabic", slotText.any { it.code in 0xFE70..0xFEFF })
            // Original English file untouched by the slot strategy.
            assertEquals(
                "New Game = New Game\n",
                readEntry(out.outputApk, "assets/jsons/translations/English.properties")!!.toString(Charsets.UTF_8),
            )
            dir.deleteRecursively()
            Unit
        }
    }

    @Test
    fun gettextExtractDelegatesAndCarriesNativeReport() {
        runBlocking {
            val dir = createTempDir()
            val adapter = GettextPoAdapter(deps(dir))
            val summary = adapter.extract(ref(gettextApk(dir)), ResourceBudget())
            assertEquals("adapter.gettext.po", summary.adapterId)
            assertTrue(summary.succeeded >= 1)
            val out = adapter.lastLocalizationOutput
            assertNotNull(out)
            val arPo = readEntry(out!!.outputApk, out.report.addedEntry)!!.toString(Charsets.UTF_8)
            assertTrue("ar.po is pre-shaped Arabic", arPo.any { it.code in 0xFE70..0xFEFF })
            // Existing languages untouched.
            assertTrue(readEntry(out.outputApk, "assets/po/de.po")!!.isNotEmpty())
            dir.deleteRecursively()
            Unit
        }
    }

    @Test
    fun extractWithoutRuntimeDepsFailsHonestly() {
        runBlocking {
            val dir = createTempDir()
            val error = assertThrows(UnsupportedOperationException::class.java) {
                runBlocking { LibGdxLocaleSlotAdapter().extract(ref(slotApk(dir)), ResourceBudget()) }
            }
            assertTrue(error.message!!.contains("runtime deps"))
            dir.deleteRecursively()
            Unit
        }
    }

    @Test
    fun dispatchWithRuntimeRegistryExecutesRealSlotExtraction() {
        runBlocking {
            val dir = createTempDir()
            val registry = ArabifyAdapters.runtimeRegistry(deps(dir))
            val result = PlannerDispatch(registry).dispatch(ref(slotApk(dir)), ResourceBudget())
            assertEquals("adapter.libgdx.localeslot", result.adapterId)
            assertEquals(listOf("adapter.libgdx.localeslot"), result.attempted)
            val adapter = registry.find("adapter.libgdx.localeslot") as LibGdxLocaleSlotAdapter
            assertTrue(adapter.lastLocalizationOutput!!.outputApk.isFile)
            dir.deleteRecursively()
            Unit
        }
    }

    @Test
    fun dispatchWithRuntimeRegistrySkipsUnmatchedSlotAndRunsGettext() {
        runBlocking {
            val dir = createTempDir()
            val registry = ArabifyAdapters.runtimeRegistry(deps(dir))
            val result = PlannerDispatch(registry).dispatch(ref(gettextApk(dir)), ResourceBudget())
            // The slot adapter does not match a gettext-only fixture, so the
            // planner never selects it; gettext runs first and only.
            assertEquals("adapter.gettext.po", result.adapterId)
            assertEquals(listOf("adapter.gettext.po"), result.attempted)
            dir.deleteRecursively()
            Unit
        }
    }
    @Test
    fun dispatchRecordsFontGateFailureWhenGettextCannotRegisterArabic() {
        runBlocking {
            // Gettext fixture WITHOUT a usable FontSet: the localizer's font
            // gate (e0eb75f) refuses to emit a raw unshaped ar.po, and the
            // dispatch must record that failure honestly instead of masking
            // it. No other wrapper declares EXTRACT yet, so dispatch ends in
            // LocalizationDispatchException carrying the gate's reason.
            val dir = createTempDir()
            val apk = File(dir, "po-nofonts.apk")
            val pot = "msgid \"\"\nmsgstr \"\"\n\"Content-Type: text/plain; charset=UTF-8\\n\"\n\nmsgid \"Play\"\nmsgstr \"\"\n"
            writeApk(apk, mapOf(
                "classes.dex" to byteArrayOf(100, 101, 120, 10),
                "assets/po/messages.pot" to pot.toByteArray(),
                "assets/ui/languages.xml" to "<Languages/>".toByteArray(),
            ))
            val registry = ArabifyAdapters.runtimeRegistry(deps(dir))
            val error = assertThrows(LocalizationDispatchException::class.java) {
                runBlocking { PlannerDispatch(registry).dispatch(ref(apk), ResourceBudget()) }
            }
            assertEquals(listOf("adapter.gettext.po"), error.attempted)
            assertTrue(error.failures.single().contains("FontSet") || error.failures.single().contains("خط"))
            dir.deleteRecursively()
            Unit
        }
    }
    // ---------- Phase B step 1b: resources + in-place wrapper extraction ----------

    private fun assetsOnlyApk(dir: File): File {
        val apk = File(dir, "assets-game.apk")
        writeApk(apk, mapOf(
            "classes.dex" to byteArrayOf(100, 101, 120, 10),
            // Non-glossary strings so the fake translate callback (not ArabicUiGlossary) produces the Arabic.
            "assets/data/strings.json" to "{\"title\": \"Sprocket Valley\", \"hint\": \"Zingle hop\"}".toByteArray(),
        ))
        return apk
    }

    @Test
    fun resourcesExtractNeedsRuntimeDeps() {
        runBlocking {
            val dir = createTempDir()
            val apk = File(dir, "r.apk")
            writeApk(apk, mapOf("resources.arsc" to byteArrayOf(1, 2, 3)))
            val error = assertThrows(UnsupportedOperationException::class.java) {
                runBlocking { AndroidResourcesAdapter().extract(ref(apk), ResourceBudget()) }
            }
            assertTrue(error.message!!.contains("runtime deps"))
            dir.deleteRecursively()
            Unit
        }
    }

    @Test
    fun assetsInPlaceExtractNeedsRuntimeDeps() {
        runBlocking {
            val dir = createTempDir()
            val apk = assetsOnlyApk(dir)
            val error = assertThrows(UnsupportedOperationException::class.java) {
                runBlocking { AndroidAssetsInPlaceAdapter().extract(ref(apk), ResourceBudget()) }
            }
            assertTrue(error.message!!.contains("runtime deps"))
            dir.deleteRecursively()
            Unit
        }
    }

    @Test
    fun assetsInPlaceExtractTranslatesJsonAssetAndKeepsOriginalUntouched() {
        runBlocking {
            val dir = createTempDir()
            val apk = assetsOnlyApk(dir)
            val original = readEntry(apk, "assets/data/strings.json")!!
            val adapter = AndroidAssetsInPlaceAdapter(deps(dir))
            val summary = adapter.extract(ref(apk), ResourceBudget())
            val output = adapter.lastLocalizationOutput
            assertNotNull(output)
            assertTrue(summary.succeeded >= 1)
            val rewritten = readEntry(output!!.outputApk, "assets/data/strings.json")
            assertNotNull(rewritten)
            assertTrue(String(rewritten!!).contains("\u0627\u0644\u0639\u0631\u0628\u064A\u0629"))
            // The unsigned marker must be surfaced honestly, not hidden.
            assertTrue(summary.warnings.any { it.contains("unsigned") })
            assertTrue(original.contentEquals(readEntry(apk, "assets/data/strings.json")!!))
            dir.deleteRecursively()
            Unit
        }
    }

    @Test
    fun dispatchFallsBackFromInPlaceToResourcesAndRecordsBothFailures() {
        runBlocking {
            // Text asset with NO translatable strings -> in-place extract
            // throws (requireTranslations); the invalid arsc then makes the
            // resources extract fail too. Dispatch must attempt in planner
            // order (in-place confidence 60 before resources 40) and report
            // both failures honestly.
            val dir = createTempDir()
            val apk = File(dir, "unlocalizable.apk")
            writeApk(apk, mapOf(
                "classes.dex" to byteArrayOf(100, 101, 120, 10),
                "resources.arsc" to "not-a-real-table".toByteArray(),
                "assets/data/numbers.txt" to "12345".toByteArray(),
            ))
            val error = assertThrows(LocalizationDispatchException::class.java) {
                runBlocking { PlannerDispatch(ArabifyAdapters.runtimeRegistry(deps(dir))).dispatch(ref(apk), ResourceBudget()) }
            }
            assertEquals(
                listOf("adapter.android.assets.inplace", "adapter.android.resources"),
                error.attempted,
            )
            assertEquals(2, error.failures.size)
            dir.deleteRecursively()
            Unit
        }
    }

    @Test
    fun dispatchPrefersSlotOverInPlaceForSlotFixture() {
        runBlocking {
            // A slot fixture that ALSO has a supported text asset must still
            // dispatch through the slot wrapper first (confidence 90 vs 60)
            // and never touch the fallback.
            val dir = createTempDir()
            val apk = File(dir, "slot-with-assets.apk")
            writeApk(apk, mapOf(
                "classes.dex" to byteArrayOf(100, 101, 120, 10),
                "assets/jsons/translations/English.properties" to "New Game = New Game\n".toByteArray(),
                "assets/data/strings.json" to "{\"title\": \"New Game\"}".toByteArray(),
            ))
            val result = PlannerDispatch(ArabifyAdapters.runtimeRegistry(deps(dir))).dispatch(ref(apk), ResourceBudget())
            assertEquals("adapter.libgdx.localeslot", result.adapterId)
            assertEquals(listOf("adapter.libgdx.localeslot"), result.attempted)
            dir.deleteRecursively()
            Unit
        }
    }
    // ---------- Phase B step 2: chain registry + progress wiring ----------

    @Test
    fun runtimeChainRegistryMirrorsTheManualAssetChain() {
        val ids = ArabifyAdapters.runtimeChainRegistry(deps(createTempDir())).all().map { it.descriptor.id }
        assertEquals(
            listOf(
                "adapter.libgdx.localeslot",
                "adapter.gettext.po",
                "adapter.android.assets.inplace",
            ),
            ids,
        )
    }

    @Test
    fun assetsInPlaceExtractReportsProgressThroughDeps() {
        runBlocking {
            val dir = createTempDir()
            val apk = assetsOnlyApk(dir)
            val progress = mutableListOf<Pair<Int, Int>>()
            val runtimeDeps = AdapterRuntimeDeps(
                stagingDir = dir,
                fontBytes = { f -> File("src/main/assets", f.assetPath).readBytes() },
                translate = { text -> "العربية ${text.length}" },
                onProgress = { done, total -> progress += done to total },
            )
            val adapter = AndroidAssetsInPlaceAdapter(runtimeDeps)
            adapter.extract(ref(apk), ResourceBudget())
            assertTrue("in-place extraction must report progress", progress.isNotEmpty())
            assertEquals(progress.last().first, progress.last().second)
            dir.deleteRecursively()
            Unit
        }
    }
}
