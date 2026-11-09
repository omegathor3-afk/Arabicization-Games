package com.omega.arabify

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction

internal data class UnityTypeTreeNode(
    val version: Int,
    val level: Int,
    val typeFlags: Int,
    val typeStringOffset: Long,
    val nameStringOffset: Long,
    val byteSize: Int,
    val index: Int,
    val metaFlags: Int,
    val refTypeHash: Long?,
    val typeName: String?,
    val fieldName: String?
)

internal data class UnityTypeTree(
    val nodes: List<UnityTypeTreeNode>,
    val stringBufferBytes: Int,
    val unresolvedCommonStringReferences: Int
)

/**
 * Bounded reader for the flat TypeTree node/string-buffer representation used by
 * SerializedFile format versions 12+. It preserves raw offsets and does not infer
 * object layouts from unknown/common string references.
 */
internal object UnityTypeTreeParser {
    private const val MAX_NODES = 250_000
    private const val MAX_STRING_BUFFER_BYTES = 16 * 1024 * 1024
    private const val BASE_NODE_BYTES = 23
    private const val REF_NODE_BYTES = 31
    private const val COMMON_STRING_FLAG = 0x80000000L

    fun parse(
        bytes: ByteArray,
        serializedFormatVersion: Int,
        byteOrder: ByteOrder = ByteOrder.LITTLE_ENDIAN
    ): UnityTypeTree? {
        if (serializedFormatVersion !in 12..100 || bytes.size < 8) return null
        return runCatching {
            val input = ByteBuffer.wrap(bytes).order(byteOrder)
            val nodeCount = input.int
            val stringBufferSize = input.int
            require(nodeCount in 1..MAX_NODES)
            require(stringBufferSize in 0..MAX_STRING_BUFFER_BYTES)
            val nodeBytes = if (serializedFormatVersion >= 19) REF_NODE_BYTES else BASE_NODE_BYTES
            val requiredNodeBytes = Math.multiplyExact(nodeCount.toLong(), nodeBytes.toLong())
            require(requiredNodeBytes <= input.remaining().toLong())
            require(stringBufferSize.toLong() <= input.remaining().toLong() - requiredNodeBytes)

            data class RawNode(
                val version: Int, val level: Int, val flags: Int,
                val typeOffset: Long, val nameOffset: Long,
                val byteSize: Int, val index: Int, val metaFlags: Int,
                val refHash: Long?
            )

            val raw = ArrayList<RawNode>(nodeCount)
            var previousLevel = -1
            repeat(nodeCount) {
                val version = input.get().toInt() and 0xff
                val level = input.get().toInt() and 0xff
                val flags = input.get().toInt() and 0xff
                val typeOffset = input.int.toLong() and 0xffffffffL
                val nameOffset = input.int.toLong() and 0xffffffffL
                val byteSize = input.int
                val index = input.int
                val metaFlags = input.int
                val refHash = if (serializedFormatVersion >= 19) input.long else null
                require(if (raw.isEmpty()) level == 0 else level in 1..(previousLevel + 1))
                require(byteSize >= -1)
                raw += RawNode(version, level, flags, typeOffset, nameOffset, byteSize, index, metaFlags, refHash)
                previousLevel = level
            }

            val strings = ByteArray(stringBufferSize)
            input.get(strings)
            require(!input.hasRemaining())

            fun resolve(offset: Long): String? {
                if ((offset and COMMON_STRING_FLAG) != 0L) return null
                require(offset < strings.size.toLong()) { "TypeTree local string offset is out of range" }
                val start = offset.toInt()
                var end = start
                while (end < strings.size && strings[end].toInt() != 0) end++
                require(end < strings.size) { "TypeTree local string is not NUL-terminated" }
                val decoder = Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                return decoder.decode(ByteBuffer.wrap(strings, start, end - start)).toString()
            }

            val nodes = raw.map { node ->
                UnityTypeTreeNode(
                    node.version, node.level, node.flags,
                    node.typeOffset, node.nameOffset, node.byteSize,
                    node.index, node.metaFlags, node.refHash,
                    resolve(node.typeOffset), resolve(node.nameOffset)
                )
            }
            val unresolved = nodes.sumOf {
                (if (it.typeName == null) 1 else 0) + (if (it.fieldName == null) 1 else 0)
            }
            UnityTypeTree(nodes, strings.size, unresolved)
        }.getOrNull()
    }
}
