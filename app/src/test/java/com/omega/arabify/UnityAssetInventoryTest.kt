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
import java.util.zip.ZipFile
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

    @Test fun parsesUnityFsVersion8HeaderWithoutDecompressingPayload() {
        val output = java.io.ByteArrayOutputStream()
        java.io.DataOutputStream(output).use { data ->
            data.write("UnityFS".toByteArray(Charsets.US_ASCII))
            data.writeByte(0)
            data.writeInt(8)
            data.write("2022.3.1f1".toByteArray(Charsets.UTF_8))
            data.writeByte(0)
            data.write("6000.0.58f2".toByteArray(Charsets.UTF_8))
            data.writeByte(0)
            data.writeLong(128L)
            data.writeInt(24)
            data.writeInt(48)
            data.writeInt(0x80)
        }
        val prefix = output.toByteArray()
        val header = UnityArchiveHeaderParser.parse(prefix, 128L)
        assertNotNull(header)
        assertEquals("UnityFS", header!!.signature)
        assertEquals(8, header.formatVersion)
        assertEquals("2022.3.1f1", header.playerVersion)
        assertEquals("6000.0.58f2", header.engineVersion)
        assertEquals(128L, header.declaredSize)
        assertTrue(header.blocksInfoAtEnd)
        assertEquals(prefix.size, header.headerSize)
    }

    @Test fun rejectsTruncatedAndOutOfBoundsUnityFsHeaders() {
        val output = java.io.ByteArrayOutputStream()
        java.io.DataOutputStream(output).use { data ->
            data.write("UnityFS".toByteArray(Charsets.US_ASCII))
            data.writeByte(0)
            data.writeInt(8)
            data.write("player".toByteArray())
            data.writeByte(0)
            data.write("engine".toByteArray())
            data.writeByte(0)
            data.writeLong(4096L)
            data.writeInt(24)
            data.writeInt(48)
            data.writeInt(0)
        }
        val prefix = output.toByteArray()
        assertNull(UnityArchiveHeaderParser.parse(prefix.copyOf(prefix.size - 5), 4096L))
        assertNull(UnityArchiveHeaderParser.parse(prefix, 128L))
    }

    @Test fun integrityVerifierAcceptsByteIdenticalUnityResourcesAfterRepack() {
        val directory = createTempDir(prefix = "arabify-unity-integrity-")
        try {
            val source = File(directory, "source.apk")
            val output = File(directory, "output.apk")
            val entries = linkedMapOf(
                "assets/bin/Data/resources.assets" to byteArrayOf(1, 2, 3, 4),
                "assets/bin/Data/content.bundle" to "UnityFS\u0000".toByteArray(),
                "assets/bin/Data/sharedassets0.assets.resS" to byteArrayOf(9, 8, 7),
                "assets/bin/Data/Managed/Assembly-CSharp.dll" to byteArrayOf(0x4d, 0x5a, 0, 1)
            )
            fun write(file: File, values: Map<String, ByteArray>) {
                ZipOutputStream(file.outputStream()).use { zip ->
                    values.forEach { (path, bytes) ->
                        zip.putNextEntry(ZipEntry(path))
                        zip.write(bytes)
                        zip.closeEntry()
                    }
                }
            }
            write(source, entries)
            write(output, entries)
            val report = UnityAssetIntegrityVerifier.verifyUnchanged(source, output)
            assertEquals(4, report.filesVerified)
            assertEquals(entries.values.sumOf { it.size.toLong() }, report.totalBytesVerified)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun integrityVerifierRejectsChangedSerializedAssetBytes() {
        val directory = createTempDir(prefix = "arabify-unity-integrity-fail-")
        try {
            val source = File(directory, "source.apk")
            val output = File(directory, "output.apk")
            fun write(file: File, bytes: ByteArray) {
                ZipOutputStream(file.outputStream()).use { zip ->
                    zip.putNextEntry(ZipEntry("assets/bin/Data/resources.assets"))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
            write(source, byteArrayOf(1, 2, 3))
            write(output, byteArrayOf(1, 2, 4))
            var rejected = false
            try {
                UnityAssetIntegrityVerifier.verifyUnchanged(source, output)
            } catch (_: IllegalArgumentException) {
                rejected = true
            }
            assertTrue(rejected)
        } finally {
            directory.deleteRecursively()
        }
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

    @Test fun parsesDecompressedUnityBlockInfoWithBoundedMetadata() {
        val output = java.io.ByteArrayOutputStream()
        java.io.DataOutputStream(output).use { data ->
            data.write(ByteArray(16) { (it + 1).toByte() })
            data.writeInt(1)
            data.writeInt(100)
            data.writeInt(60)
            data.writeShort(2)
            data.writeInt(1)
            data.writeLong(0L)
            data.writeLong(100L)
            data.writeInt(0)
            data.write("CAB-123/resource.assets".toByteArray(Charsets.UTF_8))
            data.writeByte(0)
        }
        val parsed = UnityBlockInfoParser.parse(output.toByteArray(), 100L)
        assertNotNull(parsed)
        assertEquals(1, parsed!!.blocks.size)
        assertEquals(60L, parsed.blocks.single().compressedSize)
        assertEquals(100L, parsed.totalUncompressedBytes)
        assertEquals("CAB-123/resource.assets", parsed.nodes.single().path)
        assertEquals(100L, parsed.nodes.single().size)
    }

    @Test fun rejectsMalformedUnityBlockInfoCountsRangesPathsAndTrailingBytes() {
        val output = java.io.ByteArrayOutputStream()
        java.io.DataOutputStream(output).use { data ->
            data.write(ByteArray(16))
            data.writeInt(1)
            data.writeInt(10)
            data.writeInt(5)
            data.writeShort(0)
            data.writeInt(1)
            data.writeLong(9L)
            data.writeLong(2L)
            data.writeInt(0)
            data.write("bad.bin".toByteArray())
            data.writeByte(0)
        }
        val malformed = output.toByteArray()
        assertNull(UnityBlockInfoParser.parse(malformed))
        assertNull(UnityBlockInfoParser.parse(malformed + byteArrayOf(1)))
        assertNull(UnityBlockInfoParser.parse(ByteArray(20)))
        val badCount = malformed.copyOf().also {
            ByteBuffer.wrap(it).order(ByteOrder.BIG_ENDIAN).putInt(16, -1)
        }
        assertNull(UnityBlockInfoParser.parse(badCount))
    }


    @Test fun decompressesUnityLz4RawBlockAndChecksExactOutputLength() {
        val original = "UnityFS bounded LZ4 fixture — مرحباً".toByteArray(Charsets.UTF_8)
        val compressed = net.jpountz.lz4.LZ4Factory.fastestInstance()
            .fastCompressor().compress(original)
        val decoded = UnityFsBlockDecompressor.decompress(
            UnityCompressionCodec.LZ4_BLOCK, compressed, original.size.toLong()
        )
        assertArrayEquals(original, decoded)
        var rejected = false
        try {
            UnityFsBlockDecompressor.decompress(
                UnityCompressionCodec.LZ4_BLOCK, compressed, original.size.toLong() - 1
            )
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue(rejected)
    }

    @Test fun decompressesUnityLzmaAloneStreamWithinMemoryAndOutputBudgets() {
        val original = "UnityFS bounded LZMA fixture — النص العربي".toByteArray(Charsets.UTF_8)
        val encoded = java.io.ByteArrayOutputStream()
        org.tukaani.xz.LZMAOutputStream(
            encoded, org.tukaani.xz.LZMA2Options(), original.size.toLong()
        ).use { it.write(original) }

        val decoded = UnityFsBlockDecompressor.decompress(
            UnityCompressionCodec.LZMA_ALONE,
            encoded.toByteArray(),
            original.size.toLong(),
            maxOutputBytes = 1024,
            maxLzmaMemoryBytes = 16L * 1024L * 1024L
        )
        assertArrayEquals(original, decoded)
    }

    @Test fun rejectsUnityCompressionBombsAndMalformedStreamsBeforeUnboundedAllocation() {
        val compressed = byteArrayOf(1, 2, 3, 4)
        fun rejected(block: () -> Unit): Boolean = try {
            block()
            false
        } catch (_: Exception) {
            true
        }
        assertTrue(rejected {
            UnityFsBlockDecompressor.decompress(
                UnityCompressionCodec.LZ4_BLOCK, compressed, 4096,
                maxOutputBytes = 1024
            )
        })
        assertTrue(rejected {
            UnityFsBlockDecompressor.decompress(
                UnityCompressionCodec.LZ4_BLOCK, compressed, 16,
                maxCompressedBytes = 3
            )
        })
        assertTrue(rejected {
            UnityFsBlockDecompressor.decompress(
                UnityCompressionCodec.LZ4_BLOCK, compressed, 16
            )
        })
        assertTrue(rejected {
            UnityFsBlockDecompressor.decompress(
                UnityCompressionCodec.LZMA_ALONE, compressed, 16,
                maxLzmaMemoryBytes = 1024
            )
        })
    }


    @Test fun mapsUnityFsStorageBlockCompressionFlagsAndCopiesUncompressedBlocksSafely() {
        assertEquals(UnityCompressionCodec.NONE, unityCompressionCodecFromFlags(0L))
        assertEquals(UnityCompressionCodec.LZMA_ALONE, unityCompressionCodecFromFlags(1L))
        assertEquals(UnityCompressionCodec.LZ4_BLOCK, unityCompressionCodecFromFlags(2L))
        assertEquals(UnityCompressionCodec.LZ4_BLOCK, unityCompressionCodecFromFlags(3L))
        assertNull(unityCompressionCodecFromFlags(4L))
        assertNull(unityCompressionCodecFromFlags(-1L))

        val original = byteArrayOf(3, 1, 4, 1, 5)
        assertArrayEquals(
            original,
            UnityFsBlockDecompressor.decompress(
                UnityCompressionCodec.NONE, original, original.size.toLong()
            )
        )
    }

}
