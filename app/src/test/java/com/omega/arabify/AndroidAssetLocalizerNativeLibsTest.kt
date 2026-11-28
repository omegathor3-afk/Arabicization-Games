package com.omega.arabify

import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Native-library rebuild behavior after the extractNativeLibs guard was
 * removed (real-game evidence: Car Runner, a Unity 2019 Mono player, carries
 * DEFLATED .so entries and an unset flag; the blanket guard refused a
 * perfectly installable shape). DEFLATED libs keep their semantics, STORED
 * libs are re-aligned by ApkZipEntryPolicy.
 */
class AndroidAssetLocalizerNativeLibsTest {

    private fun storedEntry(name: String, bytes: ByteArray): ZipEntry {
        val crc = CRC32().apply { update(bytes) }.value
        return ZipEntry(name).apply {
            method = ZipEntry.STORED
            size = bytes.size.toLong()
            compressedSize = bytes.size.toLong()
            this.crc = crc
        }
    }

    private fun writeApk(file: File, nativeStored: Boolean) {
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("assets/strings.json"))
            zip.write("""{"menu":"Start Game"}""".toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            val libBytes = ByteArray(8_192) { (it % 251).toByte() }
            val entry = if (nativeStored) storedEntry("lib/armeabi-v7a/libgame.so", libBytes)
                else ZipEntry("lib/armeabi-v7a/libgame.so")
            zip.putNextEntry(entry)
            zip.write(libBytes)
            zip.closeEntry()
        }
    }

    @Test
    fun deflatedNativeLibApkRebuildsWithoutExtractionFlag() = runBlocking {
        val input = File.createTempFile("arabify-deflated-lib-", ".apk")
        val output = File.createTempFile("arabify-deflated-lib-out-", ".apk").apply { delete() }
        writeApk(input, nativeStored = false)
        try {
            val report = AndroidAssetLocalizer.transformToUnsignedApk(input, output) {
                "ابدأ اللعبة"
            }
            assertEquals(1, report.stringsTranslated)
            ZipFile(output).use { zip ->
                val lib = zip.getEntry("lib/armeabi-v7a/libgame.so")
                assertEquals("deflated lib must stay deflated", ZipEntry.DEFLATED, lib.method)
                assertEquals(8_192, zip.getInputStream(lib).use { it.readBytes() }.size)
                val strings = zip.getInputStream(zip.getEntry("assets/strings.json")).use {
                    it.readBytes().toString(Charsets.UTF_8)
                }
                assertTrue(strings.contains("ابدأ اللعبة"))
            }
        } finally {
            input.delete()
            output.delete()
        }
    }

    @Test
    fun storedNativeLibStaysStoredAndByteIdenticalAfterRebuild() = runBlocking {
        val input = File.createTempFile("arabify-stored-lib-", ".apk")
        val output = File.createTempFile("arabify-stored-lib-out-", ".apk").apply { delete() }
        writeApk(input, nativeStored = true)
        try {
            val report = AndroidAssetLocalizer.transformToUnsignedApk(input, output) {
                "ابدأ اللعبة"
            }
            assertEquals(1, report.stringsTranslated)
            ZipFile(output).use { zip ->
                val lib = zip.getEntry("lib/armeabi-v7a/libgame.so")
                assertEquals("stored lib must stay stored", ZipEntry.STORED, lib.method)
                val bytes = zip.getInputStream(lib).use { it.readBytes() }
                assertEquals(8_192, bytes.size)
                assertTrue("lib bytes must survive the rebuild byte-identical",
                    bytes.indices.all { bytes[it] == (it % 251).toByte() })
            }
        } finally {
            input.delete()
            output.delete()
        }
    }
}
