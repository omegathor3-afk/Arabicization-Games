package com.omega.arabify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class UnitySerializedObjectTableParserTest {
    private data class Fixture(
        val header: UnitySerializedHeader,
        val metadata: ByteArray,
        val typeTable: UnitySerializedTypeTable
    )

    private fun fixture(version: Int = 22, bigId: Int = 0, duplicatePathId: Boolean = false): Fixture {
        val order = ByteOrder.LITTLE_ENDIAN
        val metadata = ByteBuffer.allocate(256).order(order)
        metadata.put("2022.3.0f1".toByteArray(Charsets.UTF_8))
        metadata.put(0)
        metadata.putInt(13) // target platform
        metadata.put(0) // TypeTree disabled
        metadata.putInt(1) // one serialized type

        metadata.putInt(1) // class ID
        if (version >= 16) metadata.put(0) // stripped
        if (version >= 17) metadata.putShort(0) // script type index
        if (version >= 13) metadata.put(ByteArray(16) { 0x44 }) // old type hash

        if (version in 7..13) metadata.putInt(bigId)
        metadata.putInt(2) // object count

        fun alignAbsolute(alignment: Int) {
            while ((20L + metadata.position()) % alignment != 0L) metadata.put(0x7f)
        }

        fun objectRecord(pathId: Long, start: Long, size: Int) {
            when {
                bigId != 0 && version in 7..13 -> metadata.putLong(pathId)
                version < 14 -> metadata.putInt(pathId.toInt())
                else -> {
                    alignAbsolute(4)
                    metadata.putLong(pathId)
                }
            }
            if (version >= 22) metadata.putLong(start) else metadata.putInt(start.toInt())
            metadata.putInt(size)
            metadata.putInt(if (version >= 16) 0 else 1)
            if (version < 16) metadata.putShort(1)
            if (version < 11) metadata.putShort(0)
            if (version in 11..16) metadata.putShort(0)
            if (version == 15 || version == 16) metadata.put(0)
        }

        objectRecord(101, 0, 16)
        objectRecord(if (duplicatePathId) 101 else 202, 16, 32)

        val bytes = metadata.array().copyOf(metadata.position())
        val headerSize = if (version >= 22) 48 else 20
        val header = UnitySerializedHeader(
            metadataSize = bytes.size.toLong(),
            fileSize = 4096,
            formatVersion = version,
            dataOffset = 2048,
            endian = "little",
            headerSize = headerSize
        )
        val prefix = UnitySerializedMetadataPrefixParser.parse(header, bytes)!!
        val typeTable = UnitySerializedTypeTableParser.parse(header, prefix, bytes)!!
        return Fixture(header, bytes, typeTable)
    }

    @Test fun parsesVersion22ObjectRangesAndAbsoluteOffsets() {
        val fixture = fixture()
        val table = UnitySerializedObjectTableParser.parse(
            fixture.header, fixture.typeTable, fixture.metadata
        )
        assertNotNull(table)
        assertEquals(2, table!!.objects.size)
        assertEquals(101L, table.objects[0].pathId)
        assertEquals(0L, table.objects[0].byteStart)
        assertEquals(2048L, table.objects[0].absoluteDataOffset)
        assertEquals(16L, table.objects[0].byteSize)
        assertEquals(0, table.objects[0].typeId)
        assertEquals(202L, table.objects[1].pathId)
        assertEquals(2064L, table.objects[1].absoluteDataOffset)
        assertEquals(32L, table.objects[1].byteSize)
    }

    @Test fun parsesLegacyVersion13AndBigIdEnabledPathIds() {
        val fixture = fixture(version = 13, bigId = 1)
        val table = UnitySerializedObjectTableParser.parse(
            fixture.header, fixture.typeTable, fixture.metadata
        )
        assertNotNull(table)
        assertEquals(1, table!!.bigIdEnabled)
        assertEquals(101L, table.objects[0].pathId)
        assertEquals(1, table.objects[0].classId)
        assertEquals(0, table.objects[0].scriptTypeIndex)
    }

    @Test fun parsesVersionSpecificObjectEntryLayouts() {
        for (version in listOf(15, 16, 17, 21)) {
            val fixture = fixture(version = version)
            val table = UnitySerializedObjectTableParser.parse(
                fixture.header, fixture.typeTable, fixture.metadata
            )
            assertNotNull("Failed to parse SerializedFile v$version", table)
            assertEquals(2, table!!.objects.size)
            if (version == 15 || version == 16) {
                assertEquals(0, table.objects[0].stripped)
            } else {
                assertEquals(null, table.objects[0].stripped)
            }
            if (version < 16) assertEquals(1, table.objects[0].classId)
            else assertEquals(null, table.objects[0].classId)
        }
    }

    @Test fun rejectsDuplicatePathIdsTruncatedRecordsAndOutOfFileRanges() {
        val duplicate = fixture(duplicatePathId = true)
        assertNull(UnitySerializedObjectTableParser.parse(
            duplicate.header, duplicate.typeTable, duplicate.metadata
        ))

        val valid = fixture()
        assertNull(UnitySerializedObjectTableParser.parse(
            valid.header, valid.typeTable, valid.metadata.copyOf(valid.metadata.size - 1)
        ))

        val badRange = valid.metadata.copyOf()
        val secondStartOffset = badRange.size - 24 + 8
        ByteBuffer.wrap(badRange).order(ByteOrder.LITTLE_ENDIAN)
            .putLong(secondStartOffset, Long.MAX_VALUE)
        assertNull(UnitySerializedObjectTableParser.parse(
            valid.header, valid.typeTable, badRange
        ))
    }

    @Test fun rejectsInvalidTypeIndicesAndExcessiveObjectCounts() {
        val valid = fixture()
        val badType = valid.metadata.copyOf()
        // The first object type ID follows the count, absolute-position padding,
        // PathID, data offset and size within the metadata byte array.
        val firstRecordStart = valid.typeTable.bytesConsumed + 4
        val padding = ((4 - ((valid.header.headerSize + firstRecordStart) % 4)) % 4)
        val firstTypeIdOffset = firstRecordStart + padding + 8 + 8 + 4
        ByteBuffer.wrap(badType).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(firstTypeIdOffset, 99)
        assertNull(UnitySerializedObjectTableParser.parse(
            valid.header, valid.typeTable, badType
        ))

        val excessive = valid.metadata.copyOf()
        ByteBuffer.wrap(excessive).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(valid.typeTable.bytesConsumed, Int.MAX_VALUE)
        assertNull(UnitySerializedObjectTableParser.parse(
            valid.header, valid.typeTable, excessive
        ))
    }
}
