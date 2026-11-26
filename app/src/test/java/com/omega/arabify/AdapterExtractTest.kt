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
            assertEquals("UNIT_FIXTURE", summary.evidenceLevel)
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
}
