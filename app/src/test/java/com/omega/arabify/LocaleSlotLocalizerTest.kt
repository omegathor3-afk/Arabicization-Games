package com.omega.arabify

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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
}
