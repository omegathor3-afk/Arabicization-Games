package com.omega.arabify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class UnitySerializedMetadataPrefixParserTest {
    private fun metadata(
        version: Int,
        order: ByteOrder,
        typeTreeEnabled: Int = 1,
        typeCount: Int = 1,
        includeTypeRecord: Boolean = true
    ): ByteArray {
        val output = ByteBuffer.allocate(256).order(order)
        if (version >= 7) {
            output.put("2021.3.0f1".toByteArray(Charsets.UTF_8))
            output.put(0)
        }
        if (version >= 8) output.putInt(13)
        if (version >= 13) output.put(typeTreeEnabled.toByte())
        output.putInt(typeCount)
        if (includeTypeRecord && typeCount > 0) {
            repeat(typeCount) {
                output.putInt(1)
                output.put(ByteArray(16))
            }
        }
        return output.array().copyOf(output.position())
    }

    private fun header(bytes: ByteArray, version: Int, endian: String) = UnitySerializedHeader(
        metadataSize = bytes.size.toLong(),
        fileSize = 4096,
        formatVersion = version,
        dataOffset = 2048,
        endian = endian,
        headerSize = if (version >= 22) 48 else 20
    )

    @Test fun parsesVersion13PreambleAndTypeTreeFlag() {
        val bytes = metadata(13, ByteOrder.LITTLE_ENDIAN)
        val parsed = UnitySerializedMetadataPrefixParser.parse(
            header(bytes, 13, "little"), bytes
        )
        assertNotNull(parsed)
        assertEquals("2021.3.0f1", parsed!!.unityVersion)
        assertEquals(13, parsed.targetPlatform)
        assertTrue(parsed.typeTreeEnabled == true)
        assertEquals(1, parsed.typeCount)
        assertEquals(bytes.size - 20, parsed.bytesConsumed)
    }

    @Test fun parsesExtendedFormat22MetadataInBigEndianOrder() {
        val bytes = metadata(22, ByteOrder.BIG_ENDIAN, typeTreeEnabled = 0)
        val parsed = UnitySerializedMetadataPrefixParser.parse(
            header(bytes, 22, "big"), bytes
        )
        assertNotNull(parsed)
        assertFalse(parsed!!.typeTreeEnabled!!)
        assertEquals(1, parsed.typeCount)
    }

    @Test fun permitsEmptyTypeListAndRejectsMalformedMetadata() {
        val empty = metadata(13, ByteOrder.LITTLE_ENDIAN, typeCount = 0)
        assertEquals(
            0,
            UnitySerializedMetadataPrefixParser.parse(
                header(empty, 13, "little"), empty
            )!!.typeCount
        )
        val badFlag = metadata(13, ByteOrder.LITTLE_ENDIAN, typeTreeEnabled = 2)
        assertNull(UnitySerializedMetadataPrefixParser.parse(header(badFlag, 13, "little"), badFlag))
        val truncated = metadata(13, ByteOrder.LITTLE_ENDIAN, includeTypeRecord = false)
        assertNull(UnitySerializedMetadataPrefixParser.parse(header(truncated, 13, "little"), truncated))
        assertNull(UnitySerializedMetadataPrefixParser.parse(header(empty, 13, "little"), empty + byteArrayOf(1)))
        assertNull(UnitySerializedMetadataPrefixParser.parse(header(empty, 8, "little"), empty))
    }
}
