package com.omega.arabify

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GettextPoLocalizerTest {

    private fun writeApk(file: File, entries: Map<String, ByteArray>) {
        ZipOutputStream(file.outputStream().buffered()).use { out ->
            for ((name, bytes) in entries) {
                out.putNextEntry(ZipEntry(name))
                out.write(bytes)
                out.closeEntry()
            }
        }
    }

    private fun readEntry(apk: File, name: String): ByteArray? =
        ZipFile(apk).use { zip -> zip.getEntry(name)?.let { zip.getInputStream(it).use { s -> s.readBytes() } } }

    private val languagesXml = """<Languages>
    <Language id="en" name="English" fontSet="xolonium-kwajong"/>
    <Language id="de" name="Deutsch" fontSet="xolonium-kwajong"/>

    <FontSet id="xolonium-kwajong"
        defaultFontName="Xolonium-Regular.ttf"
        defaultBoldFontName="Xolonium-Bold.ttf"
        defaultFontSize="24"

        titleFontName="Kwajong-Italic.otf"
        titleFontSize="36"

        hudFontName="Xolonium-Regular.ttf"
    />
</Languages>
"""

    private val pot = """msgid ""
msgstr ""
"Content-Type: text/plain; charset=UTF-8\n"

#: core/src/Menu.java:1
msgid "New Game"
msgstr ""

#: core/src/Reward.java:2
msgid "Hit one vehicle"
msgid_plural "Hit %# vehicles"
msgstr[0] ""
msgstr[1] ""

#: core/src/Menu.java:3
msgctxt "menu"
msgid "Quit"
msgstr ""
"""

    private fun buildGameApk(
        dir: File,
        potText: String = pot,
        languageXmlText: String = languagesXml,
    ): File {
        val apk = File(dir, "pw.apk")
        writeApk(apk, linkedMapOf(
            "classes.dex" to byteArrayOf(100, 101, 120, 10),
            "assets/po/messages.pot" to potText.toByteArray(),
            "assets/po/de.po" to "msgid \"\"\nmsgstr \"\"\n".toByteArray(),
            "assets/ui/languages.xml" to languageXmlText.toByteArray(),
            "assets/fonts/Xolonium-Regular.ttf" to TinyFont.build("Xolonium"),
            "assets/fonts/Xolonium-Bold.ttf" to TinyFont.build("Xolonium Bold"),
        ))
        return apk
    }

    private fun fakeArabic(text: String) = "العربية ${text.length}"

    @Test
    fun addsArPoRegistersLanguageAndMergesFonts() {
        runBlocking {
            val dir = createTempDir()
            val input = buildGameApk(dir)
            val output = File(dir, "out.apk")
            val assets = File("src/main/assets")
            val fonts = GettextPoLocalizer.FontInjection { f -> File(assets, f.assetPath).readBytes() }

            val report = GettextPoLocalizer.addArabicGettextSlot(input, output, fonts) { fakeArabic(it) }

            assertEquals("assets/po/ar.po", report.addedEntry)
            assertEquals(3, report.stringsTranslated) // one per entry
            assertTrue(report.fontsMerged.isNotEmpty())

            val arPo = String(readEntry(output, "assets/po/ar.po")!!, Charsets.UTF_8)
            assertTrue(arPo.contains("\"Language: ar\\n\""))
            assertTrue(arPo.contains("nplurals=6"))
            assertTrue(arPo.contains("msgid \"New Game\""))
            assertTrue(arPo.contains("msgid_plural \"Hit %# vehicles\""))
            assertTrue(arPo.contains("msgstr[5]"))
            assertTrue(arPo.contains("msgctxt \"menu\""))
            // pre-shaped: presentation forms, placeholders intact
            assertTrue(arPo.any { it.code in 0xFE70..0xFEFF })
            assertTrue(arPo.contains("%#"))

            val xml = String(readEntry(output, "assets/ui/languages.xml")!!, Charsets.UTF_8)
            assertTrue(xml.contains("<Language id=\"ar\" name=\"العربية\" fontSet=\"arabic\"/>"))
            assertTrue(xml.contains("<FontSet id=\"arabic\""))
            assertTrue(xml.contains("Xolonium-Regular-Ar.ttf"))

            val mergedFont = readEntry(output, "assets/fonts/Xolonium-Regular-Ar.ttf")!!
            val profile = FontAnalyzer.analyze(mergedFont)
            assertTrue(profile.hasArabicCore)
            assertTrue(profile.supports(0x41)) // Latin kept

            // originals untouched
            assertTrue(readEntry(output, "assets/fonts/Xolonium-Regular.ttf")!!
                .contentEquals(readEntry(input, "assets/fonts/Xolonium-Regular.ttf")!!))
            assertTrue(readEntry(output, "assets/po/de.po")!!.contentEquals(readEntry(input, "assets/po/de.po")!!))
            dir.deleteRecursively()
        }
    }

    @Test
    fun refusesWhenPotMissing() {
        runBlocking {
            val dir = createTempDir()
            val apk = File(dir, "no-pot.apk")
            writeApk(apk, mapOf("classes.dex" to byteArrayOf(1)))
            val assets = File("src/main/assets")
            val fonts = GettextPoLocalizer.FontInjection { f -> File(assets, f.assetPath).readBytes() }
            try {
                GettextPoLocalizer.addArabicGettextSlot(apk, File(dir, "o.apk"), fonts) { fakeArabic(it) }
                throw AssertionError("expected refusal")
            } catch (expected: IllegalArgumentException) {
                // honest refusal
            }
            dir.deleteRecursively()
        }
    }

    @Test
    fun keepsUntranslatableEntriesAsSource() {
        runBlocking {
            val dir = createTempDir()
            // Use a msgid outside the reviewed glossary so the empty translator
            // really leaves it untranslatable (glossary hits are intentional).
            val untranslatablePot = """msgid ""
msgstr ""
"Content-Type: text/plain; charset=UTF-8\n"

msgid "Zzq Blorf"
msgstr ""
"""
            val input = buildGameApk(dir, untranslatablePot)
            val output = File(dir, "out2.apk")
            val assets = File("src/main/assets")
            val fonts = GettextPoLocalizer.FontInjection { f -> File(assets, f.assetPath).readBytes() }
            val report = GettextPoLocalizer.addArabicGettextSlot(input, output, fonts) { "" } // nothing translatable
            val arPo = String(readEntry(output, "assets/po/ar.po")!!, Charsets.UTF_8)
            assertTrue(arPo.contains("msgstr \"Zzq Blorf\"")) // falls back to source text
            assertEquals(0, report.stringsTranslated)
            assertFalse(report.addedEntry.isEmpty())
            dir.deleteRecursively()
        }
    }

    @Test
    fun gettextUsesReviewedGlossaryAndProtectsFormatTokens() {
        runBlocking {
            val customPot = """msgid ""
msgstr ""
"Content-Type: text/plain; charset=UTF-8\n"

msgid "Quit"
msgstr ""

msgid "Collect %s"
msgstr ""

msgid "Hit one vehicle"
msgid_plural "Hit %# vehicles"
msgstr[0] ""
msgstr[1] ""
"""
            val dir = createTempDir()
            val input = buildGameApk(dir, customPot, "<Languages/>")
            val output = File(dir, "reviewed-out.apk")
            val fonts = GettextPoLocalizer.FontInjection {
                throw AssertionError("A missing FontSet must not request a replacement font.")
            }

            val report = GettextPoLocalizer.addArabicGettextSlot(input, output, fonts) { value ->
                val marker = Regex("__ARABIFY_TOKEN_\\d+__").find(value)?.value
                when {
                    value.startsWith("Collect") -> "اجمع" // Deliberately drops %s; must be rejected.
                    marker != null -> "اضرب $marker مركبات"
                    else -> "اضرب مركبة واحدة"
                }
            }

            val arPo = String(readEntry(output, "assets/po/ar.po")!!, Charsets.UTF_8)
            assertTrue(arPo.contains("msgid \"Quit\"\nmsgstr \"خروج\""))
            assertTrue(arPo.contains("msgid \"Collect %s\"\nmsgstr \"Collect %s\""))
            assertTrue(arPo.contains("msgid_plural \"Hit %# vehicles\"\nmsgstr[0] \"اضرب %# مركبات\""))
            assertFalse(arPo.contains("__ARABIFY_TOKEN_"))
            assertEquals(2, report.stringsTranslated)
            assertEquals(1, report.stringsSkipped)
            dir.deleteRecursively()
        }
    }

}
