package com.omega.arabify

import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class ApkZipEntryPolicyTest {
    @Test
    fun resourcesArscBecomesStoredAndFourByteAlignedAfterRepack() {
        val source = File.createTempFile("arabify-zip-source-", ".apk")
        val output = File.createTempFile("arabify-zip-output-", ".apk")
        val prefixBytes = byteArrayOf(1, 2, 3, 4, 5)
        val resourceBytes = ByteArray(257) { (it % 251).toByte() }
        val dialogueBytes = "A locked gate guards the old library.".toByteArray(Charsets.UTF_8)

        try {
            ZipOutputStream(source.outputStream()).use { zip ->
                putStored(zip, "first.bin", prefixBytes)
                zip.putNextEntry(ZipEntry("resources.arsc"))
                zip.write(resourceBytes)
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("assets/dialogue.txt"))
                zip.write(dialogueBytes)
                zip.closeEntry()
            }

            repack(source, output)

            ZipFile(output).use { zip ->
                val resources = requireNotNull(zip.getEntry("resources.arsc"))
                assertEquals(ZipEntry.STORED, resources.method)
                assertArrayEquals(resourceBytes, zip.getInputStream(resources).use { it.readBytes() })

                val dialogue = requireNotNull(zip.getEntry("assets/dialogue.txt"))
                assertEquals(ZipEntry.DEFLATED, dialogue.method)
                assertArrayEquals(dialogueBytes, zip.getInputStream(dialogue).use { it.readBytes() })
            }

            assertEquals(0L, localDataOffset(output, "resources.arsc") % 4L)
        } finally {
            source.delete()
            output.delete()
        }
    }

    @Test
    fun storedReplacementRecomputesSizeAndCrcAndRemainsAligned() {
        val source = File.createTempFile("arabify-zip-source-", ".apk")
        val output = File.createTempFile("arabify-zip-output-", ".apk")
        val original = "old".toByteArray(Charsets.UTF_8)
        val replacement = "نص عربي جديد".toByteArray(Charsets.UTF_8)

        try {
            ZipOutputStream(source.outputStream()).use { zip ->
                putStored(zip, "assets/dialogue.txt", original)
            }

            repack(source, output, mapOf("assets/dialogue.txt" to replacement))

            ZipFile(output).use { zip ->
                val entry = requireNotNull(zip.getEntry("assets/dialogue.txt"))
                assertEquals(ZipEntry.STORED, entry.method)
                assertEquals(replacement.size.toLong(), entry.size)
                assertEquals(crc32(replacement), entry.crc)
                assertArrayEquals(replacement, zip.getInputStream(entry).use { it.readBytes() })
            }
            assertEquals(0L, localDataOffset(output, "assets/dialogue.txt") % 4L)
        } finally {
            source.delete()
            output.delete()
        }
    }

    @Test
    fun existingSixteenKiBAlignmentIsPreservedInWrittenZip() {
        val output = File.createTempFile("arabify-zip-16k-", ".apk")
        val bytes = byteArrayOf(7, 8, 9)
        val entry = ZipEntry("lib/arm64-v8a/libsample.so").apply {
            method = ZipEntry.STORED
            size = bytes.size.toLong()
            compressedSize = size
            crc = crc32(bytes)
            extra = byteArrayOf(0x35.toByte(), 0xD9.toByte(), 0x02, 0x00, 0x00, 0x40)
        }

        try {
            writeSingleEntry(output, entry, bytes)
            assertEquals(0L, localDataOffset(output, entry.name) % 16_384L)
            ZipFile(output).use { zip ->
                val result = requireNotNull(zip.getEntry(entry.name))
                assertEquals(ZipEntry.STORED, result.method)
                assertArrayEquals(bytes, zip.getInputStream(result).use { it.readBytes() })
            }
        } finally {
            output.delete()
        }
    }

    @Test
    fun outOfRangeTimestampDoesNotInjectUnaccountedExtraBytes() {
        val output = File.createTempFile("arabify-zip-time-", ".apk")
        val bytes = byteArrayOf(1, 2, 3)
        val entry = ZipEntry("resources.arsc").apply {
            method = ZipEntry.STORED
            size = bytes.size.toLong()
            compressedSize = size
            crc = crc32(bytes)
            time = 0L
        }

        try {
            writeSingleEntry(output, entry, bytes)
            assertEquals(0L, localDataOffset(output, entry.name) % 4L)
            ZipFile(output).use { zip ->
                val result = requireNotNull(zip.getEntry(entry.name))
                assertEquals(ZipEntry.STORED, result.method)
                assertArrayEquals(bytes, zip.getInputStream(result).use { it.readBytes() })
            }
        } finally {
            output.delete()
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsTruncatedExtraFieldInsteadOfCopyingIt() {
        val entry = ZipEntry("resources.arsc").apply {
            method = ZipEntry.DEFLATED
            extra = byteArrayOf(0x34, 0x12, 0x05, 0x00, 0x01)
        }
        ApkZipEntryPolicy.create(entry, null, 0L)
    }

    private fun repack(
        source: File,
        destination: File,
        replacements: Map<String, ByteArray> = emptyMap()
    ) {
        ZipFile(source).use { input ->
            val counting = CountingOutputStream(destination.outputStream().buffered())
            ZipOutputStream(counting).use { output ->
                val entries = input.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val replacement = replacements[entry.name]
                    val target = ApkZipEntryPolicy.create(
                        entry,
                        replacement,
                        counting.bytesWritten
                    )
                    output.putNextEntry(target)
                    if (!entry.isDirectory) {
                        if (replacement != null) {
                            output.write(replacement)
                        } else {
                            input.getInputStream(entry).use { it.copyTo(output) }
                        }
                    }
                    output.closeEntry()
                }
            }
        }
    }

    private fun writeSingleEntry(file: File, entry: ZipEntry, bytes: ByteArray) {
        val counting = CountingOutputStream(file.outputStream().buffered())
        ZipOutputStream(counting).use { output ->
            val target = ApkZipEntryPolicy.create(entry, null, counting.bytesWritten)
            output.putNextEntry(target)
            output.write(bytes)
            output.closeEntry()
        }
    }

    private fun putStored(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        val entry = ZipEntry(name).apply {
            method = ZipEntry.STORED
            size = bytes.size.toLong()
            compressedSize = bytes.size.toLong()
            crc = crc32(bytes)
        }
        zip.putNextEntry(entry)
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun crc32(bytes: ByteArray): Long = CRC32().apply { update(bytes) }.value

    private fun localDataOffset(file: File, wantedName: String): Long {
        val bytes = file.readBytes()
        var offset = 0
        while (offset + 30 <= bytes.size && readUnsignedInt(bytes, offset) == 0x04034B50L) {
            val flags = unsignedShort(bytes, offset + 6)
            val compressedSize = readUnsignedInt(bytes, offset + 18)
            val nameLength = unsignedShort(bytes, offset + 26)
            val extraLength = unsignedShort(bytes, offset + 28)
            val nameStart = offset + 30
            val dataStart = nameStart + nameLength + extraLength
            val name = String(bytes, nameStart, nameLength, Charsets.UTF_8)
            if (name == wantedName) return dataStart.toLong()
            require((flags and 0x08) == 0) {
                "Test fixture must not place a data-descriptor entry before the target"
            }
            offset = Math.addExact(dataStart, compressedSize.toInt())
        }
        error("Local ZIP entry was not found")
    }

    private fun unsignedShort(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun readUnsignedInt(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xFFL) or
            ((bytes[offset + 1].toLong() and 0xFFL) shl 8) or
            ((bytes[offset + 2].toLong() and 0xFFL) shl 16) or
            ((bytes[offset + 3].toLong() and 0xFFL) shl 24)
}
