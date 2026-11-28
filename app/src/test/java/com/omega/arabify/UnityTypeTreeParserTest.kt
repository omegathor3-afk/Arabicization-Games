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
        val nodeSize = if (version >= 19) 32 else 24
        val output = ByteBuffer.allocate(8 + nodeSize * 2 + strings.size).order(ByteOrder.LITTLE_ENDIAN)
        output.putInt(2)
        output.putInt(strings.size)
        fun node(level: Int, typeOffset: Int, nameOffset: Int) {
            output.putShort(1)
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

    @Test fun commonStringTableHasExpectedInitialEntries() {
        assertEquals("AABB", UnityCommonStringTable.resolve(0L))
        assertEquals("m_Name", UnityCommonStringTable.resolve(427L))
        assertEquals("TextAsset", UnityCommonStringTable.resolve(847L))
        assertEquals(114, UnityCommonStringTable.entries().size)
        assertNull(UnityCommonStringTable.resolve(0x7fffffffL))
    }

    private fun commonStringFixture(typeOffset: Int, nameOffset: Int): ByteArray {
        return ByteBuffer.allocate(8 + 24).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(1)
            putInt(0)
            putShort(1)
            put(0)
            put(0)
            putInt(typeOffset)
            putInt(nameOffset)
            putInt(-1)
            putInt(0)
            putInt(0)
        }.array()
    }

    @Test fun resolvesKnownCommonStringOffsets() {
        val bytes = commonStringFixture(
            typeOffset = 0x80000000.toInt(), // AABB, offset 0
            nameOffset = 0x8000034f.toInt() // TextAsset, byte offset 847
        )
        val tree = UnityTypeTreeParser.parse(bytes, 17)
        assertNotNull(tree)
        assertEquals("AABB", tree!!.nodes.single().typeName)
        assertEquals("TextAsset", tree.nodes.single().fieldName)
        assertEquals(0, tree.unresolvedCommonStringReferences)
    }

    @Test fun leavesUnknownCommonStringOffsetsUnresolvedWithoutGuessingNames() {
        val bytes = commonStringFixture(
            typeOffset = 0x80007fff.toInt(),
            nameOffset = 0x80000000.toInt()
        )
        val tree = UnityTypeTreeParser.parse(bytes, 17)
        assertNotNull(tree)
        assertEquals(null, tree!!.nodes.single().typeName)
        assertEquals("AABB", tree.nodes.single().fieldName)
        assertEquals(1, tree.unresolvedCommonStringReferences)
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


class UnitySerializedTypeTableParserTest {
    private fun treeBlob(formatVersion: Int): ByteArray {
        val strings = "Base\u0000Root\u0000".toByteArray(Charsets.UTF_8)
        val nodeSize = if (formatVersion >= 19) 32 else 24
        val out = ByteBuffer.allocate(8 + nodeSize + strings.size).order(ByteOrder.LITTLE_ENDIAN)
        out.putInt(1)
        out.putInt(strings.size)
        out.putShort(1)
        out.put(0)
        out.put(0)
        out.putInt(0)
        out.putInt(5)
        out.putInt(-1)
        out.putInt(0)
        out.putInt(0)
        if (formatVersion >= 19) out.putLong(0x1122334455667788L)
        out.put(strings)
        return out.array()
    }

    private fun infoHandler(formatVersion: Int): ByteArray {
        val tree = treeBlob(formatVersion)
        val info = ByteBuffer.allocate(8 + tree.size + 4 + 16).order(ByteOrder.LITTLE_ENDIAN)
        info.put("mhtt".toByteArray(Charsets.US_ASCII))
        info.putInt(formatVersion)
        info.put(tree)
        info.putInt(1)
        info.put(ByteArray(16) { (it + 2).toByte() })
        val payload = info.array()
        return ByteBuffer.allocate(20 + payload.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(ByteArray(16) { 0x33 })
            putInt(payload.size)
            put(payload)
        }.array()
    }

    private fun metadata(version: Int, treeEnabled: Boolean = true): ByteArray {
        val typeTree = if (version >= 23) infoHandler(version) else treeBlob(version)
        val prefix = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("2022.3.0f1".toByteArray(Charsets.UTF_8))
            put(0)
            putInt(13)
            put(if (treeEnabled) 1 else 0)
            putInt(1)
        }.array().copyOf(20)
        val treeBytes = if (treeEnabled) typeTree.size else 0
        val dependencyBytes = if (version >= 21 && treeEnabled) 12 else 0
        val typeRecord = ByteBuffer.allocate(4 + 1 + 2 + 16 + 16 + treeBytes + dependencyBytes)
            .order(ByteOrder.LITTLE_ENDIAN).apply {
                putInt(114)
                put(0)
                putShort(0)
                put(ByteArray(16) { 0x11 })
                put(ByteArray(16) { 0x22 })
                if (treeEnabled) put(typeTree)
                if (version >= 21 && treeEnabled) {
                    putInt(2)
                    putInt(3)
                    putInt(7)
                }
            }.array()
        return prefix + typeRecord
    }

    private fun parse(version: Int, bytes: ByteArray): UnitySerializedTypeTable? {
        val headerSize = if (version >= 22) 48 else 20
        val header = UnitySerializedHeader(
            bytes.size.toLong(),
            bytes.size.toLong() + headerSize + 4096L,
            version,
            bytes.size.toLong() + headerSize,
            "little",
            headerSize
        )
        val prefix = UnitySerializedMetadataPrefixParser.parse(header, bytes) ?: return null
        return UnitySerializedTypeTableParser.parse(header, prefix, bytes)
    }

    @Test fun parsesVersion22InlineTypeTreeAndTypeDependencies() {
        val parsed = parse(22, metadata(22))
        assertNotNull(parsed)
        val record = parsed!!.records.single()
        assertEquals(114, record.classId)
        assertEquals("Root", record.typeTree!!.nodes.single().fieldName)
        assertEquals(listOf(3, 7), record.typeDependencies)
        assertEquals(16, record.scriptId!!.size)
        assertEquals(16, record.oldTypeHash!!.size)
    }

    @Test fun parsesVersion23MhttTypeTreeInfoHandler() {
        val parsed = parse(23, metadata(23))
        assertNotNull(parsed)
        val record = parsed!!.records.single()
        assertNotNull(record.typeTreeInfoHandler)
        assertEquals(23, record.typeTreeInfoHandler!!.info!!.typeTreeFormatVersion)
        assertEquals(1, record.typeTreeInfoHandler!!.info!!.referenceHashes.size)
        assertEquals(listOf(3, 7), record.typeDependencies)
    }

    @Test fun parsesSerializedTypeWithoutInlineTypeTree() {
        val parsed = parse(22, metadata(22, treeEnabled = false))
        assertNotNull(parsed)
        assertEquals(null, parsed!!.records.single().typeTree)
        assertTrue(parsed.records.single().typeDependencies.isEmpty())
    }

    private fun largeTreeBlob(formatVersion: Int, nodeCount: Int): ByteArray {
        val nodeSize = if (formatVersion >= 19) 32 else 24
        val output = ByteBuffer.allocate(8 + nodeSize * nodeCount).order(ByteOrder.LITTLE_ENDIAN)
        output.putInt(nodeCount)
        output.putInt(0)
        repeat(nodeCount) { index ->
            output.putShort(1)
            output.put(if (index == 0) 0 else 1)
            output.put(0)
            output.putInt(0x80000001.toInt())
            output.putInt(0x80000001.toInt())
            output.putInt(-1)
            output.putInt(index)
            output.putInt(0)
            if (formatVersion >= 19) output.putLong(0L)
        }
        return output.array()
    }

    private fun largeInfoHandler(formatVersion: Int, nodeCount: Int): ByteArray {
        val tree = largeTreeBlob(formatVersion, nodeCount)
        val info = ByteBuffer.allocate(8 + tree.size + 4).order(ByteOrder.LITTLE_ENDIAN)
        info.put("mhtt".toByteArray(Charsets.US_ASCII))
        info.putInt(formatVersion)
        info.put(tree)
        info.putInt(0)
        val payload = info.array()
        return ByteBuffer.allocate(20 + payload.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(ByteArray(16) { 0x33 })
            putInt(payload.size)
            put(payload)
        }.array()
    }

    private fun metadataWithLargeTrees(formatVersion: Int, nodeCount: Int): ByteArray {
        val records = List(2) { index ->
            val tree = if (formatVersion >= 23) {
                largeInfoHandler(formatVersion, nodeCount)
            } else {
                largeTreeBlob(formatVersion, nodeCount)
            }
            ByteBuffer.allocate(4 + 1 + 2 + 16 + tree.size + 4)
                .order(ByteOrder.LITTLE_ENDIAN).apply {
                    putInt(index + 1)
                    put(0)
                    putShort(0)
                    put(ByteArray(16))
                    put(tree)
                    putInt(0)
                }.array()
        }
        val metadataSize = 20 + records.sumOf { it.size }
        return ByteBuffer.allocate(metadataSize).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("2022.3.0f1".toByteArray(Charsets.UTF_8))
            put(0)
            putInt(13)
            put(1)
            putInt(2)
            records.forEach { put(it) }
        }.array()
    }

    @Test fun acceptsMultipleSmallTreesWithinTheAggregateBudget() {
        assertEquals(2, parse(22, metadataWithLargeTrees(22, 2))!!.records.size)
        assertEquals(2, parse(23, metadataWithLargeTrees(23, 2))!!.records.size)
    }

    @Test fun rejectsAggregateTypeTreeNodeBudgetAcrossInlineAndMhttRecords() {
        // Each tree is below the per-tree 250k cap, but the two together exceed
        // the per-table budget. The second tree must be rejected before allocation.
        assertNull(parse(22, metadataWithLargeTrees(22, 125_001)))
        assertNull(parse(23, metadataWithLargeTrees(23, 125_001)))
    }

    @Test fun rejectsTruncatedTypeRecordsAndMalformedTreePayloads() {
        val valid = metadata(22)
        assertNull(parse(22, valid.copyOf(valid.size - 1)))
        val malformed = metadata(22).copyOf()
        val nodeCountOffset = 20 + 4 + 1 + 2 + 16 + 16
        ByteBuffer.wrap(malformed).order(ByteOrder.LITTLE_ENDIAN).putInt(nodeCountOffset, -1)
        assertNull(parse(22, malformed))
    }
}
