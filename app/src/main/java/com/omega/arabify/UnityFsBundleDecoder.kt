package com.omega.arabify

import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile

internal data class UnityFsDecodeLimits(
    val maxCompressedBlockBytes: Long = 64L * 1024L * 1024L,
    val maxDecodedBlockBytes: Long = 64L * 1024L * 1024L,
    val maxTotalDecodedBytes: Long = 512L * 1024L * 1024L,
    val maxBlockInfoBytes: Long = 64L * 1024L * 1024L,
    val maxLzmaMemoryBytes: Long = 64L * 1024L * 1024L
)

internal data class UnityFsDecodeResult(
    val header: UnityArchiveHeader,
    val blockInfo: UnityBlockInfo,
    val decodedBytes: Long
)

/**
 * Read-only UnityFS decoder. It streams decoded storage blocks to a caller-owned
 * OutputStream and never extracts CAB nodes or modifies the source bundle.
 */
internal object UnityFsBundleDecoder {
    fun decode(
        file: File,
        output: OutputStream,
        limits: UnityFsDecodeLimits = UnityFsDecodeLimits()
    ): UnityFsDecodeResult? {
        if (!validLimits(limits) || !file.isFile) return null
        return runCatching {
            RandomAccessFile(file, "r").use { input ->
                val actualLength = input.length()
                require(actualLength > 0L)
                val prefixSize = minOf(actualLength, 1024L).toInt()
                val prefix = ByteArray(prefixSize)
                input.seek(0L)
                input.readFully(prefix)
                val header = UnityArchiveHeaderParser.parse(prefix, actualLength)
                    ?: error("Invalid Unity archive header")
                require(header.signature == "UnityFS")
                val declaredSize = header.declaredSize
                require(declaredSize <= actualLength)

                val compressedInfoSize = header.compressedBlocksInfoSize
                val uncompressedInfoSize = header.uncompressedBlocksInfoSize
                require(compressedInfoSize in 1..limits.maxCompressedBlockBytes)
                require(uncompressedInfoSize in 1..limits.maxBlockInfoBytes)
                require(compressedInfoSize <= Int.MAX_VALUE && uncompressedInfoSize <= Int.MAX_VALUE)

                val infoOffset = if (header.blocksInfoAtEnd) {
                    declaredSize - compressedInfoSize
                } else {
                    header.headerSize.toLong()
                }
                val dataStart = if (header.blocksInfoAtEnd) {
                    header.headerSize.toLong()
                } else {
                    Math.addExact(header.headerSize.toLong(), compressedInfoSize)
                }
                val dataEnd = if (header.blocksInfoAtEnd) infoOffset else declaredSize
                require(infoOffset >= header.headerSize.toLong())
                require(dataStart >= header.headerSize.toLong())
                require(dataEnd >= dataStart && dataEnd <= declaredSize)
                require(infoOffset + compressedInfoSize <= declaredSize)

                val headerCodec = unityCompressionCodecFromFlags(header.flags)
                    ?: error("Unsupported UnityFS block-info compression")
                val compressedInfo = readRange(input, infoOffset, compressedInfoSize.toInt())
                val decodedInfo = UnityFsBlockDecompressor.decompress(
                    headerCodec,
                    compressedInfo,
                    uncompressedInfoSize,
                    maxOutputBytes = limits.maxBlockInfoBytes,
                    maxCompressedBytes = limits.maxCompressedBlockBytes,
                    maxLzmaMemoryBytes = limits.maxLzmaMemoryBytes
                )
                val blockInfo = UnityBlockInfoParser.parse(decodedInfo)
                    ?: error("Invalid UnityFS block-info metadata")

                var expectedCompressedDataBytes = 0L
                blockInfo.blocks.forEach { block ->
                    require(block.compressedSize in 1..limits.maxCompressedBlockBytes)
                    require(block.uncompressedSize in 1..limits.maxDecodedBlockBytes)
                    expectedCompressedDataBytes = Math.addExact(
                        expectedCompressedDataBytes, block.compressedSize
                    )
                }
                require(expectedCompressedDataBytes == dataEnd - dataStart) {
                    "UnityFS storage blocks do not exactly cover the declared data region"
                }
                require(blockInfo.totalUncompressedBytes <= limits.maxTotalDecodedBytes)

                var cursor = dataStart
                var totalDecoded = 0L
                for (block in blockInfo.blocks) {
                    val compressedBytes = readRange(input, cursor, block.compressedSize.toInt())
                    cursor = Math.addExact(cursor, block.compressedSize)
                    val codec = unityCompressionCodecFromFlags(block.flags.toLong())
                        ?: error("Unsupported UnityFS storage-block compression")
                    totalDecoded = Math.addExact(totalDecoded, block.uncompressedSize)
                    require(totalDecoded <= limits.maxTotalDecodedBytes)
                    val decoded = UnityFsBlockDecompressor.decompress(
                        codec,
                        compressedBytes,
                        block.uncompressedSize,
                        maxOutputBytes = limits.maxDecodedBlockBytes,
                        maxCompressedBytes = limits.maxCompressedBlockBytes,
                        maxLzmaMemoryBytes = limits.maxLzmaMemoryBytes
                    )
                    output.write(decoded)
                }
                require(cursor == dataEnd)
                require(totalDecoded == blockInfo.totalUncompressedBytes)
                output.flush()
                UnityFsDecodeResult(header, blockInfo, totalDecoded)
            }
        }.getOrNull()
    }

    private fun readRange(input: RandomAccessFile, offset: Long, size: Int): ByteArray {
        require(offset >= 0L && size > 0)
        require(offset <= input.length() && size.toLong() <= input.length() - offset)
        return ByteArray(size).also {
            input.seek(offset)
            input.readFully(it)
        }
    }

    private fun validLimits(limits: UnityFsDecodeLimits): Boolean =
        limits.maxCompressedBlockBytes in 1..Int.MAX_VALUE.toLong() &&
            limits.maxDecodedBlockBytes in 1..Int.MAX_VALUE.toLong() &&
            limits.maxTotalDecodedBytes > 0L &&
            limits.maxBlockInfoBytes in 1..Int.MAX_VALUE.toLong() &&
            limits.maxLzmaMemoryBytes >= 1024L
}
