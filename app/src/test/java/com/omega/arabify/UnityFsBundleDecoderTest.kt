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
    private fun compressLz4(bytes: ByteArray): ByteArray =
        LZ4Factory.fastestInstance().fastCompressor().let { compressor ->
            ByteArray(compressor.maxCompressedLength(bytes.size)).also { target ->
                val length = compressor.compress(bytes, 0, bytes.size, target, 0, target.size)
                return@let target.copyOf(length)
            }
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
        infoAtEnd: Boolean = false
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
        } or if (infoAtEnd) 0x80 else 0

        fun header(declaredSize: Long): ByteArray {
            val bytes = ByteArrayOutputStream()
            DataOutputStream(bytes).use { data ->
                data.write("UnityFS".toByteArray(Charsets.US_ASCII))
                data.writeByte(0)
                data.writeInt(6)
                data.write("5.x".toByteArray(Charsets.US_ASCII))
                data.writeByte(0)
                data.write("2021.3".toByteArray(Charsets.US_ASCII))
                data.writeByte(0)
                data.writeLong(declaredSize)
                data.writeInt(compressedInfo.size)
                data.writeInt(rawInfo.size)
                data.writeInt(infoFlags)
            }
            return bytes.toByteArray()
        }

        val headerSize = header(0L).size
        val totalSize = headerSize.toLong() + compressedInfo.size + compressedPayload.size
        val output = ByteArrayOutputStream()
        output.write(header(totalSize))
        if (infoAtEnd) {
            output.write(compressedPayload)
            output.write(compressedInfo)
        } else {
            output.write(compressedInfo)
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
        // The final four bytes in the header are at offset 39 for this fixture.
        ByteBuffer.wrap(bundle).order(ByteOrder.BIG_ENDIAN).putInt(39, 0x00010282)
        val result = decode(bundle).first
        assertNotNull(result)
        assertEquals(0x00010282L, result!!.header.flags)
    }
}
