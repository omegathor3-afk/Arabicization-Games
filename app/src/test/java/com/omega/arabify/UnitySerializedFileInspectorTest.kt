package com.omega.arabify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class UnitySerializedFileInspectorTest {
    private fun fileBytes(): ByteArray {
        val metadata = ByteBuffer.allocate(128).order(ByteOrder.LITTLE_ENDIAN)
        metadata.put("2022.3.0f1".toByteArray(Charsets.UTF_8))
        metadata.put(0)
        metadata.putInt(13)
        metadata.put(0)
        metadata.putInt(1)
        metadata.putInt(1)
        metadata.put(0)
        metadata.putShort(0)
        metadata.put(ByteArray(16) { 0x44 })
        metadata.putInt(2)
        while ((48L + metadata.position()) % 4L != 0L) metadata.put(0x7f)
        fun obj(pathId: Long, offset: Long, size: Int) {
            metadata.putLong(pathId)
            metadata.putLong(offset)
            metadata.putInt(size)
            metadata.putInt(0)
        }
        obj(11, 0, 16)
        obj(22, 16, 32)
        val meta = metadata.array().copyOf(metadata.position())
        val header = ByteBuffer.allocate(48).order(ByteOrder.BIG_ENDIAN).apply {
            putInt(meta.size)
            putInt(4096)
            putInt(22)
            putInt(2048)
            put(0)
            put(ByteArray(3))
            putInt(meta.size)
            putLong(4096)
            putLong(2048)
            putLong(0)
        }.array()
        return header + meta + ByteArray(4096 - header.size - meta.size)
    }

    @Test fun inspectsHeaderTypesAndObjectIndexWithoutReadingPayloads() {
        val bytes = fileBytes()
        val result = UnitySerializedFileInspector.inspect(
            ByteArrayInputStream(bytes), bytes.size.toLong()
        )
        assertNotNull(result)
        assertEquals(UnitySerializedInspectionStage.OBJECT_TABLE_PARSED, result!!.stage)
        assertEquals(1, result.metadataPrefix!!.typeCount)
        assertEquals(1, result.typeTable!!.records.size)
        assertEquals(2, result.objectTable!!.objects.size)
        assertEquals(2048L, result.objectTable!!.objects.first().absoluteDataOffset)
    }

    @Test fun readsMetadataAcrossShortInputReadsWithoutTouchingObjectPayloads() {
        val bytes = fileBytes()
        val metadataSize = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).int
        var bytesRead = 0
        val chunked = object : ByteArrayInputStream(bytes) {
            override fun read(target: ByteArray, offset: Int, length: Int): Int {
                val count = super.read(target, offset, minOf(length, 3))
                if (count > 0) bytesRead += count
                return count
            }
        }

        val result = UnitySerializedFileInspector.inspect(chunked, bytes.size.toLong())

        assertNotNull(result)
        assertEquals(UnitySerializedInspectionStage.OBJECT_TABLE_PARSED, result!!.stage)
        // The inspector should consume only the 48-byte header and bounded metadata,
        // never the object payload region that follows it.
        assertEquals(48 + metadataSize, bytesRead)
    }

    @Test fun rejectsTruncatedHeadersAndMetadata() {
        val bytes = fileBytes()
        assertNull(UnitySerializedFileInspector.inspect(
            ByteArrayInputStream(bytes.copyOf(30)), 30L
        ))
        val truncated = bytes.copyOf(48 + 10)
        assertNull(UnitySerializedFileInspector.inspect(
            ByteArrayInputStream(truncated), truncated.size.toLong()
        ))
    }
}
