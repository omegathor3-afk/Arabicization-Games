package com.omega.arabify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class UnityTypeTreeInfoHandlerParserTest {
    private fun rawTree(formatVersion: Int): ByteArray {
        val strings = "Base\u0000Root\u0000".toByteArray(Charsets.UTF_8)
        val nodeSize = if (formatVersion >= 19) 32 else 24
        val output = ByteBuffer.allocate(8 + nodeSize + strings.size).order(ByteOrder.LITTLE_ENDIAN)
        output.putInt(1)
        output.putInt(strings.size)
        output.putShort(1)
        output.put(0)
        output.put(0)
        output.putInt(0)
        output.putInt(5)
        output.putInt(-1)
        output.putInt(0)
        output.putInt(0)
        if (formatVersion >= 19) output.putLong(0x0102030405060708L)
        output.put(strings)
        return output.array()
    }

    private fun handler(formatVersion: Int = 32, hashes: Int = 1, magic: String = "mhtt"): ByteArray {
        val tree = rawTree(formatVersion)
        val inner = ByteBuffer.allocate(8 + tree.size + 4 + hashes * 16).order(ByteOrder.LITTLE_ENDIAN)
        inner.put(magic.toByteArray(Charsets.US_ASCII))
        inner.putInt(formatVersion)
        inner.put(tree)
        inner.putInt(hashes)
        repeat(hashes) { inner.put(ByteArray(16) { (it + 1).toByte() }) }
        val info = inner.array()
        val output = ByteBuffer.allocate(16 + 4 + info.size).order(ByteOrder.LITTLE_ENDIAN)
        output.put(ByteArray(16) { 0x55 })
        output.putInt(info.size)
        output.put(info)
        return output.array()
    }

    @Test fun parsesMhttEnvelopeAndReferenceHashes() {
        val parsed = UnityTypeTreeInfoHandlerParser.parse(handler())
        assertNotNull(parsed)
        assertEquals(32, parsed!!.info!!.typeTreeFormatVersion)
        assertEquals("Base", parsed.info.tree.nodes.single().typeName)
        assertEquals("Root", parsed.info.tree.nodes.single().fieldName)
        assertEquals(1, parsed.info.referenceHashes.size)
    }

    @Test fun acceptsHandlerWithoutInlineTreePayload() {
        val bytes = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(ByteArray(16))
            putInt(0)
        }.array()
        val parsed = UnityTypeTreeInfoHandlerParser.parse(bytes)
        assertNotNull(parsed)
        assertEquals(null, parsed!!.info)
    }

    @Test fun rejectsInvalidMagicAndReferenceHashLengths() {
        assertNull(UnityTypeTreeInfoHandlerParser.parse(handler(magic = "nope")))
        assertNull(UnityTypeTreeInfoHandlerParser.parse(handler(hashes = 2).copyOf(16 + 4 + 8 + rawTree(32).size + 4 + 16)))
    }
}
