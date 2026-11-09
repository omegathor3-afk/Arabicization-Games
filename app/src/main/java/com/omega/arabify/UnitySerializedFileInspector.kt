package com.omega.arabify

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal enum class UnitySerializedInspectionStage {
    HEADER_ONLY,
    METADATA_PREFIX_PARSED,
    TYPE_TABLE_PARSED,
    OBJECT_TABLE_PARSED
}

internal data class UnitySerializedFileInspection(
    val header: UnitySerializedHeader,
    val metadataPrefix: UnitySerializedMetadataPrefix?,
    val typeTable: UnitySerializedTypeTable?,
    val objectTable: UnitySerializedObjectTable?,
    val stage: UnitySerializedInspectionStage
)

/**
 * Streams only the SerializedFile header and bounded metadata region.
 * Object payload bytes are never loaded.
 */
internal object UnitySerializedFileInspector {
    private const val MAX_METADATA_BYTES = 64 * 1024 * 1024

    fun inspect(input: InputStream, actualLength: Long): UnitySerializedFileInspection? {
        if (actualLength < 20L) return null
        return runCatching {
            val first16 = readExactly(input, 16)
            val version = ByteBuffer.wrap(first16).order(ByteOrder.BIG_ENDIAN).getInt(8)
            require(version in 9..100) { "Legacy SerializedFile versions below 9 are not supported here" }
            val headerSize = if (version >= 22) 48 else 20
            val headerBytes = first16 + readExactly(input, headerSize - first16.size)
            val header = UnitySerializedFileHeaderParser.parse(headerBytes, actualLength)
                ?: error("Invalid SerializedFile header")
            require(header.metadataSize in 1..MAX_METADATA_BYTES.toLong())
            require(header.metadataSize <= Int.MAX_VALUE.toLong())
            val metadata = readExactly(input, header.metadataSize.toInt())

            val prefix = UnitySerializedMetadataPrefixParser.parse(header, metadata)
                ?: return@runCatching UnitySerializedFileInspection(
                    header, null, null, null, UnitySerializedInspectionStage.HEADER_ONLY
                )
            val types = UnitySerializedTypeTableParser.parse(header, prefix, metadata)
                ?: return@runCatching UnitySerializedFileInspection(
                    header, prefix, null, null, UnitySerializedInspectionStage.METADATA_PREFIX_PARSED
                )
            val objects = UnitySerializedObjectTableParser.parse(header, types, metadata)
            UnitySerializedFileInspection(
                header,
                prefix,
                types,
                objects,
                if (objects != null) UnitySerializedInspectionStage.OBJECT_TABLE_PARSED
                else UnitySerializedInspectionStage.TYPE_TABLE_PARSED
            )
        }.getOrNull()
    }

    private fun readExactly(input: InputStream, count: Int): ByteArray {
        require(count >= 0)
        val result = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = input.read(result, offset, count - offset)
            require(read > 0) { "Unexpected end of SerializedFile" }
            offset += read
        }
        return result
    }
}
