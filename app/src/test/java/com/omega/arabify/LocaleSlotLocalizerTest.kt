package com.omega.arabify

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocaleSlotLocalizerTest {

    private val fakeArabic: suspend (String) -> String = { text ->
        when (text.trim()) {
            "New Game" -> "لعبة جديدة"
            "Load Game" -> "تحميل لعبة"
            "Quit" -> "خروج"
            else -> error("unexpected text: $text")
        }
    }

    private fun writeApk(file: File, entries: Map<String, ByteArray>) {
        ZipOutputStream(file.outputStream().buffered()).use { out ->
            entries.forEach { (name, bytes) ->
                out.putNextEntry(ZipEntry(name))
                out.write(bytes)
                out.closeEntry()
            }
        }
    }

    private fun readEntry(apk: File, name: String): ByteArray? =
        ZipFile(apk).use { zip ->
            zip.getEntry(name)?.let { zip.getInputStream(it).use { s -> s.readBytes() } }
        }

    private fun buildGameApk(dir: File, withArabic: Boolean = false): File {
        val apk = File(dir, "game.apk")
        val entries = linkedMapOf(
            "classes.dex" to byteArrayOf(100, 101, 120, 10),
            "assets/bundles/bundle.properties" to
                "menu.new = New Game\nmenu.load = Load Game\nmenu.quit = Quit\n".toByteArray(),
            "assets/bundles/bundle_de.properties" to
                "menu.new = Neues Spiel\n".toByteArray(),
            "META-INF/OLD.SF" to "stale".toByteArray()
        )
        if (withArabic) {
            entries["assets/bundles/bundle_ar.properties"] = "menu.new = لعبة جديدة\n".toByteArray()
        }
        writeApk(apk, entries)
        return apk
    }

    private fun buildTranslationFolderApk(dir: File, withArabic: Boolean = false): File {
        val apk = File(dir, "folder-game.apk")
        val entries = linkedMapOf(
            "classes.dex" to byteArrayOf(100, 101, 120, 10),
            "assets/jsons/translations/English.properties" to
                "New Game = New Game\nLoad Game = Load Game\nQuit = Quit\n".toByteArray(),
            "assets/jsons/translations/French.properties" to
                "New Game = Nouvelle partie\n".toByteArray()
        )
        if (withArabic) {
            entries["assets/jsons/translations/Arabic.properties"] = "New Game = لعبة جديدة\n".toByteArray()
        }
        writeApk(apk, entries)
        return apk
    }

    @Test
    fun addsArabicTranslationFileToTranslationFolderGames() {
        runBlocking {
            val dir = createTempDir()
            val input = buildTranslationFolderApk(dir)
            val output = File(dir, "folder-game-ar.apk")
            val report = LocaleSlotLocalizer.addArabicLocaleSlot(input, output, fakeArabic)

            assertEquals("assets/jsons/translations/English.properties", report.baseEntry)
            assertEquals("assets/jsons/translations/العربية.properties", report.addedEntry)
            assertEquals(3, report.stringsTranslated)
            val slot = readEntry(output, "assets/jsons/translations/العربية.properties")!!.toString(Charsets.UTF_8)
            assertTrue(slot.contains("New Game") && slot.contains("لعبة جديدة"))
            // The French sibling and the English base stay byte-identical.
            val originalFrench = readEntry(input, "assets/jsons/translations/French.properties")!!
            val copiedFrench = readEntry(output, "assets/jsons/translations/French.properties")!!
            assertTrue(originalFrench.contentEquals(copiedFrench))
            val originalEnglish = readEntry(input, "assets/jsons/translations/English.properties")!!
            val copiedEnglish = readEntry(output, "assets/jsons/translations/English.properties")!!
            assertTrue(originalEnglish.contentEquals(copiedEnglish))
        }
    }

    @Test
    fun refusesTranslationFolderGameThatAlreadyHasArabic() {
        runBlocking {
            val dir = createTempDir()
            val input = buildTranslationFolderApk(dir, withArabic = true)
            val output = File(dir, "folder-game-ar.apk")
            val failure = try {
                LocaleSlotLocalizer.addArabicLocaleSlot(input, output, fakeArabic)
                null
            } catch (failure: IllegalArgumentException) {
                failure
            }
            assertNotNull(failure)
            assertTrue(failure!!.message!!.contains("Arabic.properties"))
        }
    }

    @Test
    fun addsArabicSlotWithoutTouchingOtherEntries() {
        runBlocking {
            val dir = createTempDir()
            val input = buildGameApk(dir)
            val output = File(dir, "out.apk")
            val report = LocaleSlotLocalizer.addArabicLocaleSlot(input, output) { fakeArabic(it) }

            assertEquals("assets/bundles/bundle.properties", report.baseEntry)
            assertEquals("assets/bundles/bundle_ar.properties", report.addedEntry)
            assertEquals(3, report.stringsTranslated)

            val slot = String(readEntry(output, "assets/bundles/bundle_ar.properties")!!, Charsets.UTF_8)
            assertTrue(slot.contains("menu.new") && slot.contains("لعبة جديدة"))
            assertTrue(slot.contains("menu.load") && slot.contains("تحميل لعبة"))
            assertTrue(slot.contains("menu.quit") && slot.contains("خروج"))

            // Base bundle and the German sibling are byte-identical.
            assertTrue(
                readEntry(output, "assets/bundles/bundle.properties")!!
                    .contentEquals(readEntry(input, "assets/bundles/bundle.properties")!!)
            )
            assertTrue(
                readEntry(output, "assets/bundles/bundle_de.properties")!!
                    .contentEquals(readEntry(input, "assets/bundles/bundle_de.properties")!!)
            )
            // Stale signatures are stripped; the source APK is untouched.
            assertNull(readEntry(output, "META-INF/OLD.SF"))
            assertNotNull(readEntry(input, "META-INF/OLD.SF"))
            dir.deleteRecursively()
        }
    }

    @Test
    fun refusesWhenArabicSlotAlreadyExists() {
        runBlocking {
            val dir = createTempDir()
            val input = buildGameApk(dir, withArabic = true)
            var message: String? = null
            try {
                LocaleSlotLocalizer.addArabicLocaleSlot(input, File(dir, "out.apk")) { fakeArabic(it) }
            } catch (expected: IllegalArgumentException) {
                message = expected.message
            }
            assertTrue(message != null && message.contains("بالفعل"))
            dir.deleteRecursively()
        }
    }

    @Test
    fun refusesWhenNoLibGdxBundleExists() {
        runBlocking {
            val dir = createTempDir()
            val apk = File(dir, "plain.apk")
            writeApk(apk, mapOf("classes.dex" to byteArrayOf(1)))
            var message: String? = null
            try {
                LocaleSlotLocalizer.addArabicLocaleSlot(apk, File(dir, "out.apk")) { fakeArabic(it) }
            } catch (expected: IllegalArgumentException) {
                message = expected.message
            }
            assertTrue(message != null && message.contains("libGDX"))
            dir.deleteRecursively()
        }
    }

    /** Minimal sfnt (head + cmap format 4, Latin A-Z only): enough for FontAnalyzer. */
    private fun latinOnlyFont(familyName: String? = null): ByteArray {
        val head = ByteArray(54).also { it[18] = 0x03; it[19] = 0xE8.toByte() } // unitsPerEm 1000
        fun u16(v: Int) = byteArrayOf((v shr 8).toByte(), v.toByte())
        fun u32(v: Int) = u16(v shr 16) + u16(v and 0xFFFF)
        val seg = u16(4) + u16(2) + u16(1) + u16(0) +
            u16(0x5A) + u16(0xFFFF) + u16(0) +
            u16(0x41) + u16(0xFFFF) +
            u16(0) + u16(1) + u16(0) + u16(0)
        val sub = u16(4) + u16(6 + seg.size) + u16(0) + seg
        val cmap = u16(0) + u16(1) + u16(3) + u16(1) + u32(12) + sub
        val tables = mutableListOf("cmap" to cmap, "head" to head)
        if (familyName != null) {
            val encoded = familyName.toByteArray(Charsets.UTF_16BE)
            val name = ByteArray(18 + encoded.size)
            name[2] = 0
            name[3] = 1
            name[4] = 0
            name[5] = 18
            name[6] = 0
            name[7] = 3
            name[8] = 0
            name[9] = 1
            name[10] = 0x04
            name[11] = 0x09
            name[12] = 0
            name[13] = 1
            name[14] = (encoded.size ushr 8).toByte()
            name[15] = encoded.size.toByte()
            name[16] = 0
            name[17] = 0
            encoded.copyInto(name, 18)
            tables += "name" to name
        }
        var offset = 12 + 16 * tables.size
        var dir = u32(0x00010000) + u16(tables.size) + u16(0) + u16(0) + u16(0)
        var body = ByteArray(0)
        for ((tag, data) in tables) {
            dir += tag.toByteArray(Charsets.US_ASCII) + u32(0) + u32(offset) + u32(data.size)
            body += data
            offset += data.size
        }
        return dir + body
    }

    private fun buildLibGdxFontApk(dir: File, gameFont: ByteArray): File {
        val apk = File(dir, "gdx-font.apk")
        writeApk(apk, linkedMapOf(
            "classes.dex" to byteArrayOf(100, 101, 120, 10),
            "assets/bundles/bundle.properties" to "menu.new = New Game\nmenu.load = Load Game\nmenu.quit = Quit\n".toByteArray(),
            "assets/fonts/font.ttf" to gameFont,
            "assets/fonts/icon.ttf" to byteArrayOf(1, 2, 3)
        ))
        return apk
    }

    @Test
    fun injectsSelectedFontAndShapesSlotForLibGdxGameWithoutArabicGlyphs() {
        runBlocking {
            val dir = createTempDir()
            val gameFont = latinOnlyFont()
            val input = buildLibGdxFontApk(dir, gameFont)
            val output = File(dir, "out.apk")
            val assets = File("src/main/assets")
            val fonts = LocaleSlotLocalizer.FontInjection { f -> File(assets, f.assetPath).readBytes() }
            val report = LocaleSlotLocalizer.addArabicLocaleSlotWithFont(input, output, fonts) { fakeArabic(it) }

            assertEquals("assets/fonts/font.ttf", report.fontReplaced)
            assertTrue(report.fontReason!!.contains("OFL-1.1"))
            val replaced = readEntry(output, "assets/fonts/font.ttf")!!
            assertTrue(!replaced.contentEquals(gameFont))
            assertTrue(FontAnalyzer.analyze(replaced).hasArabicCore)
            // other fonts untouched
            assertTrue(readEntry(output, "assets/fonts/icon.ttf")!!.contentEquals(byteArrayOf(1, 2, 3)))
            // slot values are shaped: presentation forms present, no raw base letters
            val slot = String(readEntry(output, "assets/bundles/bundle_ar.properties")!!, Charsets.UTF_8)
            assertTrue(slot.any { it.code in 0xFE70..0xFEFF })
            assertTrue("raw letters in: $slot", slot.none { it.code in 0x0628..0x064A })
        }
    }

    @Test
    fun mergesArabicGlyphsIntoIconFontAndShapesSlot() {
        runBlocking {
            val dir = createTempDir()
            val gameFont = TinyFont.build("fontello")
            val input = buildLibGdxFontApk(dir, gameFont)
            val output = File(dir, "icon-font-merged-out.apk")
            val assets = File("src/main/assets")
            val fonts = LocaleSlotLocalizer.FontInjection { f -> File(assets, f.assetPath).readBytes() }

            val report = LocaleSlotLocalizer.addArabicLocaleSlotWithFont(input, output, fonts) { fakeArabic(it) }

            assertEquals("assets/fonts/font.ttf", report.fontReplaced)
            assertTrue(report.fontReason!!.contains("دمج"))
            val merged = readEntry(output, "assets/fonts/font.ttf")!!
            val profile = FontAnalyzer.analyze(merged)
            assertEquals("fontello", profile.familyName)
            assertTrue(profile.hasArabicCore)
            assertTrue("icons preserved", profile.supports(0xE800))
            assertTrue(profile.supports(0x41))
            val slot = String(readEntry(output, "assets/bundles/bundle_ar.properties")!!, Charsets.UTF_8)
            assertTrue("slot must be pre-shaped", slot.any { it.code in 0xFE70..0xFEFF })
            dir.deleteRecursively()
        }
    }

    @Test
    fun preservesRecognizedIconFontWhenMergeIsImpossible() {
        runBlocking {
            val dir = createTempDir()
            val gameFont = latinOnlyFont("fontello") // no glyf/loca tables: merge must honestly fail
            val input = buildLibGdxFontApk(dir, gameFont)
            val output = File(dir, "icon-font-out.apk")
            val assets = File("src/main/assets")
            val fonts = LocaleSlotLocalizer.FontInjection { f -> File(assets, f.assetPath).readBytes() }

            val report = LocaleSlotLocalizer.addArabicLocaleSlotWithFont(input, output, fonts) { fakeArabic(it) }

            assertNull(report.fontReplaced)
            assertTrue(report.fontReason!!.contains("خط الأيقونات"))
            assertTrue(readEntry(output, "assets/fonts/font.ttf")!!.contentEquals(gameFont))
            // renderer family has no bidi: the slot is pre-shaped even when no
            // font could be injected (glyphs were already unavailable).
            val slot = String(readEntry(output, "assets/bundles/bundle_ar.properties")!!, Charsets.UTF_8)
            assertTrue(slot.any { it.code in 0xFE70..0xFEFF })
            dir.deleteRecursively()
        }
    }

    @Test
    fun keepsAnArabicCapableGameFontButStillPreShapesForRendererWithoutBidi() {
        runBlocking {
            val dir = createTempDir()
            val assets = File("src/main/assets")
            val gameFont = File(assets, "fonts/catalog/amiri-regular.ttf").readBytes()
            val input = buildLibGdxFontApk(dir, gameFont)
            val output = File(dir, "native-arabic-font-out.apk")
            val fonts = LocaleSlotLocalizer.FontInjection { f -> File(assets, f.assetPath).readBytes() }

            val report = LocaleSlotLocalizer.addArabicLocaleSlotWithFont(input, output, fonts) { fakeArabic(it) }

            assertNull(report.fontReplaced)
            assertNull(report.fontReason)
            assertTrue(readEntry(output, "assets/fonts/font.ttf")!!.contentEquals(gameFont))
            // libGDX-family layout has no bidi: even with an Arabic-capable font,
            // logical order renders reversed on screen (verified on Unciv), so the
            // slot is still pre-shaped to visual-order presentation forms.
            val slot = String(readEntry(output, "assets/bundles/bundle_ar.properties")!!, Charsets.UTF_8)
            assertTrue(slot.any { it.code in 0xFE70..0xFEFF })
            assertTrue("raw letters in: $slot", slot.none { it.code in 0x0628..0x064A })
            dir.deleteRecursively()
        }
    }

    @Test
    fun plainSlotNeverTouchesFontsOrShapes() {
        runBlocking {
            val dir = createTempDir()
            val input = buildLibGdxFontApk(dir, latinOnlyFont())
            val output = File(dir, "out.apk")
            val report = LocaleSlotLocalizer.addArabicLocaleSlot(input, output) { fakeArabic(it) }
            assertNull(report.fontReplaced)
            assertTrue(readEntry(output, "assets/fonts/font.ttf")!!.contentEquals(readEntry(input, "assets/fonts/font.ttf")!!))
        }
    }
}
