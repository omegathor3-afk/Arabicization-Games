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

class SplitApkSetLocalizerTest {

    private val fakeArabic: suspend (String) -> String = { text ->
        when (text) {
            "Start Game" -> "ابدأ اللعبة"
            "Options" -> "الخيارات"
            "Daily Reward" -> "مكافأة يومية"
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

    private fun buildSet(dir: File): List<File> {
        val base = File(dir, "base-src.apk")
        writeApk(
            base,
            mapOf(
                "AndroidManifest.xml" to byteArrayOf(3, 0, 8, 0),
                "classes.dex" to byteArrayOf(100, 101, 120, 10),
                "assets/strings.json" to """{"menu":"Start Game","sub":"Options"}""".toByteArray(),
                "META-INF/OLD.SF" to "stale".toByteArray()
            )
        )
        val splitText = File(dir, "split-text-src.apk")
        writeApk(
            splitText,
            mapOf(
                "AndroidManifest.xml" to byteArrayOf(3, 0, 8, 1),
                "assets/notes.txt" to "Daily Reward".toByteArray()
            )
        )
        val splitBinary = File(dir, "split-binary-src.apk")
        writeApk(
            splitBinary,
            mapOf(
                "AndroidManifest.xml" to byteArrayOf(3, 0, 8, 2),
                "res/raw/data.bin" to byteArrayOf(0, 1, 2, 3, 4)
            )
        )
        return listOf(base, splitText, splitBinary)
    }

    @Test
    fun everyMemberIsRebuiltAndOnlyTranslatableOnesChange() {
        runBlocking {
            val dir = createTempDir()
            val outDir = File(dir, "out")
            val setReport = SplitApkSetLocalizer.localizeSet(
                buildSet(dir), outDir, { true }, tx = fakeArabic
            )
            assertEquals(3, setReport.members.size)
            assertTrue(setReport.anyTransformed)
            assertEquals(3, setReport.stringsTranslated)

            val base = File(outDir, "base.apk")
            val splitText = File(outDir, "split_01.apk")
            val splitBinary = File(outDir, "split_02.apk")
            assertTrue(base.isFile && splitText.isFile && splitBinary.isFile)

            val baseJson = String(readEntry(base, "assets/strings.json")!!)
            assertTrue(baseJson.contains("ابدأ اللعبة"))
            assertTrue(baseJson.contains("menu"))
            assertTrue(readEntry(base, "META-INF/OLD.SF") == null)

            val notes = String(readEntry(splitText, "assets/notes.txt")!!)
            assertEquals("مكافأة يومية", notes)

            assertTrue(readEntry(splitBinary, "res/raw/data.bin")!!.contentEquals(byteArrayOf(0, 1, 2, 3, 4)))
            assertFalse(setReport.members[2].transformed)
            assertTrue(setReport.members[0].transformed && setReport.members[1].transformed)
            dir.deleteRecursively()
        }
    }

    @Test
    fun setWithoutAnyTranslatableTextFails() {
        runBlocking {
            val dir = createTempDir()
            val binaryOnly = File(dir, "only.apk")
            writeApk(binaryOnly, mapOf("classes.dex" to byteArrayOf(1, 2, 3)))
            var failed = false
            try {
                SplitApkSetLocalizer.localizeSet(
                    listOf(binaryOnly), File(dir, "out"), { true }, tx = fakeArabic
                )
            } catch (expected: IllegalArgumentException) {
                failed = true
            }
            assertTrue(failed)
            dir.deleteRecursively()
        }
    }
}
