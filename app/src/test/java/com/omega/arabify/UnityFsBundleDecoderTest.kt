package com.omega.arabify

import net.jpountz.lz4.LZ4Factory
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.LZMAOutputStream

class UnityFsBundleDecoderTest {
    private fun compressLz4(bytes: ByteArray): ByteArray {
        val compressor = LZ4Factory.fastestInstance().fastCompressor()
        val target = ByteArray(compressor.maxCompressedLength(bytes.size))
        val length = compressor.compress(bytes, 0, bytes.size, target, 0, target.size)
        return target.copyOf(length)
    }

    private fun compressLzma(bytes: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        LZMAOutputStream(output, LZMA2Options(), bytes.size.toLong()).use { it.write(bytes) }
        return output.toByteArray()
    }

    private fun makeBlockInfo(payloadSize: Int, compressedSize: Int, blockFlags: Int): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { data ->
            data.write(ByteArray(16))
            data.writeInt(1)
            data.writeInt(payloadSize)
            data.writeInt(compressedSize)
            data.writeShort(blockFlags)
            data.writeInt(1)
            data.writeLong(0L)
            data.writeLong(payloadSize.toLong())
            data.writeInt(0)
            data.write("CAB-fixture/resource.assets".toByteArray(Charsets.UTF_8))
            data.writeByte(0)
        }
        return bytes.toByteArray()
    }

    private fun makeBundle(
        payload: ByteArray,
        blockCodec: UnityCompressionCodec = UnityCompressionCodec.LZ4_BLOCK,
        infoCodec: UnityCompressionCodec = UnityCompressionCodec.LZ4_BLOCK,
        infoAtEnd: Boolean = false,
        formatVersion: Int = 6,
        engineVersion: String = "2021.3.0f1",
        needsPadding: Boolean = false
    ): ByteArray {
        val compressedPayload = when (blockCodec) {
            UnityCompressionCodec.NONE -> payload
            UnityCompressionCodec.LZ4_BLOCK -> compressLz4(payload)
            UnityCompressionCodec.LZMA_ALONE -> compressLzma(payload)
        }
        val blockFlags = when (blockCodec) {
            UnityCompressionCodec.NONE -> 0
            UnityCompressionCodec.LZ4_BLOCK -> 2
            UnityCompressionCodec.LZMA_ALONE -> 1
        }
        val rawInfo = makeBlockInfo(payload.size, compressedPayload.size, blockFlags)
        val compressedInfo = when (infoCodec) {
            UnityCompressionCodec.NONE -> rawInfo
            UnityCompressionCodec.LZ4_BLOCK -> compressLz4(rawInfo)
            UnityCompressionCodec.LZMA_ALONE -> compressLzma(rawInfo)
        }
        val infoFlags = when (infoCodec) {
            UnityCompressionCodec.NONE -> 0
            UnityCompressionCodec.LZ4_BLOCK -> 2
            UnityCompressionCodec.LZMA_ALONE -> 1
        } or (if (infoAtEnd) 0x80 else 0) or (if (needsPadding) 0x200 else 0)

        fun header(declaredSize: Long): ByteArray {
            val bytes = ByteArrayOutputStream()
            DataOutputStream(bytes).use { data ->
                data.write("UnityFS".toByteArray(Charsets.US_ASCII))
                data.writeByte(0)
                data.writeInt(formatVersion)
                data.write("5.x".toByteArray(Charsets.US_ASCII))
                data.writeByte(0)
                data.write(engineVersion.toByteArray(Charsets.US_ASCII))
                data.writeByte(0)
                data.writeLong(declaredSize)
                data.writeInt(compressedInfo.size)
                data.writeInt(rawInfo.size)
                data.writeInt(infoFlags)
            }
            return bytes.toByteArray()
        }

        val headerSize = header(0L).size
        fun align16(value: Int): Int = (value + 15) and -16
        val alignedHeaderSize = if (formatVersion >= 7 ||
            (engineVersion.startsWith("2019.4.") &&
                (engineVersion.substringAfter("2019.4.").takeWhile { it.isDigit() }.toIntOrNull() ?: 0) >= 15)
        ) align16(headerSize) else headerSize
        val afterInfo = if (infoAtEnd) alignedHeaderSize else alignedHeaderSize + compressedInfo.size
        val dataStart = if (needsPadding) align16(afterInfo) else afterInfo
        val totalSize = dataStart.toLong() + compressedPayload.size +
            if (infoAtEnd) compressedInfo.size else 0
        val output = ByteArrayOutputStream()
        output.write(header(totalSize))
        if (alignedHeaderSize > headerSize) output.write(ByteArray(alignedHeaderSize - headerSize))
        if (infoAtEnd) {
            if (dataStart > alignedHeaderSize) output.write(ByteArray(dataStart - alignedHeaderSize))
            output.write(compressedPayload)
            output.write(compressedInfo)
        } else {
            output.write(compressedInfo)
            if (dataStart > afterInfo) output.write(ByteArray(dataStart - afterInfo))
            output.write(compressedPayload)
        }
        return output.toByteArray()
    }

    private fun decode(bytes: ByteArray, limits: UnityFsDecodeLimits = UnityFsDecodeLimits()): Pair<UnityFsDecodeResult?, ByteArray> {
        val file = File.createTempFile("unityfs-fixture", ".bundle")
        try {
            file.writeBytes(bytes)
            val output = ByteArrayOutputStream()
            val result = UnityFsBundleDecoder.decode(file, output, limits)
            return result to output.toByteArray()
        } finally {
            file.delete()
        }
    }

    @Test fun decodesLz4UnityFsWithBlockInfoAtBeginningAndAtEnd() {
        val payload = "UnityFS bundle payload — مرحباً بالعالم".toByteArray(Charsets.UTF_8)
        listOf(false, true).forEach { infoAtEnd ->
            val bundle = makeBundle(payload, infoAtEnd = infoAtEnd)
            val (result, decoded) = decode(bundle)
            assertNotNull(result)
            assertArrayEquals(payload, decoded)
            assertEquals(payload.size.toLong(), result!!.decodedBytes)
            assertEquals("CAB-fixture/resource.assets", result.blockInfo.nodes.single().path)
            assertArrayEquals(bundle, bundle.copyOf())
        }
    }

    @Test fun decodesLzmaCompressedBlockInfoAndLzmaStorageBlock() {
        val payload = "UnityFS LZMA payload — العربية".toByteArray(Charsets.UTF_8)
        val bundle = makeBundle(
            payload,
            blockCodec = UnityCompressionCodec.LZMA_ALONE,
            infoCodec = UnityCompressionCodec.LZMA_ALONE,
            infoAtEnd = true
        )
        val (result, decoded) = decode(bundle)
        assertNotNull(result)
        assertArrayEquals(payload, decoded)
    }

    @Test fun rejectsTruncatedBundlesAndAggregateOrPerBlockBudgetOverruns() {
        val payload = ByteArray(2048) { (it % 251).toByte() }
        val bundle = makeBundle(payload)
        assertNull(decode(bundle.copyOf(bundle.size - 1)).first)
        assertNull(
            decode(
                bundle,
                UnityFsDecodeLimits(maxTotalDecodedBytes = 1024)
            ).first
        )
        assertNull(
            decode(
                bundle,
                UnityFsDecodeLimits(maxDecodedBlockBytes = 1024)
            ).first
        )
        assertNull(
            decode(
                bundle,
                UnityFsDecodeLimits(maxCompressedBlockBytes = 1)
            ).first
        )
    }

    @Test fun alignsVersion7HeadersAndLate2019Version6Headers() {
        val payload = "alignment".toByteArray()
        listOf(
            makeBundle(payload, formatVersion = 7),
            makeBundle(payload, formatVersion = 6, engineVersion = "2019.4.15f1")
        ).forEach { bundle ->
            val (result, decoded) = decode(bundle)
            assertNotNull(result)
            assertArrayEquals(payload, decoded)
        }
    }

    @Test fun appliesVersionedBlockInfoPaddingAndRejectsLegacyEncryptionMeaning() {
        val payload = "padding".toByteArray()
        val modern = makeBundle(
            payload, engineVersion = "2021.3.3f1", needsPadding = true
        )
        val (result, decoded) = decode(modern)
        assertNotNull(result)
        assertArrayEquals(payload, decoded)

        val legacy = makeBundle(
            payload, engineVersion = "2021.3.2f1", needsPadding = true
        )
        assertNull(decode(legacy).first)
    }

    @Test fun enforcesAggregateWorkingSetForLzmaDictionaryAndBlockBuffers() {
        val bundle = makeBundle(
            ByteArray(128) { it.toByte() },
            blockCodec = UnityCompressionCodec.LZMA_ALONE
        )
        assertNull(
            decode(
                bundle,
                UnityFsDecodeLimits(maxWorkingSetBytes = 1024)
            ).first
        )
    }

    @Test fun includesRetainedBlockMetadataInStorageWorkingSetBudget() {
        val bundle = makeBundle(ByteArray(128) { (it % 251).toByte() })
        val header = UnityArchiveHeaderParser.parse(bundle.copyOf(minOf(bundle.size, 1024)), bundle.size.toLong())!!
        val infoOffset = header.headerSize
        val compressedInfo = bundle.copyOfRange(
            infoOffset,
            infoOffset + header.compressedBlocksInfoSize.toInt()
        )
        val codec = unityCompressionCodecFromFlags(header.flags)!!
        val decodedInfo = UnityFsBlockDecompressor.decompress(
            codec,
            compressedInfo,
            header.uncompressedBlocksInfoSize
        )
        val blockInfo = UnityBlockInfoParser.parse(decodedInfo)!!
        val block = blockInfo.blocks.single()
        val retainedAndBlockBytes = header.compressedBlocksInfoSize +
            header.uncompressedBlocksInfoSize + block.compressedSize + block.uncompressedSize

        // The metadata alone fits, and the storage block alone fits, but their
        // simultaneously retained buffers exceed this aggregate working-set cap.
        assertNull(
            decode(
                bundle,
                UnityFsDecodeLimits(maxWorkingSetBytes = retainedAndBlockBytes - 1L)
            ).first
        )
    }

    @Test fun rejectsUnknownCompressionFlagsAndNonUnityFsSignatures() {
        assertNull(unityCompressionCodecFromFlags(0x100000000L))
        val payload = byteArrayOf(1, 2, 3)
        val bundle = makeBundle(payload)
        val invalid = bundle.copyOf()
        invalid[0] = 'X'.code.toByte()
        assertNull(decode(invalid).first)
    }

    @Test fun acceptsHeaderFlagsWithUnityFsOptionsOutsideCompressionBits() {
        val payload = "flags".toByteArray()
        val bundle = makeBundle(payload, infoAtEnd = true)
        // Header flags follow the two size fields; this fixture's engine version makes the offset 43.
        ByteBuffer.wrap(bundle).order(ByteOrder.BIG_ENDIAN).putInt(43, 0x000001c2)
        val result = decode(bundle).first
        assertNotNull(result)
        assertEquals(0x1c2L, result!!.header.flags)
        val unknownFlagBundle = bundle.copyOf()
        ByteBuffer.wrap(unknownFlagBundle).order(ByteOrder.BIG_ENDIAN).putInt(43, 0x000101c2)
        assertNull(decode(unknownFlagBundle).first)
    }
}
