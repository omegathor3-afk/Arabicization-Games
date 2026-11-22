package com.omega.arabify

import java.io.ByteArrayOutputStream
import java.io.FilterOutputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry

/**
 * Counts bytes written to the local-file-header area so STORED APK entries can
 * carry a valid alignment extra field before ApkSigner sees the archive.
 */
internal class CountingOutputStream(output: OutputStream) : FilterOutputStream(output) {
    var bytesWritten: Long = 0L
        private set

    override fun write(value: Int) {
        out.write(value)
        bytesWritten = Math.addExact(bytesWritten, 1L)
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        out.write(buffer, offset, length)
        bytesWritten = Math.addExact(bytesWritten, length.toLong())
    }
}

internal object ApkZipEntryPolicy {
    private const val ALIGNMENT_EXTRA_ID = 0xD935
    private const val ZIP64_EXTRA_ID = 0x0001
    private const val EXTENDED_TIMESTAMP_EXTRA_ID = 0x5455
    private const val MIN_ALIGNMENT = 4
    private const val MAX_ALIGNMENT = 16_384
    private const val DOS_SAFE_MIN_MILLIS = 315_662_400_000L
    private const val DOS_SAFE_MAX_MILLIS = 4_354_689_600_000L
    private const val FALLBACK_DOS_TIME_MILLIS = 1_230_768_000_000L

    private data class FilteredExtra(
        val bytes: ByteArray,
        val alignment: Int?,
        val hasExtendedTimestamp: Boolean
    )

    /**
     * Creates an output entry that retains its compression method, except that
     * resources.arsc is always STORED. Every STORED file receives an alignment
     * field so ApkSigner can preserve the data offset after signing.
     */
    fun create(
        source: ZipEntry,
        replacement: ByteArray?,
        localHeaderOffset: Long
    ): ZipEntry {
        require(localHeaderOffset >= 0L)
        require(!source.isDirectory || replacement == null)

        val sourceMethod = source.method
        require(sourceMethod == ZipEntry.STORED || sourceMethod == ZipEntry.DEFLATED) {
            "Unsupported ZIP compression method"
        }
        val outputMethod = if (source.name == "resources.arsc") {
            ZipEntry.STORED
        } else {
            sourceMethod
        }

        val filteredExtra = filterExtraFields(source.extra ?: ByteArray(0))
        var outputExtra = filteredExtra.bytes
        val sourceTime = source.time
        val output = ZipEntry(source.name)
        if (sourceTime != -1L) {
            if (sourceTime in DOS_SAFE_MIN_MILLIS..DOS_SAFE_MAX_MILLIS) {
                output.time = sourceTime
            } else {
                // Avoid ZipOutputStream silently adding a timestamp extra field
                // after the alignment offset has already been calculated.
                output.time = FALLBACK_DOS_TIME_MILLIS
                val epochSeconds = sourceTime / 1_000L
                if (!filteredExtra.hasExtendedTimestamp &&
                    sourceTime >= 0L &&
                    epochSeconds <= 0xFFFF_FFFFL
                ) {
                    outputExtra = appendExtendedTimestamp(outputExtra, epochSeconds)
                }
            }
        }
        source.comment?.let { output.comment = it }
        output.method = outputMethod

        if (source.isDirectory) {
            if (outputMethod == ZipEntry.STORED) {
                output.size = 0L
                output.compressedSize = 0L
                output.crc = 0L
            }
            if (outputExtra.isNotEmpty()) output.extra = outputExtra
            return output
        }

        if (outputMethod == ZipEntry.STORED) {
            val size = replacement?.size?.toLong() ?: source.size
            val crc = replacement?.let(::crc32) ?: source.crc
            require(size >= 0L && crc >= 0L) {
                "STORED ZIP entries require a known size and CRC"
            }
            output.size = size
            output.compressedSize = size
            output.crc = crc

            val alignment = maxOf(MIN_ALIGNMENT, filteredExtra.alignment ?: MIN_ALIGNMENT)
            output.extra = appendAlignmentField(
                outputExtra,
                source.name,
                localHeaderOffset,
                alignment
            )
        } else if (outputExtra.isNotEmpty()) {
            output.extra = outputExtra
        }

        return output
    }

