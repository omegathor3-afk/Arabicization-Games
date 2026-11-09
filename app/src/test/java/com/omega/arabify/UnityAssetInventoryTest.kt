package com.omega.arabify

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class UnityAssetInventoryTest {
    @Test fun parsesLegacySerializedFileHeaderWithoutEditingPayload() {
        val bytes = ByteBuffer.allocate(32).order(ByteOrder.BIG_ENDIAN).apply {
            putInt(12)
            putInt(32)
            putInt(17)
            putInt(20)
            put(0)
            put(byteArrayOf(0, 0, 0))
            put(ByteArray(12) { it.toByte() })
        }.array()

        val header = UnitySerializedFileHeaderParser.parse(bytes.copyOf(20), bytes.size.toLong())
        assertNotNull(header)
        assertEquals(17, header!!.formatVersion)
        assertEquals("little", header.endian)
        assertEquals(20L, header.dataOffset)
        assertArrayEquals(bytes, UnitySerializedFileHeaderParser.preserveUnmodified(bytes))
    }

    @Test fun parsesExtendedVersion22HeaderAndKeepsUnknownFields() {
        val bytes = ByteBuffer.allocate(64).order(ByteOrder.BIG_ENDIAN).apply {
            putInt(0)
            putInt(0)
            putInt(22)
            putInt(0)
            put(1)
            put(byteArrayOf(0, 0, 0))
            putInt(16)
            putLong(64)
            putLong(48)
            putLong(0x1122334455667788L)
            put(ByteArray(16) { (it + 1).toByte() })
        }.array()

        val header = UnitySerializedFileHeaderParser.parse(bytes.copyOf(48), bytes.size.toLong())
        assertNotNull(header)
        assertEquals(22, header!!.formatVersion)
        assertEquals(64L, header.fileSize)
        assertEquals(48L, header.dataOffset)
        assertEquals("big", header.endian)
        assertArrayEquals(bytes, UnitySerializedFileHeaderParser.preserveUnmodified(bytes))
    }

    @Test fun rejectsTruncatedAndImpossibleSerializedHeaders() {
        assertNull(UnitySerializedFileHeaderParser.parse(byteArrayOf(1, 2, 3), 3))
        val impossible = ByteBuffer.allocate(20).order(ByteOrder.BIG_ENDIAN).apply {
            putInt(100)
            putInt(20)
            putInt(17)
            putInt(19)
            put(0)
        }.array()
        assertNull(UnitySerializedFileHeaderParser.parse(impossible, impossible.size.toLong()))
    }

    @Test fun recognizesUnityArchiveSignaturesWithoutUnpackingThem() {
        assertEquals("UnityFS", UnityArchiveHeaderParser.detect("UnityFS\u0000".toByteArray()))
        assertEquals("UnityRaw", UnityArchiveHeaderParser.detect("UnityRaw\u0000".toByteArray()))
        assertNull(UnityArchiveHeaderParser.detect("not-a-bundle".toByteArray()))
    }

    @Test fun inventorySeparatesTextCandidatesSerializedFilesBundlesAndExternalStreams() {
        val directory = createTempDir(prefix = "arabify-unity-inventory-")
        try {
            val apk = File(directory, "unity-inventory-fixture.apk")
            val serialized = ByteBuffer.allocate(32).order(ByteOrder.BIG_ENDIAN).apply {
                putInt(12); putInt(32); putInt(17); putInt(20)
                put(0); put(byteArrayOf(0, 0, 0)); put(ByteArray(12))
            }.array()
            ZipOutputStream(apk.outputStream()).use { zip ->
                fun entry(path: String, bytes: ByteArray) {
                    zip.putNextEntry(ZipEntry(path))
                    zip.write(bytes)
                    zip.closeEntry()
                }
                entry("assets/bin/Data/resources.assets", serialized)
                entry("assets/bin/Data/content.bundle", "UnityFS\u0000\u0001".toByteArray())
                entry("assets/bin/Data/sharedassets0.assets.resS", byteArrayOf(1, 2, 3, 4))
                entry("assets/bin/Data/StreamingAssets/dialogue.json", """{"line":"Hello"}""".toByteArray())
                entry("assets/bin/Data/Managed/Assembly-CSharp.dll", byteArrayOf(0x4d, 0x5a, 1, 2))
            }

            val inventory = UnityAssetInventoryScanner.inspect(apk)
            assertEquals(1, inventory.streamingTextCandidates)
            assertEquals(1, inventory.serializedFiles)
            assertEquals(1, inventory.bundleFiles)
            assertEquals(1, inventory.externalStreams)
            assertTrue(inventory.items.any { it.path.endsWith("Assembly-CSharp.dll") && it.support == UnityAssetSupport.PRESERVE_UNSUPPORTED })
        } finally {
            directory.deleteRecursively()
        }
    }
}
