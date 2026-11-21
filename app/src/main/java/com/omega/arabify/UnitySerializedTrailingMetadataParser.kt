package com.omega.arabify

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction

internal data class UnityLocalSerializedObjectIdentifier(
    val localSerializedFileIndex: Int,
    val localIdentifierInFile: Long
)

internal data class UnitySerializedExternalReference(
    val temporaryEmpty: String?,
    val guid: ByteArray?,
    val type: Int?,
    val path: String
)

internal data class UnitySerializedTrailingMetadata(
    val scriptReferences: List<UnityLocalSerializedObjectIdentifier>,
    val externals: List<UnitySerializedExternalReference>,
    val referenceTypes: List<UnitySerializedTypeRecord>,
    val sharedSubtreeHandlers: List<UnityTypeTreeInfoHandler>,
    val userInformation: String?,
    val bytesConsumed: Int,
    val unknownTailBytes: Int
)

/**
 * Bounded parser for metadata sections following the SerializedFile object table.
 * It reads script identifiers, external file references, v20+ reference types,
 * v26+ shared TypeTree handlers, and user information. It never loads object data.
 */
internal object UnitySerializedTrailingMetadataParser {
    private const val MAX_METADATA_BYTES = 64 * 1024 * 1024
    private const val MAX_ENTRIES = 100_000
    private const val MAX_CSTRING_BYTES = 4096

    fun parse(
        header: UnitySerializedHeader,
        metadataPrefix: UnitySerializedMetadataPrefix,
        metadata: ByteArray,
        objectTable: UnitySerializedObjectTable
    ): UnitySerializedTrailingMetadata? {
        if (header.formatVersion !in 9..100 ||
            metadata.size.toLong() != header.metadataSize ||
            metadata.size > MAX_METADATA_BYTES ||
            objectTable.bytesConsumed !in 0..metadata.size
        ) return null

        val order = when (header.endian) {
            "little" -> ByteOrder.LITTLE_ENDIAN
            "big" -> ByteOrder.BIG_ENDIAN
            else -> return null
        }

        return runCatching {
            val input = ByteBuffer.wrap(metadata).order(order)
            input.position(objectTable.bytesConsumed)

            fun readCString(): String {
                val start = input.position()
                var end = start
                while (input.hasRemaining() && end - start <= MAX_CSTRING_BYTES) {
                    if (input.get().toInt() == 0) {
                        val decoder = Charsets.UTF_8.newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                        return decoder.decode(ByteBuffer.wrap(metadata, start, end - start)).toString()
                    }
                    end++
                }
                error("Unterminated or oversized SerializedFile string")
            }

            fun alignAbsolute(alignment: Int) {
                val absolute = Math.addExact(header.headerSize.toLong(), input.position().toLong())
                val remainder = (absolute % alignment).toInt()
                if (remainder != 0) {
                    val skip = alignment - remainder
                    require(skip <= input.remaining())
                    input.position(Math.addExact(input.position(), skip))
                }
            }

            fun readCount(minimumBytesPerEntry: Int, label: String): Int {
                require(input.remaining() >= 4)
                val count = input.int
                require(count in 0..MAX_ENTRIES) { "Invalid $label count" }
                require(count.toLong() <= input.remaining().toLong() / minimumBytesPerEntry) {
                    "$label count exceeds remaining metadata"
                }
                return count
            }

            val scripts = mutableListOf<UnityLocalSerializedObjectIdentifier>()
            if (header.formatVersion >= 11) {
                val count = readCount(if (header.formatVersion < 14) 8 else 12, "script reference")
                repeat(count) {
                    require(input.remaining() >= 4)
                    val fileIndex = input.int
                    val localId = if (header.formatVersion < 14) {
                        require(input.remaining() >= 4)
                        input.int.toLong()
                    } else {
                        alignAbsolute(4)
                        require(input.remaining() >= 8)
                        input.long
                    }
                    scripts += UnityLocalSerializedObjectIdentifier(fileIndex, localId)
                }
            }

            val externals = mutableListOf<UnitySerializedExternalReference>()
            val externalCount = readCount(if (header.formatVersion >= 6) 22 else 17, "external reference")
            repeat(externalCount) {
                val temporaryEmpty = if (header.formatVersion >= 6) readCString() else null
                val guid: ByteArray?
                val type: Int?
                if (header.formatVersion >= 5) {
                    require(input.remaining() >= 20)
                    guid = ByteArray(16).also(input::get)
                    type = input.int
                } else {
                    guid = null
                    type = null
                }
                val path = readCString()
                externals += UnitySerializedExternalReference(temporaryEmpty, guid, type, path)
            }

            val referenceTypes = if (header.formatVersion >= 20) {
                val count = readCount(4, "reference type")
                val parsed = UnitySerializedTypeTableParser.parse(
                    header,
                    metadataPrefix,
                    metadata,
                    isReferenceType = true,
                    startOffset = input.position(),
                    typeCount = count
                ) ?: error("Invalid SerializedFile reference type table")
                input.position(parsed.bytesConsumed)
                parsed.records
            } else emptyList()

            val sharedSubtrees = mutableListOf<UnityTypeTreeInfoHandler>()
            if (header.formatVersion >= 26) {
                val count = readCount(20, "shared TypeTree subtree")
                repeat(count) {
                    val start = input.position()
                    require(input.remaining() >= 20)
                    input.position(start + 16)
                    val serializedSize = input.int.toLong() and 0xffffffffL
                    require(serializedSize <= MAX_METADATA_BYTES)
                    val payloadBytes = if (serializedSize >= 8L) serializedSize.toInt() else 0
                    require(payloadBytes <= input.remaining())
                    val end = Math.addExact(input.position(), payloadBytes)
                    val handler = UnityTypeTreeInfoHandlerParser.parse(
                        metadata.copyOfRange(start, end), order
                    ) ?: error("Invalid shared TypeTree info handler")
                    input.position(end)
                    sharedSubtrees += handler
                }
            }

            val userInformation = if (header.formatVersion >= 5) readCString() else null
            UnitySerializedTrailingMetadata(
                scripts,
                externals,
                referenceTypes,
                sharedSubtrees,
                userInformation,
                input.position(),
                input.remaining()
            )
        }.getOrNull()
    }
}
