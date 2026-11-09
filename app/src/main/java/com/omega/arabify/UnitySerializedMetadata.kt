package com.omega.arabify

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction

internal data class UnitySerializedMetadataPrefix(
    val formatVersion: Int,
    val unityVersion: String?,
    val targetPlatform: Int?,
    val typeTreeEnabled: Boolean?,
    val typeCount: Int,
    val bytesConsumed: Int
)

/**
 * Reads only the stable metadata preamble before serialized type records.
 * It intentionally stops before interpreting type records or object payloads.
 */
internal object UnitySerializedMetadataPrefixParser {
    private const val MAX_METADATA_BYTES = 64 * 1024 * 1024
    private const val MAX_UNITY_VERSION_BYTES = 1024
    private const val MAX_TYPES = 100_000
    private const val MIN_TYPE_RECORD_BYTES = 20

    fun parse(header: UnitySerializedHeader, metadata: ByteArray): UnitySerializedMetadataPrefix? {
        if (header.formatVersion !in 9..100) return null
        if (header.endian == null || metadata.isEmpty()) return null
        if (metadata.size.toLong() != header.metadataSize ||
            metadata.size > MAX_METADATA_BYTES
        ) return null

        val order = when (header.endian) {
            "little" -> ByteOrder.LITTLE_ENDIAN
            "big" -> ByteOrder.BIG_ENDIAN
            else -> return null
        }
        return runCatching {
            val input = ByteBuffer.wrap(metadata).order(order)

            fun readCString(maxBytes: Int): String {
                val start = input.position()
                var end = start
                while (input.hasRemaining() && end - start <= maxBytes) {
                    if (input.get().toInt() == 0) {
                        val length = end - start
                        val decoder = Charsets.UTF_8.newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                        return decoder.decode(ByteBuffer.wrap(metadata, start, length)).toString()
                    }
                    end++
                }
                error("Unterminated or oversized SerializedFile metadata string")
            }

            val unityVersion = if (header.formatVersion >= 7) {
                readCString(MAX_UNITY_VERSION_BYTES)
            } else null
            val targetPlatform = if (header.formatVersion >= 8) {
                require(input.remaining() >= 4)
                input.int
            } else null
            val typeTreeEnabled = if (header.formatVersion >= 13) {
                require(input.remaining() >= 1)
                when (input.get().toInt() and 0xff) {
                    0 -> false
                    1 -> true
                    else -> error("Invalid SerializedFile TypeTree enabled flag")
                }
            } else null

            require(input.remaining() >= 4)
            val typeCount = input.int
            require(typeCount in 0..MAX_TYPES)
            val minimumTypeBytes = Math.multiplyExact(typeCount.toLong(), MIN_TYPE_RECORD_BYTES.toLong())
            require(minimumTypeBytes <= input.remaining().toLong()) {
                "SerializedFile type records exceed remaining metadata"
            }

            UnitySerializedMetadataPrefix(
                header.formatVersion, unityVersion, targetPlatform,
                typeTreeEnabled, typeCount, input.position()
            )
        }.getOrNull()
    }
}