    private fun crc32(bytes: ByteArray): Long = CRC32().apply {
        update(bytes)
    }.value

    private fun filterExtraFields(extra: ByteArray): FilteredExtra {
        val retained = ByteArrayOutputStream(extra.size)
        var alignment: Int? = null
        var hasExtendedTimestamp = false
        var offset = 0

        while (offset < extra.size) {
            require(extra.size - offset >= 4) {
                "Malformed ZIP extra field header"
            }
            val id = readUnsignedShort(extra, offset)
            val dataSize = readUnsignedShort(extra, offset + 2)
            require(dataSize <= extra.size - offset - 4) {
                "Malformed ZIP extra field length"
            }

            when {
                id == ALIGNMENT_EXTRA_ID -> {
                    require(dataSize >= 2) { "Malformed APK alignment extra field" }
                    val declaredAlignment = readUnsignedShort(extra, offset + 4)
                    require(declaredAlignment in MIN_ALIGNMENT..MAX_ALIGNMENT) {
                        "Unsupported APK data alignment"
                    }
                    require(alignment == null || alignment == declaredAlignment) {
                        "Conflicting APK alignment extra fields"
                    }
                    alignment = declaredAlignment
                }
                id == EXTENDED_TIMESTAMP_EXTRA_ID -> {
                    hasExtendedTimestamp = true
                    retained.write(extra, offset, 4 + dataSize)
                }
                id == ZIP64_EXTRA_ID || (id == 0 && dataSize == 0) -> Unit
                else -> retained.write(extra, offset, 4 + dataSize)
            }
            offset += 4 + dataSize
        }

        return FilteredExtra(retained.toByteArray(), alignment, hasExtendedTimestamp)
    }

    private fun appendExtendedTimestamp(originalExtra: ByteArray, epochSeconds: Long): ByteArray {
        val resultSize = Math.addExact(originalExtra.size, 9)
        require(resultSize <= 0xFFFF) { "ZIP extra fields exceed the format limit" }
        val result = ByteArray(resultSize)
        originalExtra.copyInto(result)
        val fieldOffset = originalExtra.size
        writeUnsignedShort(result, fieldOffset, EXTENDED_TIMESTAMP_EXTRA_ID)
        writeUnsignedShort(result, fieldOffset + 2, 5)
        result[fieldOffset + 4] = 0x01
        for (index in 0 until 4) {
            result[fieldOffset + 5 + index] = (epochSeconds ushr (index * 8)).toByte()
        }
        return result
    }

    private fun appendAlignmentField(
        originalExtra: ByteArray,
        entryName: String,
        localHeaderOffset: Long,
        alignment: Int
    ): ByteArray {
        val encodedNameLength = entryName.toByteArray(Charsets.UTF_8).size
        require(encodedNameLength <= 0xFFFF) { "ZIP entry name is too long" }

        val extraStartOffset = Math.addExact(
            localHeaderOffset,
            30L + encodedNameLength.toLong()
        )
        val minimumDataOffset = Math.addExact(
            extraStartOffset,
            originalExtra.size.toLong() + 6L
        )
        val padding = ((alignment - (minimumDataOffset % alignment)) % alignment).toInt()
        val fieldDataSize = 2 + padding
        val totalSize = Math.addExact(originalExtra.size, 4 + fieldDataSize)
        require(totalSize <= 0xFFFF) { "ZIP extra fields exceed the format limit" }

        val result = ByteArray(totalSize)
        originalExtra.copyInto(result)
        val fieldOffset = originalExtra.size
        writeUnsignedShort(result, fieldOffset, ALIGNMENT_EXTRA_ID)
        writeUnsignedShort(result, fieldOffset + 2, fieldDataSize)
        writeUnsignedShort(result, fieldOffset + 4, alignment)
        return result
    }

    private fun readUnsignedShort(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun writeUnsignedShort(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value and 0xFF).toByte()
        bytes[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    }
}
