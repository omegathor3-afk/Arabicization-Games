package com.omega.arabify

import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.zip.DataFormatException
import java.util.zip.Inflater

private val iconFontFamilyMarkers = listOf(
    "fontello", "font awesome", "material icons", "material symbols", "icomoon",
    "glyphicons", "wingdings", "webdings", "entypo", "ionicons", "octicons",
    "segoe mdl2 assets", "segoe fluent icons"
)

private fun isKnownIconFontFamily(familyName: String): Boolean {
    val normalized = familyName.lowercase(Locale.ROOT).replace(Regex("[_-]+"), " ").trim()
    return iconFontFamilyMarkers.any { marker ->
        normalized == marker || normalized.startsWith("$marker ") || normalized.contains(" $marker ")
    }
}

/** Bounded pure-Kotlin reader for sfnt fonts (TTF/OTF) and WOFF1 containers. */
object FontAnalyzer {
    private const val MAX_FONT_BYTES = 32 * 1024 * 1024
    private const val MAX_TABLE_BYTES = 8 * 1024 * 1024
    private const val MAX_TOTAL_TABLE_BYTES = 32 * 1024 * 1024
    private const val MAX_TABLE_COUNT = 256
    private const val MAX_CMAP_SUBTABLES = 1024
    private const val MAX_CMAP_SEGMENTS = 8192
    private const val MAX_CMAP_GROUPS = 100_000

    data class FontProfile(
        val format: String,
        val familyName: String,
        val subfamily: String,
        val weightClass: Int,
        val bold: Boolean,
        val italic: Boolean,
        val monospace: Boolean,
        val bitmapStrikes: Boolean,
        val unitsPerEm: Int,
        val arabicGlyphs: Int,
        val presentationFormsA: Int,
        val presentationFormsB: Int,
        val ranges: List<IntRange>,
    ) {
        val hasArabicCore: Boolean get() = arabicGlyphs >= 60
        val pixelStyle: Boolean get() = bitmapStrikes ||
            familyName.contains("pixel", true) || familyName.contains("rooyin", true) ||
            familyName.contains("8bit", true)
        /** Known icon-only families must never be replaced by a text font. */
        val isIconFont: Boolean get() = isKnownIconFontFamily(familyName)
        fun supports(cp: Int): Boolean = ranges.any { cp in it }
    }

    class FontParseException(message: String) : Exception(message)

    fun analyze(bytes: ByteArray): FontProfile {
        if (bytes.size < 12) throw FontParseException("too small")
        if (bytes.size > MAX_FONT_BYTES) throw FontParseException("font exceeds the 32 MiB input limit")
        val tag = be32(bytes, 0)
        val tables = when (tag) {
            0x00010000L, 0x4F54544FL, 0x74727565L -> readSfntTables(bytes)
            0x774F4646L -> readWoff1Tables(bytes)
            0x774F4632L -> throw FontParseException("WOFF2 needs brotli; not supported")
            0x74746366L -> throw FontParseException("TrueType collection; not supported")
            else -> throw FontParseException("unknown magic 0x" + tag.toString(16))
        }
        val format = when (tag) {
            0x4F54544FL -> "OTF"
            0x774F4646L -> "WOFF1"
            else -> "TTF"
        }
        val head = tables["head"] ?: throw FontParseException("no head table")
        if (head.size < 46) throw FontParseException("truncated head table")
        val unitsPerEm = u16(head, 18)
        if (unitsPerEm !in 16..16384) throw FontParseException("invalid unitsPerEm")
        val macStyle = u16(head, 44)
        val os2 = tables["OS/2"]
        val weight = if (os2 != null && os2.size >= 6) u16(os2, 4) else 400
        val post = tables["post"]
        val mono = post != null && post.size >= 16 && be32(post, 12) != 0L
        val bitmap = tables.containsKey("EBDT") || tables.containsKey("CBDT") || tables.containsKey("sbix")
        val (family, subfamily) = readNames(tables["name"])
        val ranges = readCmap(tables["cmap"] ?: throw FontParseException("no cmap table"))
        fun countIn(lo: Int, hi: Int): Int {
            var n = 0
            for (r in ranges) {
                val s = maxOf(lo, r.first)
                val e = minOf(hi, r.last)
                if (s <= e) n += e - s + 1
            }
            return n
        }
        return FontProfile(
            format, family, subfamily, weight, macStyle and 1 != 0, macStyle and 2 != 0,
            mono, bitmap, unitsPerEm, countIn(0x0600, 0x06FF),
            countIn(0xFB50, 0xFDFF), countIn(0xFE70, 0xFEFF), ranges
        )
    }

