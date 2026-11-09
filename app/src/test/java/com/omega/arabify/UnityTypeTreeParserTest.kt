package com.omega.arabify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class UnityTypeTreeParserTest {
    private fun fixture(version: Int, badChildLevel: Int? = null, badStringOffset: Boolean = false): ByteArray {
        val strings = "Base\u0000GameObject\u0000m_Name\u0000".toByteArray(Charsets.UTF_8)
        val nodeSize = if (version >= 19) 31 else 23
        val output = ByteBuffer.allocate(8 + nodeSize * 2 + strings.size).order(ByteOrder.LITTLE_ENDIAN)
        output.putInt(2)
        output.putInt(strings.size)
        fun node(level: Int, typeOffset: Int, nameOffset: Int) {
            output.put(1)
            output.put(level.toByte())
            output.put(0)
            output.putInt(typeOffset)
            output.putInt(nameOffset)
            output.putInt(-1)
            output.putInt(0)
            output.putInt(0)
            if (version >= 19) output.putLong(0x1020304050607080L)
        }
        node(0, if (badStringOffset) 999 else 0, 5)
        node(badChildLevel ?: 1, 5, 16)
        output.put(strings)
        return output.array()
    }

    @Test fun parsesLegacyFlatTypeTreeNodesAndResolvesLocalStrings() {
        val tree = UnityTypeTreeParser.parse(fixture(18), 18)
        assertNotNull(tree)
        assertEquals(2, tree!!.nodes.size)
        assertEquals("Base", tree.nodes[0].typeName)
        assertEquals("GameObject", tree.nodes[0].fieldName)
        assertEquals("GameObject", tree.nodes[1].typeName)
        assertEquals("m_Name", tree.nodes[1].fieldName)
        assertEquals(0, tree.unresolvedCommonStringReferences)
        assertEquals(null, tree.nodes[0].refTypeHash)
    }

    @Test fun parsesVersion19ReferenceTypeHashes() {
        val tree = UnityTypeTreeParser.parse(fixture(19), 19)
        assertNotNull(tree)
        assertEquals(0x1020304050607080L, tree!!.nodes[0].refTypeHash)
    }

    @Test fun preservesUnresolvedCommonStringOffsetsWithoutGuessingNames() {
        val bytes = fixture(19)
        // Type string offset on the root uses the common-string high bit.
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(11, 0x80000001.toInt())
        val tree = UnityTypeTreeParser.parse(bytes, 19)
        assertNotNull(tree)
        assertEquals(null, tree!!.nodes[0].typeName)
        assertTrue(tree.unresolvedCommonStringReferences >= 1)
    }

    @Test fun rejectsInvalidCountsLevelsOffsetsAndTrailingData() {
        val negativeCount = fixture(18).also {
            ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(0, -1)
        }
        assertNull(UnityTypeTreeParser.parse(negativeCount, 18))
        assertNull(UnityTypeTreeParser.parse(fixture(18, badChildLevel = 4), 18))
        assertNull(UnityTypeTreeParser.parse(fixture(18, badStringOffset = true), 18))
        assertNull(UnityTypeTreeParser.parse(fixture(18) + byteArrayOf(1), 18))
        assertNull(UnityTypeTreeParser.parse(fixture(18), 11))
    }
}
