package com.omega.arabify

import java.nio.ByteBuffer
import java.nio.ByteOrder

internal data class UnityTypeTreeInfo(
    val typeTreeFormatVersion: Int,
    val tree: UnityTypeTree,
    val referenceHashes: List<ByteArray>
)

internal data class UnityTypeTreeInfoHandler(
    val contentHash: ByteArray,
    val serializedSize: Long,
    val info: UnityTypeTreeInfo?
)

/**
 * Reads the v23+ TypeTreeNodeInfoHandler envelope:
 * 16-byte content hash, serialized-size field, then an optional "mhtt" payload.
 */
internal object UnityTypeTreeInfoHandlerParser {
    private const val HASH_BYTES = 16
    private const val MAX_INFO_BYTES = 64 * 1024 * 1024
    private const val MAX_REFERENCE_HASHES = 100_000

    fun parse(bytes: ByteArray, byteOrder: ByteOrder = ByteOrder.LITTLE_ENDIAN): UnityTypeTreeInfoHandler? {
        if (bytes.size < HASH_BYTES + 4 || bytes.size > MAX_INFO_BYTES + HASH_BYTES + 4) return null
        return runCatching {
            val input = ByteBuffer.wrap(bytes).order(byteOrder)
            val contentHash = ByteArray(HASH_BYTES).also(input::get)
            val serializedSize = input.int.toLong() and 0xffffffffL
            require(serializedSize <= MAX_INFO_BYTES)
            require(serializedSize == input.remaining().toLong())

            if (serializedSize < 8L) {
                require(!input.hasRemaining())
                return@runCatching UnityTypeTreeInfoHandler(contentHash, serializedSize, null)
            }

            val infoBytes = ByteArray(serializedSize.toInt()).also(input::get)
            val infoInput = ByteBuffer.wrap(infoBytes).order(byteOrder)
            val magic = ByteArray(4).also(infoInput::get)
            require(magic.contentEquals(byteArrayOf(0x6d, 0x68, 0x74, 0x74))) {
                "Invalid TypeTree info magic"
            }
            val typeTreeFormatVersion = infoInput.int
            require(typeTreeFormatVersion in 12..100)
            val treeStart = infoInput.position()
            require(infoInput.remaining() >= 8)
            val nodeCount = infoInput.int
            val stringBufferSize = infoInput.int
            require(nodeCount in 1..250_000)
            require(stringBufferSize in 0..(16 * 1024 * 1024))
            val nodeSize = if (typeTreeFormatVersion >= 19) 32L else 24L
            val nodeDataBytes = Math.multiplyExact(nodeCount.toLong(), nodeSize)
            val treeBlobSize = Math.addExact(8L, Math.addExact(nodeDataBytes, stringBufferSize.toLong()))
            require(treeBlobSize <= infoInput.limit().toLong() - treeStart)
            val treeBlobEnd = Math.addExact(treeStart, treeBlobSize.toInt())
            val treeBlob = infoBytes.copyOfRange(treeStart, treeBlobEnd)
            val tree = UnityTypeTreeParser.parse(treeBlob, typeTreeFormatVersion, byteOrder)
                ?: error("Invalid TypeTree node/string-buffer blob")
            infoInput.position(treeBlobEnd)
            require(infoInput.remaining() >= 4)
            val referenceHashCount = infoInput.int.toLong() and 0xffffffffL
            require(referenceHashCount <= MAX_REFERENCE_HASHES)
            val referenceBytes = Math.multiplyExact(referenceHashCount, HASH_BYTES.toLong())
            require(referenceBytes == infoInput.remaining().toLong())
            val hashes = ArrayList<ByteArray>(referenceHashCount.toInt())
            repeat(referenceHashCount.toInt()) {
                hashes += ByteArray(HASH_BYTES).also(infoInput::get)
            }
            require(!infoInput.hasRemaining())
            UnityTypeTreeInfoHandler(
                contentHash,
                serializedSize,
                UnityTypeTreeInfo(typeTreeFormatVersion, tree, hashes)
            )
        }.getOrNull()
    }
}
