package com.omega.arabify

import java.io.ByteArrayOutputStream
import java.util.zip.Inflater

/**
 * Pure-Kotlin reader for sfnt fonts (TTF/OTF) and WOFF1 containers.
 * Extracts the profile the font tier needs to auto-select an Arabic font:
 * family/style names, weight, italic/monospace flags, bitmap strikes and
 * cmap coverage (Arabic block and the Arabic Presentation Forms ranges).
 * WOFF2 (brotli) and TrueType collections are reported, not parsed.
 */
object FontAnalyzer {

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
        val arabicGlyphs: Int,        // U+0600..U+06FF covered
        val presentationFormsA: Int,  // U+FB50..U+FDFF covered
        val presentationFormsB: Int,  // U+FE70..U+FEFF covered
        val ranges: List<IntRange>,
    ) {
        val hasArabicCore: Boolean get() = arabicGlyphs >= 60
        val pixelStyle: Boolean get() = bitmapStrikes ||
            familyName.contains("pixel", true) || familyName.contains("rooyin", true) ||
            familyName.contains("8bit", true)

        fun supports(cp: Int): Boolean = ranges.any { cp in it }
    }

    class FontParseException(message: String) : Exception(message)

    fun analyze(bytes: ByteArray): FontProfile {
        if (bytes.size < 12) throw FontParseException("too small")
        val tag = be32(bytes, 0)
        val tables: Map<String, ByteArray> = when (tag) {
            0x00010000L, 0x4F54544FL, 0x74727565L -> readSfntTables(bytes)
            0x774F4646L -> readWoff1Tables(bytes) // wOFF
            0x774F4632L -> throw FontParseException("WOFF2 needs brotli; not supported")
            0x74746366L -> throw FontParseException("TrueType collection; not supported")
            else -> throw FontParseException("unknown magic 0x${tag.toString(16)}")
        }
        val format = when (tag) {
            0x4F54544FL -> "OTF"
            0x774F4646L -> "WOFF1"
            else -> "TTF"
        }
        val head = tables["head"] ?: throw FontParseException("no head table")
        val unitsPerEm = u16(head, 18)
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
                val s = maxOf(lo, r.first); val e = minOf(hi, r.last)
                if (s <= e) n += e - s + 1
            }
            return n
        }
        return FontProfile(
            format = format,
            familyName = family,
            subfamily = subfamily,
            weightClass = weight,
            bold = macStyle and 0x1 != 0,
            italic = macStyle and 0x2 != 0,
            monospace = mono,
            bitmapStrikes = bitmap,
            unitsPerEm = unitsPerEm,
            arabicGlyphs = countIn(0x0600, 0x06FF),
            presentationFormsA = countIn(0xFB50, 0xFDFF),
            presentationFormsB = countIn(0xFE70, 0xFEFF),
            ranges = ranges,
        )
    }

    private fun be32(b: ByteArray, o: Int): Long =
        ((b[o].toLong() and 0xFF) shl 24) or ((b[o + 1].toLong() and 0xFF) shl 16) or
            ((b[o + 2].toLong() and 0xFF) shl 8) or (b[o + 3].toLong() and 0xFF)

    private fun u16(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)

    private fun readSfntTables(bytes: ByteArray): Map<String, ByteArray> {
        val count = u16(bytes, 4)
        val out = LinkedHashMap<String, ByteArray>()
        var o = 12
        repeat(count) {
            val tag = String(bytes, o, 4, Charsets.US_ASCII)
            val off = be32(bytes, o + 8).toInt()
            val len = be32(bytes, o + 12).toInt()
            if (off + len <= bytes.size) out[tag] = bytes.copyOfRange(off, off + len)
            o += 16
        }
        return out
    }

    private fun readWoff1Tables(bytes: ByteArray): Map<String, ByteArray> {
        if (bytes.size < 44) throw FontParseException("bad WOFF header")
        val count = u16(bytes, 12)
        val out = LinkedHashMap<String, ByteArray>()
        var o = 44
        repeat(count) {
            val tag = String(bytes, o, 4, Charsets.US_ASCII)
            val off = be32(bytes, o + 4).toInt()
            val compLen = be32(bytes, o + 8).toInt()
            val origLen = be32(bytes, o + 12).toInt()
            if (off + compLen <= bytes.size) {
                val raw = bytes.copyOfRange(off, off + compLen)
                out[tag] = if (compLen == origLen) raw else inflate(raw, origLen)
            }
            o += 20
        }
        return out
    }

    private fun inflate(data: ByteArray, expected: Int): ByteArray {
        val inf = Inflater()
        inf.setInput(data)
        val buf = ByteArray(expected)
        var done = 0
        val out = ByteArrayOutputStream(expected)
        while (!inf.finished() && done > 0 || done == 0) {
            val n = inf.inflate(buf)
            if (n <= 0) break
            out.write(buf, 0, n)
            done = n
        }
        inf.end()
        return out.toByteArray()
    }

    private fun readNames(table: ByteArray?): Pair<String, String> {
        if (table == null || table.size < 6) return "" to ""
        val count = u16(table, 2)
        val storage = u16(table, 4)
        var family = ""; var sub = ""
        var o = 6
        repeat(count) {
            if (o + 12 <= table.size) {
                val platform = u16(table, o)
                val nameId = u16(table, o + 6)
                val len = u16(table, o + 8)
                val off = u16(table, o + 10)
                if (storage + off + len <= table.size && (nameId == 1 || nameId == 2)) {
                    val raw = table.copyOfRange(storage + off, storage + off + len)
                    val text = if (platform == 3 || platform == 0) {
                        String(raw, Charsets.UTF_16BE)
                    } else String(raw, Charsets.ISO_8859_1)
                    if (nameId == 1 && family.isEmpty()) family = text
                    if (nameId == 2 && sub.isEmpty()) sub = text
                }
            }
            o += 12
        }
        return family to sub
    }

    private fun readCmap(cmap: ByteArray): List<IntRange> {
        if (cmap.size < 4) return emptyList()
        val count = u16(cmap, 2)
        var bestOffset = -1; var bestScore = -1
        var o = 4
        repeat(count) {
            if (o + 8 <= cmap.size) {
                val platform = u16(cmap, o)
                val encoding = u16(cmap, o + 2)
                val off = be32(cmap, o + 4).toInt()
                if (off + 2 <= cmap.size) {
                    val format = u16(cmap, off)
                    // prefer full-repertoire subtables: (3,10) fmt12 > (0,*) > (3,1) fmt4
                    val score = when {
                        format == 12 && platform == 3 && encoding == 10 -> 3
                        format == 12 && platform == 0 -> 2
                        format == 4 && (platform == 3 || platform == 0) -> 1
                        else -> 0
                    }
                    if (score > bestScore) { bestScore = score; bestOffset = off }
                }
            }
            o += 8
        }
        if (bestOffset < 0) return emptyList()
        val format = u16(cmap, bestOffset)
        val raw = when (format) {
            4 -> parseCmap4(cmap, bestOffset)
            12 -> parseCmap12(cmap, bestOffset)
            else -> emptyList()
        }
        return mergeRanges(raw)
    }

    private fun parseCmap4(cmap: ByteArray, off: Int): List<IntRange> {
        val segCount = u16(cmap, off + 6) / 2
        val endBase = off + 14
        val startBase = endBase + segCount * 2 + 2
        val deltaBase = startBase + segCount * 2
        val roBase = deltaBase + segCount * 2
        val out = ArrayList<IntRange>(segCount)
        for (i in 0 until segCount) {
            if (roBase + i * 2 + 2 > cmap.size) break
            val end = u16(cmap, endBase + i * 2)
            val start = u16(cmap, startBase + i * 2)
            if (start == 0xFFFF && end == 0xFFFF) continue
            // Coverage only needs the segment span; glyph id mapping is irrelevant here.
            if (start <= end) out.add(start..end)
        }
        return out
    }

    private fun parseCmap12(cmap: ByteArray, off: Int): List<IntRange> {
        val groups = be32(cmap, off + 12).toInt()
        val out = ArrayList<IntRange>(groups)
        var o = off + 16
        repeat(groups) {
            if (o + 12 <= cmap.size) {
                val start = be32(cmap, o).toInt()
                val end = be32(cmap, o + 4).toInt()
                if (start <= end && end < 0x110000) out.add(start..end)
            }
            o += 12
        }
        return out
    }

    private fun mergeRanges(rs: List<IntRange>): List<IntRange> {
        if (rs.isEmpty()) return rs
        val sorted = rs.sortedBy { it.first }
        val out = ArrayList<IntRange>()
        var cur = sorted[0]
        for (r in sorted.drop(1)) {
            if (r.first <= cur.last + 1) cur = cur.first..maxOf(cur.last, r.last)
            else { out.add(cur); cur = r }
        }
        out.add(cur)
        return out
    }
}