    private fun checkRange(bytes: ByteArray, offset: Int, length: Int, label: String) {
        if (offset < 0 || length < 0 || offset.toLong() + length.toLong() > bytes.size.toLong()) {
            throw FontParseException("truncated or out-of-range " + label)
        }
    }

    private fun be32(b: ByteArray, o: Int): Long {
        checkRange(b, o, 4, "32-bit field")
        return ((b[o].toLong() and 0xFF) shl 24) or ((b[o + 1].toLong() and 0xFF) shl 16) or
            ((b[o + 2].toLong() and 0xFF) shl 8) or (b[o + 3].toLong() and 0xFF)
    }

    private fun u16(b: ByteArray, o: Int): Int {
        checkRange(b, o, 2, "16-bit field")
        return ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)
    }

    private fun readSfntTables(bytes: ByteArray): Map<String, ByteArray> {
        val count = u16(bytes, 4)
        if (count !in 1..MAX_TABLE_COUNT) throw FontParseException("invalid sfnt table count")
        val directoryEnd = 12L + count.toLong() * 16L
        if (directoryEnd > bytes.size.toLong()) throw FontParseException("truncated sfnt table directory")
        val out = LinkedHashMap<String, ByteArray>()
        var total = 0L
        repeat(count) { index ->
            val o = 12 + index * 16
            val tag = String(bytes, o, 4, Charsets.US_ASCII)
            val offset = be32(bytes, o + 8)
            val length = be32(bytes, o + 12)
            if (length == 0L) return@repeat
            if (length > MAX_TABLE_BYTES.toLong()) throw FontParseException("sfnt table exceeds the 8 MiB limit: " + tag)
            if (offset < directoryEnd || offset + length > bytes.size.toLong()) {
                throw FontParseException("sfnt table is outside the input: " + tag)
            }
            if (out.containsKey(tag)) throw FontParseException("duplicate sfnt table: " + tag)
            total += length
            if (total > MAX_TOTAL_TABLE_BYTES.toLong()) throw FontParseException("sfnt tables exceed the 32 MiB aggregate limit")
            out[tag] = bytes.copyOfRange(offset.toInt(), (offset + length).toInt())
        }
        return out
    }

    private fun readWoff1Tables(bytes: ByteArray): Map<String, ByteArray> {
        if (bytes.size < 44) throw FontParseException("bad WOFF header")
        if (be32(bytes, 8) != bytes.size.toLong()) throw FontParseException("WOFF length does not match the input")
        val count = u16(bytes, 12)
        if (count !in 1..MAX_TABLE_COUNT) throw FontParseException("invalid WOFF table count")
        if (u16(bytes, 14) != 0) throw FontParseException("WOFF reserved field is not zero")
        val directoryEnd = 44L + count.toLong() * 20L
        if (directoryEnd > bytes.size.toLong()) throw FontParseException("truncated WOFF table directory")
        val totalSfntSize = be32(bytes, 16)
        if (totalSfntSize < 12L + count.toLong() * 16L || totalSfntSize > MAX_FONT_BYTES.toLong()) {
            throw FontParseException("WOFF reconstructed font size is outside the safe limit")
        }
        val out = LinkedHashMap<String, ByteArray>()
        val dataRanges = mutableListOf<LongRange>()
        var totalOriginalBytes = 0L
        repeat(count) { index ->
            val o = 44 + index * 20
            val tag = String(bytes, o, 4, Charsets.US_ASCII)
            val offset = be32(bytes, o + 4)
            val compressedLength = be32(bytes, o + 8)
            val originalLength = be32(bytes, o + 12)
            if (compressedLength == 0L || originalLength == 0L || compressedLength > originalLength) {
                throw FontParseException("invalid WOFF table lengths: " + tag)
            }
            if (originalLength > MAX_TABLE_BYTES.toLong() || compressedLength > MAX_TABLE_BYTES.toLong()) {
                throw FontParseException("WOFF table exceeds the 8 MiB limit: " + tag)
            }
            if (offset % 4L != 0L || offset < directoryEnd || offset + compressedLength > bytes.size.toLong()) {
                throw FontParseException("WOFF table is outside the input: " + tag)
            }
            if (out.containsKey(tag)) throw FontParseException("duplicate WOFF table: " + tag)
            val range = offset until (offset + compressedLength)
            if (dataRanges.any { range.first <= it.last && it.first <= range.last }) {
                throw FontParseException("overlapping WOFF table data: " + tag)
            }
            dataRanges += range
            totalOriginalBytes += originalLength
            if (totalOriginalBytes > MAX_TOTAL_TABLE_BYTES.toLong()) {
                throw FontParseException("WOFF tables exceed the 32 MiB aggregate limit")
            }
            val raw = bytes.copyOfRange(offset.toInt(), (offset + compressedLength).toInt())
            out[tag] = if (compressedLength == originalLength) raw else inflate(raw, originalLength.toInt())
        }
        return out
    }

    private fun inflate(data: ByteArray, expected: Int): ByteArray {
        if (expected !in 1..MAX_TABLE_BYTES) throw FontParseException("WOFF decompressed table exceeds the 8 MiB limit")
        val inflater = Inflater()
        try {
            inflater.setInput(data)
            val output = ByteArrayOutputStream(minOf(expected, 64 * 1024))
            val buffer = ByteArray(8192)
            var produced = 0
            while (!inflater.finished()) {
                val count = try {
                    inflater.inflate(buffer)
                } catch (error: DataFormatException) {
                    throw FontParseException("invalid WOFF compressed table")
                }
                if (count > 0) {
                    if (produced + count > expected) throw FontParseException("WOFF table expands beyond its declared length")
                    output.write(buffer, 0, count)
                    produced += count
                } else if (inflater.needsDictionary()) {
                    throw FontParseException("WOFF table requires an unsupported compression dictionary")
                } else if (inflater.needsInput()) {
                    break
                } else {
                    throw FontParseException("WOFF decompressor made no progress")
                }
            }
            if (!inflater.finished() || produced != expected || inflater.remaining != 0) {
                throw FontParseException("WOFF decompressed length does not match its declaration")
            }
            return output.toByteArray()
        } finally {
            inflater.end()
        }
    }

    private fun readNames(table: ByteArray?): Pair<String, String> {
        if (table == null) return "" to ""
        if (table.size < 6) throw FontParseException("truncated name table")
        val count = u16(table, 2)
        val storage = u16(table, 4)
        val recordsEnd = 6L + count.toLong() * 12L
        if (recordsEnd > table.size.toLong() || storage.toLong() < recordsEnd || storage > table.size) {
            throw FontParseException("invalid name table directory")
        }
        var family = ""
        var subfamily = ""
        var o = 6
        repeat(count) {
            val platform = u16(table, o)
            val nameId = u16(table, o + 6)
            val length = u16(table, o + 8)
            val offset = u16(table, o + 10)
            val start = storage.toLong() + offset.toLong()
            val end = start + length.toLong()
            if (end > table.size.toLong()) throw FontParseException("name record is outside the name table")
            if ((nameId == 1 || nameId == 2) && (platform == 3 || platform == 0) && length % 2 != 0) {
                throw FontParseException("odd UTF-16 name record length")
            }
            if (nameId == 1 || nameId == 2) {
                val raw = table.copyOfRange(start.toInt(), end.toInt())
                val value = if (platform == 3 || platform == 0) String(raw, Charsets.UTF_16BE)
                    else String(raw, Charsets.ISO_8859_1)
                if (nameId == 1 && family.isEmpty()) family = value
                if (nameId == 2 && subfamily.isEmpty()) subfamily = value
            }
            o += 12
        }
        return family to subfamily
    }

    private fun readCmap(cmap: ByteArray): List<IntRange> {
        if (cmap.size < 4) throw FontParseException("truncated cmap table")
        val count = u16(cmap, 2)
        if (count > MAX_CMAP_SUBTABLES || 4L + count.toLong() * 8L > cmap.size.toLong()) {
            throw FontParseException("invalid cmap encoding-record count")
        }
        var bestOffset = -1
        var bestScore = -1
        var o = 4
        repeat(count) {
            val platform = u16(cmap, o)
            val encoding = u16(cmap, o + 2)
            val offset = be32(cmap, o + 4)
            if (offset + 2L <= cmap.size.toLong()) {
                val subtableOffset = offset.toInt()
                val format = u16(cmap, subtableOffset)
                val score = when {
                    format == 12 && platform == 3 && encoding == 10 -> 3
                    format == 12 && platform == 0 -> 2
                    format == 4 && (platform == 3 || platform == 0) -> 1
                    else -> 0
                }
                if (score > bestScore) {
                    bestScore = score
                    bestOffset = subtableOffset
                }
            }
            o += 8
        }
        if (bestOffset < 0 || bestScore <= 0) return emptyList()
        val raw = when (u16(cmap, bestOffset)) {
            4 -> parseCmap4(cmap, bestOffset)
            12 -> parseCmap12(cmap, bestOffset)
            else -> emptyList()
        }
        return mergeRanges(raw)
    }

    private fun parseCmap4(cmap: ByteArray, offset: Int): List<IntRange> {
        checkRange(cmap, offset, 14, "cmap format 4 header")
        val length = u16(cmap, offset + 2)
        if (length < 16 || offset.toLong() + length.toLong() > cmap.size.toLong()) {
            throw FontParseException("invalid cmap format 4 length")
        }
        val segmentBytes = u16(cmap, offset + 6)
        if (segmentBytes == 0 || segmentBytes % 2 != 0) throw FontParseException("invalid cmap format 4 segment count")
        val segments = segmentBytes / 2
        if (segments > MAX_CMAP_SEGMENTS || 16L + segments.toLong() * 8L > length.toLong()) {
            throw FontParseException("cmap format 4 segment table exceeds its bounds")
        }
        val endBase = offset + 14
        val startBase = endBase + segments * 2 + 2
        val out = ArrayList<IntRange>(segments)
        for (i in 0 until segments) {
            val end = u16(cmap, endBase + i * 2)
            val start = u16(cmap, startBase + i * 2)
            if (start == 0xFFFF && end == 0xFFFF) continue
            if (start <= end) out.add(start..end)
        }
        return out
    }

    private fun parseCmap12(cmap: ByteArray, offset: Int): List<IntRange> {
        checkRange(cmap, offset, 16, "cmap format 12 header")
        val length = be32(cmap, offset + 4)
        val groups = be32(cmap, offset + 12)
        if (length < 16L || offset.toLong() + length > cmap.size.toLong() ||
            groups > MAX_CMAP_GROUPS.toLong() || 16L + groups * 12L > length) {
            throw FontParseException("cmap format 12 groups exceed their bounds")
        }
        val out = ArrayList<IntRange>(groups.toInt())
        var o = offset + 16
        repeat(groups.toInt()) {
            val first = be32(cmap, o)
            val last = be32(cmap, o + 4)
            if (first > last || last >= 0x110000L) throw FontParseException("invalid cmap format 12 codepoint range")
            out.add(first.toInt()..last.toInt())
            o += 12
        }
        return out
    }

    private fun mergeRanges(rs: List<IntRange>): List<IntRange> {
        if (rs.isEmpty()) return rs
        val sorted = rs.sortedBy { it.first }
        val out = ArrayList<IntRange>()
        var current = sorted[0]
        for (range in sorted.drop(1)) {
            if (range.first <= current.last + 1) current = current.first..maxOf(current.last, range.last)
            else {
                out.add(current)
                current = range
            }
        }
        out.add(current)
        return out
    }
}
