package com.omega.arabify

import java.nio.ByteBuffer
import java.nio.ByteOrder

internal data class UnitySerializedObjectInfo(
    val pathId: Long,
    val byteStart: Long,
    val absoluteDataOffset: Long,
    val byteSize: Long,
    val typeId: Int,
    val classId: Int?,
    val destroyed: Int?,
    val scriptTypeIndex: Int?,
    val stripped: Int?
)

internal data class UnitySerializedObjectTable(
    val objects: List<UnitySerializedObjectInfo>,
    val bigIdEnabled: Int?,
    val bytesConsumed: Int
)

/**
 * Bounded, read-only parser for the SerializedFile object-info table.
 *
 * It records object byte ranges but never reads, decodes, or modifies object data.
 * Alignment is calculated against the absolute file position, not the metadata
 * buffer's local index.
 */
internal object UnitySerializedObjectTableParser {
    private const val MAX_METADATA_BYTES = 64 * 1024 * 1024
    private const val MAX_OBJECTS = 500_000

    fun parse(
        header: UnitySerializedHeader,
        typeTable: UnitySerializedTypeTable,
        metadata: ByteArray
    ): UnitySerializedObjectTable? {
        if (header.formatVersion !in 9..100 ||
            metadata.size.toLong() != header.metadataSize ||
            metadata.size > MAX_METADATA_BYTES ||
            typeTable.bytesConsumed !in 0..metadata.size ||
            header.headerSize < 16 ||
            header.fileSize < header.dataOffset ||
            header.dataOffset < header.headerSize
        ) return null

        val order = when (header.endian) {
            "little" -> ByteOrder.LITTLE_ENDIAN
            "big" -> ByteOrder.BIG_ENDIAN
            else -> return null
        }

        return runCatching {
            val input = ByteBuffer.wrap(metadata).order(order)
            input.position(typeTable.bytesConsumed)

            fun readU32(): Long = input.int.toLong() and 0xffffffffL

            fun alignAbsolute(alignment: Int) {
                val absolute = Math.addExact(header.headerSize.toLong(), input.position().toLong())
                val remainder = (absolute % alignment).toInt()
                if (remainder != 0) {
                    val skip = alignment - remainder
                    require(skip <= input.remaining())
                    input.position(Math.addExact(input.position(), skip))
                }
            }

            val bigIdEnabled = if (header.formatVersion in 7..13) {
                require(input.remaining() >= 4)
                input.int
            } else null

            require(input.remaining() >= 4)
            val objectCount = input.int
            require(objectCount in 0..MAX_OBJECTS)
            val minimumObjectBytes = when {
                header.formatVersion >= 22 -> 24L
                header.formatVersion >= 14 -> 19L
                header.formatVersion >= 11 -> 18L
                else -> 20L
            }
            require(objectCount.toLong() <= input.remaining().toLong() / minimumObjectBytes) {
                "SerializedFile object count exceeds remaining metadata"
            }

            val objects = ArrayList<UnitySerializedObjectInfo>(objectCount)
            val seenPathIds = HashSet<Long>(objectCount.coerceAtMost(1_000_000))

            repeat(objectCount) {
                val pathId = when {
                    bigIdEnabled != null && bigIdEnabled != 0 -> {
                        require(input.remaining() >= 8)
                        input.long
                    }
                    header.formatVersion < 14 -> {
                        require(input.remaining() >= 4)
                        input.int.toLong()
                    }
                    else -> {
                        alignAbsolute(4)
                        require(input.remaining() >= 8)
                        input.long
                    }
                }

                val byteStart = if (header.formatVersion >= 22) {
                    require(input.remaining() >= 8)
                    input.long
                } else {
                    readU32()
                }
                val byteSize = readU32()
                val typeId = input.int

                val classId = if (header.formatVersion < 16) {
                    input.short.toInt() and 0xffff
                } else null
                val destroyed = if (header.formatVersion < 11) {
                    input.short.toInt() and 0xffff
                } else null
                val scriptTypeIndex = if (header.formatVersion in 11..16) {
                    input.short.toInt()
                } else null
                val stripped = if (header.formatVersion == 15 || header.formatVersion == 16) {
                    input.get().toInt() and 0xff
                } else null

                require(seenPathIds.add(pathId)) { "Duplicate SerializedFile PathID" }
                require(byteStart >= 0L)
                require(byteStart <= header.fileSize - header.dataOffset)
                require(byteSize <= header.fileSize - header.dataOffset - byteStart)
                if (header.formatVersion >= 16) {
                    require(typeId in typeTable.records.indices) { "Object type index is out of range" }
                }

                val absoluteDataOffset = Math.addExact(header.dataOffset, byteStart)
                objects += UnitySerializedObjectInfo(
                    pathId, byteStart, absoluteDataOffset, byteSize, typeId,
                    classId, destroyed, scriptTypeIndex, stripped
                )
            }

            UnitySerializedObjectTable(objects, bigIdEnabled, input.position())
        }.getOrNull()
    }
}
