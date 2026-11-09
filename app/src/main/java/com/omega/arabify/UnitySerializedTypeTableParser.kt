package com.omega.arabify

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction

internal data class UnitySerializedTypeRecord(
    val classId: Int,
    val isStrippedType: Boolean?,
    val scriptTypeIndex: Int,
    val scriptId: ByteArray?,
    val oldTypeHash: ByteArray?,
    val typeTree: UnityTypeTree?,
    val typeTreeInfoHandler: UnityTypeTreeInfoHandler?,
    val typeDependencies: List<Int>,
    val referenceClassName: String?,
    val referenceNamespace: String?,
    val referenceAssemblyName: String?
)

internal data class UnitySerializedTypeTable(
    val records: List<UnitySerializedTypeRecord>,
    val bytesConsumed: Int
)

/**
 * Parses only SerializedType records after the metadata preamble. Object indices,
 * scripts, externals and object payloads remain outside this parser's scope.
 */
internal object UnitySerializedTypeTableParser {
    private const val MAX_METADATA_BYTES = 64 * 1024 * 1024
    private const val MAX_TYPES = 100_000
    private const val MAX_TYPE_DEPENDENCIES = 100_000
    private const val MAX_CSTRING_BYTES = 4096
    private const val MAX_TYPE_TREE_NODES = 250_000
    private const val MAX_TYPE_TREE_STRINGS = 16 * 1024 * 1024

    fun parse(
        header: UnitySerializedHeader,
        metadataPrefix: UnitySerializedMetadataPrefix,
        metadata: ByteArray,
        isReferenceType: Boolean = false,
        startOffset: Int = metadataPrefix.bytesConsumed,
        typeCount: Int = metadataPrefix.typeCount
    ): UnitySerializedTypeTable? {
        if (header.formatVersion !in 9..100 ||
            metadataPrefix.formatVersion != header.formatVersion ||
            metadata.size.toLong() != header.metadataSize ||
            metadata.size > MAX_METADATA_BYTES ||
            startOffset !in 0..metadata.size ||
            typeCount !in 0..MAX_TYPES
        ) return null

        val order = when (header.endian) {
            "little" -> ByteOrder.LITTLE_ENDIAN
            "big" -> ByteOrder.BIG_ENDIAN
            else -> return null
        }
        return runCatching {
            val input = ByteBuffer.wrap(metadata).order(order)
            input.position(startOffset)

            fun readBoolean(): Boolean = when (input.get().toInt() and 0xff) {
                0 -> false
                1 -> true
                else -> error("Invalid SerializedType boolean")
            }

            fun readBytes(count: Int): ByteArray {
                require(count >= 0 && count <= input.remaining())
                return ByteArray(count).also(input::get)
            }

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
                error("SerializedType string is unterminated or too long")
            }

            fun readTypeTreeBlob(formatVersion: Int): UnityTypeTree {
                require(input.remaining() >= 8)
                val start = input.position()
                val nodeCount = input.int
                val stringSize = input.int
                require(nodeCount in 1..MAX_TYPE_TREE_NODES)
                require(stringSize in 0..MAX_TYPE_TREE_STRINGS)
                val nodeSize = if (formatVersion >= 19) 32L else 24L
                val nodeBytes = Math.multiplyExact(nodeCount.toLong(), nodeSize)
                val blobSize = Math.addExact(8L, Math.addExact(nodeBytes, stringSize.toLong()))
                require(blobSize <= input.limit().toLong() - start)
                val end = Math.addExact(start, blobSize.toInt())
                val blob = metadata.copyOfRange(start, end)
                val parsed = UnityTypeTreeParser.parse(blob, formatVersion, order)
                    ?: error("Invalid SerializedType TypeTree blob")
                input.position(end)
                return parsed
            }

            fun readTypeTreeInfoHandler(): UnityTypeTreeInfoHandler {
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
                ) ?: error("Invalid SerializedType TypeTree info handler")
                input.position(end)
                return handler
            }

            val records = ArrayList<UnitySerializedTypeRecord>(typeCount)
            val typeTreeEnabled = metadataPrefix.typeTreeEnabled ?: true
            repeat(typeCount) {
                require(input.remaining() >= 4)
                val classId = input.int
                val isStripped = if (header.formatVersion >= 16) readBoolean() else null
                val scriptTypeIndex = if (header.formatVersion >= 17) input.short.toInt() else -1
                val scriptId = if (header.formatVersion >= 13 &&
                    ((isReferenceType && scriptTypeIndex >= 0) ||
                        (header.formatVersion < 16 && classId < 0) ||
                        (header.formatVersion >= 16 && classId == 114))
                ) readBytes(16) else null
                val oldTypeHash = if (header.formatVersion >= 13) readBytes(16) else null

                var tree: UnityTypeTree? = null
                var handler: UnityTypeTreeInfoHandler? = null
                val dependencies = mutableListOf<Int>()
                var referenceClassName: String? = null
                var referenceNamespace: String? = null
                var referenceAssemblyName: String? = null

                if (typeTreeEnabled) {
                    when {
                        header.formatVersion >= 23 -> {
                            handler = readTypeTreeInfoHandler()
                            tree = handler.info?.tree
                        }
                        header.formatVersion >= 12 || header.formatVersion == 10 -> {
                            tree = readTypeTreeBlob(header.formatVersion)
                        }
                        else -> error("Legacy recursive TypeTree format is not supported")
                    }

                    if (header.formatVersion >= 21) {
                        if (isReferenceType) {
                            referenceClassName = readCString()
                            referenceNamespace = readCString()
                            referenceAssemblyName = readCString()
                        } else {
                            require(input.remaining() >= 4)
                            val dependencyCount = input.int
                            require(dependencyCount in 0..MAX_TYPE_DEPENDENCIES)
                            val dependencyBytes = Math.multiplyExact(dependencyCount.toLong(), 4L)
                            require(dependencyBytes <= input.remaining().toLong())
                            repeat(dependencyCount) { dependencies += input.int }
                        }
                    }
                }

                records += UnitySerializedTypeRecord(
                    classId, isStripped, scriptTypeIndex, scriptId, oldTypeHash,
                    tree, handler, dependencies, referenceClassName,
                    referenceNamespace, referenceAssemblyName
                )
            }
            UnitySerializedTypeTable(records, input.position())
        }.getOrNull()
    }
}
