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
                validateArchiveFlags(header)

                val alignedHeaderEnd = if (needsHeaderAlignment(header)) {
                    align16(header.headerSize.toLong())
                } else {
                    header.headerSize.toLong()
                }
                require(alignedHeaderEnd <= declaredSize)

                val compressedInfoSize = header.compressedBlocksInfoSize
                val uncompressedInfoSize = header.uncompressedBlocksInfoSize
                require(compressedInfoSize in 1..limits.maxCompressedBlockBytes)
                require(uncompressedInfoSize in 1..limits.maxBlockInfoBytes)
                require(compressedInfoSize <= Int.MAX_VALUE && uncompressedInfoSize <= Int.MAX_VALUE)

                val infoOffset = if (header.blocksInfoAtEnd) {
                    declaredSize - compressedInfoSize
                } else {
                    alignedHeaderEnd
                }
                val afterInfo = if (header.blocksInfoAtEnd) {
                    alignedHeaderEnd
                } else {
                    Math.addExact(alignedHeaderEnd, compressedInfoSize)
                }
                val dataStart = if ((header.flags and FLAG_BLOCK_INFO_NEEDS_PADDING_AT_START) != 0L) {
                    align16(afterInfo)
                } else {
                    afterInfo
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

    private const val FLAG_BLOCK_INFO_NEEDS_PADDING_AT_START = 0x200L
    private const val KNOWN_ARCHIVE_FLAGS = 0x3fL or 0x40L or 0x80L or 0x100L or 0x200L
    private const val ENCRYPTION_FLAGS = 0x400L or 0x1000L

    private fun validateArchiveFlags(header: UnityArchiveHeader) {
        require((header.flags and ENCRYPTION_FLAGS) == 0L) { "Encrypted UnityFS bundles are not supported" }
        require((header.flags and KNOWN_ARCHIVE_FLAGS.inv()) == 0L) { "Unknown UnityFS archive flags" }
        if ((header.flags and FLAG_BLOCK_INFO_NEEDS_PADDING_AT_START) != 0L) {
            val version = parseEngineVersion(header.engineVersion)
                ?: error("Ambiguous UnityFS 0x200 flag without a parseable engine revision")
            require(isPaddingFlagEra(version)) {
                "UnityFS 0x200 flag denotes legacy encryption for this engine revision"
            }
        }
    }

    private fun parseEngineVersion(value: String): Triple<Int, Int, Int>? {
        val match = Regex("(\\d+)\\.(\\d+)\\.(\\d+)").find(value) ?: return null
        return runCatching {
            Triple(match.groupValues[1].toInt(), match.groupValues[2].toInt(), match.groupValues[3].toInt())
        }.getOrNull()
    }

    private fun isPaddingFlagEra(version: Triple<Int, Int, Int>): Boolean {
        val (year, minor, patch) = version
        return when {
            year < 2020 -> false
            year == 2020 -> minor > 3 || (minor == 3 && patch > 34)
            year == 2021 -> minor > 3 || (minor == 3 && patch > 2)
            year == 2022 -> minor > 1 || (minor == 1 && patch > 1)
            else -> year > 2022
        }
    }

    private fun needsHeaderAlignment(header: UnityArchiveHeader): Boolean {
        if (header.formatVersion >= 7) return true
        val version = parseEngineVersion(header.engineVersion) ?: return false
        return version.first == 2019 && version.second == 4 && version.third >= 15
    }

    private fun align16(value: Long): Long = Math.addExact(value, (16L - (value and 15L)) and 15L)

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
