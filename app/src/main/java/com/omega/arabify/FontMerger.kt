package com.omega.arabify

import java.io.ByteArrayOutputStream
import java.util.TreeMap
import java.util.zip.Deflater

/**
 * Arabic glyph-merge tier: appends Arabic glyphs from a licensed catalog font
 * INTO the game's own font. Every original glyph stays byte-identical, so
 * icon-merged fonts (Mindustry's Fontello font.woff) keep their icons and
 * Latin look while Arabic codepoints become renderable. TrueType-outline
 * fonts (glyf/loca) only; WOFF1 in -> WOFF1 out, sfnt in -> sfnt out.
 * Outlines and metrics are rescaled when the catalog font's unitsPerEm
 * differs. Hinting instructions of appended glyphs are dropped (the game's
 * own cvt/fpgm/prep tables are kept verbatim); shaping lives in ArabicShaper.
 */
internal object FontMerger {
    private const val MAX_GLYPH_COUNT = 65535
    private const val MAX_CMAP4_SEGMENTS = 4000
    private const val MAX_NEW_GLYPHS = 5000

    class FontMergeException(message: String) : Exception(message)

    data class MergeResult(
        val fontBytes: ByteArray,
        val glyphsAdded: Int,
        val codepointsAdded: Int,
        val scaledToGameUpm: Boolean,
    )

    /** Everything ArabicShaper output can contain: base block + Presentation Forms-B. */
    private val arabicRanges = listOf(0x0600..0x06FF, 0xFE70..0xFEFF)

    fun mergeArabicInto(gameFont: ByteArray, catalogFont: ByteArray): MergeResult {
        val game = GlyfFont.parse(gameFont, "game")
        val catalog = GlyfFont.parse(catalogFont, "catalog")

        // Target codepoints: in the catalog, not already in the game font.
        val wanted = LinkedHashMap<Int, Int>() // cp -> catalog gid
        for (range in arabicRanges) {
            for (cp in range) {
                if (game.cmap.containsKey(cp)) continue
                val gid = catalog.cmap[cp] ?: continue
                if (gid in 1 until catalog.numGlyphs) wanted[cp] = gid
            }
        }
        if (wanted.isEmpty()) throw FontMergeException("catalog font has no Arabic glyphs the game font lacks")

        // Transitive closure over composite components.
        val needed = LinkedHashSet<Int>()
        val queue = ArrayDeque<Int>()
        for (gid in wanted.values) if (needed.add(gid)) queue.add(gid)
        while (queue.isNotEmpty()) {
            val gid = queue.removeFirst()
            for (component in catalog.compositeComponents(gid)) {
                if (component in 0 until catalog.numGlyphs && needed.add(component)) queue.add(component)
            }
        }
        if (needed.size > MAX_NEW_GLYPHS) throw FontMergeException("Arabic subset too large: ${needed.size} glyphs")
        if (game.numGlyphs + needed.size > MAX_GLYPH_COUNT) {
            throw FontMergeException("merged font would exceed the 65535 glyph limit")
        }

        // New glyph ids: game glyphs first (untouched), then catalog glyphs sorted by gid.
        val ordered = needed.sorted()
        val remap = HashMap<Int, Int>(ordered.size * 2)
        ordered.forEachIndexed { index, gid -> remap[gid] = game.numGlyphs + index }
        val cpToNewGid = wanted.mapValues { remap.getValue(it.value) }

        val scale = game.unitsPerEm.toDouble() / catalog.unitsPerEm.toDouble()
        val scaled = scale != 1.0

        // Build the new glyf table: original glyph bytes verbatim, then transformed catalog glyphs.
        val glyfOut = ByteArrayOutputStream()
        val loca = LongArray(game.numGlyphs + ordered.size + 1)
        for (gid in 0 until game.numGlyphs) {
            loca[gid] = glyfOut.size().toLong()
            glyfOut.write(game.rawGlyph(gid))
        }
        val newBbox = intArrayOf(Int.MAX_VALUE, Int.MAX_VALUE, Int.MIN_VALUE, Int.MIN_VALUE)
        var maxPoints = 0
        var maxContours = 0
        var maxCompPoints = 0
        var maxCompContours = 0
        var maxCompElements = 0
        var maxCompDepth = 0
        for (gid in ordered) {
            loca[remap.getValue(gid)] = glyfOut.size().toLong()
            val built = buildGlyph(catalog, gid, remap, scale)
            glyfOut.write(built.bytes)
            if (built.xMin < newBbox[0]) newBbox[0] = built.xMin
            if (built.yMin < newBbox[1]) newBbox[1] = built.yMin
            if (built.xMax > newBbox[2]) newBbox[2] = built.xMax
            if (built.yMax > newBbox[3]) newBbox[3] = built.yMax
            if (built.composite) {
                if (built.points > maxCompPoints) maxCompPoints = built.points
                if (built.contours > maxCompContours) maxCompContours = built.contours
                if (built.elements > maxCompElements) maxCompElements = built.elements
                if (built.depth > maxCompDepth) maxCompDepth = built.depth
            } else {
                if (built.points > maxPoints) maxPoints = built.points
                if (built.contours > maxContours) maxContours = built.contours
            }
        }
        loca[loca.size - 1] = glyfOut.size().toLong()

        // hmtx: full long form, one entry per glyph.
        val numGlyphs = game.numGlyphs + ordered.size
        val hmtx = ByteArrayOutputStream(numGlyphs * 4)
        for (gid in 0 until game.numGlyphs) {
            w16(hmtx, game.advance(gid)); w16(hmtx, game.lsb(gid))
        }
        for (gid in ordered) {
            w16(hmtx, scaleMetric(catalog.advance(gid), scale))
            w16(hmtx, scaleMetric(catalog.lsb(gid), scale))
        }

        val tables = LinkedHashMap(game.tables)
        tables["glyf"] = glyfOut.toByteArray()
        tables["loca"] = buildLoca(loca)
        tables["hmtx"] = hmtx.toByteArray()
        tables["hhea"] = patchHhea(game, numGlyphs)
        tables["maxp"] = patchMaxp(game, numGlyphs, maxPoints, maxContours, maxCompPoints,
            maxCompContours, maxCompElements, maxCompDepth)
        tables["head"] = patchHead(game, newBbox)
        tables["cmap"] = buildCmap(game.cmap + cpToNewGid)
        tables["post"] = buildPost(game)

        val sfnt = assembleSfnt(tables)
        val bytes = if (game.format == "WOFF1") {
            // Carry the computed checkSumAdjustment into the wrapped head table so a
            // reconstructed sfnt stays self-consistent.
            val headTag = "head"
            val headTable = tables.getValue(headTag).copyOf()
            val sfntHead = findTable(sfnt, headTag)
            System.arraycopy(sfntHead, 8, headTable, 8, 4)
            tables[headTag] = headTable
            wrapWoff1(tables, sfnt)
        } else {
            sfnt
        }
        return MergeResult(bytes, ordered.size, cpToNewGid.size, scaled)
    }

    // ---- glyph building -------------------------------------------------

    private class BuiltGlyph(
        val bytes: ByteArray, val composite: Boolean,
        val xMin: Int, val yMin: Int, val xMax: Int, val yMax: Int,
        val points: Int, val contours: Int, val elements: Int, val depth: Int,
    )

    private fun buildGlyph(font: GlyfFont, gid: Int, remap: Map<Int, Int>, scale: Double): BuiltGlyph {
        val raw = font.rawGlyph(gid)
        if (raw.size < 10) {
            return BuiltGlyph(ByteArray(0), false, 0, 0, 0, 0, 0, 0, 0, 0)
        }
        val contours = i16(raw, 0)
        return if (contours >= 0) buildSimple(font, raw, contours, scale)
        else buildComposite(font, raw, remap, scale)
    }

    private fun sc(v: Int, scale: Double): Int = (v * scale).toInt()
    private fun scaleMetric(v: Int, scale: Double): Int = (v * scale).toInt().coerceIn(0, 65535)

    private fun buildSimple(font: GlyfFont, raw: ByteArray, contours: Int, scale: Double): BuiltGlyph {
        val endPts = IntArray(contours) { u16(raw, 10 + it * 2) }
        val points = endPts.last() + 1
        val flags = BooleanArray(points * 6) // onCurve, xShort, yShort, xSame, ySame per point (repeat expanded)
        var p = 10 + contours * 2
        val instructionLength = u16(raw, p)
        p += 2 + instructionLength // appended glyphs drop hinting instructions
        // expand flags
        val onCurve = BooleanArray(points)
        val xShort = BooleanArray(points); val yShort = BooleanArray(points)
        val xSame = BooleanArray(points); val ySame = BooleanArray(points)
        var i = 0
        while (i < points) {
            val f = raw[p].toInt() and 0xFF; p++
            var repeat = 1
            if (f and 0x08 != 0) { repeat += raw[p].toInt() and 0xFF; p++ }
            for (k in 0 until repeat) {
                if (i + k >= points) break
                onCurve[i + k] = f and 0x01 != 0
                xShort[i + k] = f and 0x02 != 0
                yShort[i + k] = f and 0x04 != 0
                xSame[i + k] = f and 0x10 != 0
                ySame[i + k] = f and 0x20 != 0
            }
            i += repeat
        }
        val xs = IntArray(points)
        run {
            var x = 0
            for (j in 0 until points) {
                val d = when {
                    xShort[j] -> { val v = raw[p].toInt() and 0xFF; p++; if (xSame[j]) v else -v }
                    xSame[j] -> 0
                    else -> { val v = i16(raw, p); p += 2; v }
                }
                x += d; xs[j] = x
            }
        }
        val ys = IntArray(points)
        run {
            var y = 0
            for (j in 0 until points) {
                val d = when {
                    yShort[j] -> { val v = raw[p].toInt() and 0xFF; p++; if (ySame[j]) v else -v }
                    ySame[j] -> 0
                    else -> { val v = i16(raw, p); p += 2; v }
                }
                y += d; ys[j] = y
            }
        }
        val sxs = IntArray(points) { sc(xs[it], scale) }
        val sys = IntArray(points) { sc(ys[it], scale) }

        val out = ByteArrayOutputStream()
        w16(out, contours)
        val xMin = if (points > 0) sxs.min() else 0
        val yMin = if (points > 0) sys.min() else 0
        val xMax = if (points > 0) sxs.max() else 0
        val yMax = if (points > 0) sys.max() else 0
        w16(out, xMin); w16(out, yMin); w16(out, xMax); w16(out, yMax)
        for (e in endPts) w16(out, e)
        w16(out, 0) // no instructions on appended glyphs
        var prev = 0
        val xDeltas = IntArray(points) { val d = sxs[it] - prev; prev = sxs[it]; d }
        prev = 0
        val yDeltas = IntArray(points) { val d = sys[it] - prev; prev = sys[it]; d }
        for (j in 0 until points) {
            var f = if (onCurve[j]) 0x01 else 0
            f = f or encodeDeltaFlags(xDeltas[j], 0x02, 0x10)
            f = f or encodeDeltaFlags(yDeltas[j], 0x04, 0x20)
            out.write(f)
        }
        for (j in 0 until points) writeDelta(out, xDeltas[j])
        for (j in 0 until points) writeDelta(out, yDeltas[j])
        return BuiltGlyph(out.toByteArray(), false, xMin, yMin, xMax, yMax, points, contours, 0, 0)
    }

    private fun encodeDeltaFlags(d: Int, shortBit: Int, sameBit: Int): Int = when {
        d == 0 -> sameBit
        d in 1..255 -> shortBit or sameBit
        d in -255..-1 -> shortBit
        else -> 0
    }

    private fun writeDelta(out: ByteArrayOutputStream, d: Int) {
        when {
            d == 0 -> Unit
            d in 1..255 -> out.write(d)
            d in -255..-1 -> out.write(-d)
            else -> w16(out, d)
        }
    }

    private fun buildComposite(font: GlyfFont, raw: ByteArray, remap: Map<Int, Int>, scale: Double): BuiltGlyph {
        val out = ByteArrayOutputStream()
        w16(out, -1)
        val xMin = sc(i16(raw, 2), scale); val yMin = sc(i16(raw, 4), scale)
        val xMax = sc(i16(raw, 6), scale); val yMax = sc(i16(raw, 8), scale)
        w16(out, xMin); w16(out, yMin); w16(out, xMax); w16(out, yMax)
        var p = 10
        var elements = 0
        while (true) {
            val flags = u16(raw, p)
            val component = u16(raw, p + 2)
            p += 4
            if (flags and 0x0002 == 0) {
                throw FontMergeException("composite glyph uses point-matching arguments; unsupported")
            }
            val arg1: Int; val arg2: Int
            if (flags and 0x0001 != 0) { arg1 = i16(raw, p); arg2 = i16(raw, p + 2); p += 4 }
            else { arg1 = raw[p].toInt(); arg2 = raw[p + 1].toInt(); p += 2 }
            val scaleBytes = when {
                flags and 0x0008 != 0 -> { val b = raw.copyOfRange(p, p + 2); p += 2; b }
                flags and 0x0040 != 0 -> { val b = raw.copyOfRange(p, p + 4); p += 4; b }
                flags and 0x0080 != 0 -> { val b = raw.copyOfRange(p, p + 8); p += 8; b }
                else -> null
            }
            val more = flags and 0x0020 != 0
            val kept = flags and (0x0004 or 0x0008 or 0x0040 or 0x0080 or 0x0200 or 0x0400 or 0x0800 or 0x1000)
            val newFlags = kept or 0x0001 or 0x0002 or (if (more) 0x0020 else 0)
            val newGid = remap[component]
                ?: throw FontMergeException("composite references glyph outside the Arabic subset")
            w16(out, newFlags); w16(out, newGid)
            w16(out, sc(arg1, scale)); w16(out, sc(arg2, scale))
            if (scaleBytes != null) out.write(scaleBytes)
            elements++
            if (!more) break
        }
        // Source WE_HAVE_INSTRUCTIONS tail is dropped: appended glyphs carry no hinting.
        return BuiltGlyph(out.toByteArray(), true, xMin, yMin, xMax, yMax, 0, 0, elements, 1)
    }

    // ---- table rebuild ---------------------------------------------------

    private fun buildLoca(loca: LongArray): ByteArray {
        val out = ByteArrayOutputStream(loca.size * 4)
        for (o in loca) w32(out, o)
        return out.toByteArray()
    }

    private fun patchHhea(game: GlyfFont, numGlyphs: Int): ByteArray {
        val hhea = game.tables.getValue("hhea").copyOf()
        if (hhea.size < 36) throw FontMergeException("truncated hhea table")
        put16(hhea, 34, numGlyphs)
        return hhea
    }

    private fun patchMaxp(
        game: GlyfFont, numGlyphs: Int, maxPoints: Int, maxContours: Int,
        maxCompPoints: Int, maxCompContours: Int, maxCompElements: Int, maxCompDepth: Int,
    ): ByteArray {
        val maxp = game.tables.getValue("maxp").copyOf()
        if (maxp.size < 32 || be32(maxp, 0) != 0x00010000L) {
            throw FontMergeException("maxp is not a TrueType version 1.0 table")
        }
        fun grow(field: Int, value: Int) { if (value > u16(maxp, field)) put16(maxp, field, value) }
        put16(maxp, 4, numGlyphs)
        grow(6, maxPoints); grow(8, maxContours)
        grow(10, maxCompPoints); grow(12, maxCompContours)
        grow(26, maxCompElements); grow(28, maxCompDepth)
        return maxp
    }

    private fun patchHead(game: GlyfFont, newBbox: IntArray): ByteArray {
        val head = game.tables.getValue("head").copyOf()
        if (head.size < 54) throw FontMergeException("truncated head table")
        put32(head, 8, 0) // checkSumAdjustment computed after assembly
        if (newBbox[0] != Int.MAX_VALUE) {
            if (newBbox[0] < i16(head, 36)) put16(head, 36, newBbox[0])
            if (newBbox[1] < i16(head, 38)) put16(head, 38, newBbox[1])
            if (newBbox[2] > i16(head, 40)) put16(head, 40, newBbox[2])
            if (newBbox[3] > i16(head, 42)) put16(head, 42, newBbox[3])
        }
        put16(head, 50, 1) // long loca
        return head
    }

    private fun buildPost(game: GlyfFont): ByteArray {
        // Format 3.0: no glyph names; appended glyphs have none and glyph count changed.
        val out = ByteArrayOutputStream(32)
        w32(out, 0x00030000)
        val src = game.tables["post"]
        val body = ByteArray(28)
        if (src != null && src.size >= 32) System.arraycopy(src, 4, body, 0, 28)
        out.write(body)
        return out.toByteArray()
    }

    private fun buildCmap(mapping: Map<Int, Int>): ByteArray {
        val fmt4 = buildCmapFormat4(mapping)
        val fmt12 = buildCmapFormat12(mapping)
        val out = ByteArrayOutputStream()
        w16(out, 0); w16(out, 4) // version, 4 encoding records
        val headerSize = 4 + 4 * 8
        val off4 = headerSize
        val off12 = headerSize + fmt4.size
        // (0,3) + (3,1) share the format-4 subtable; (0,4) + (3,10) share format 12.
        w16(out, 0); w16(out, 3); w32(out, off4.toLong())
        w16(out, 0); w16(out, 4); w32(out, off12.toLong())
        w16(out, 3); w16(out, 1); w32(out, off4.toLong())
        w16(out, 3); w16(out, 10); w32(out, off12.toLong())
        out.write(fmt4); out.write(fmt12)
        return out.toByteArray()
    }

    private fun buildCmapFormat4(mapping: Map<Int, Int>): ByteArray {
        val bmp = TreeMap<Int, Int>()
        for ((cp, gid) in mapping) if (cp in 0..0xFFFF && gid in 1..0xFFFF) bmp[cp] = gid
        // Segments: maximal runs with a constant (gid - cp) delta.
        val segments = ArrayList<Triple<Int, Int, Int>>() // start, end, delta
        var start = -1; var end = -1; var delta = 0
        for ((cp, gid) in bmp) {
            val d = (gid - cp) and 0xFFFF
            if (start >= 0 && cp == end + 1 && d == delta) {
                end = cp
            } else {
                if (start >= 0) segments.add(Triple(start, end, delta))
                start = cp; end = cp; delta = d
            }
        }
        if (start >= 0) segments.add(Triple(start, end, delta))
        if (segments.size + 1 > MAX_CMAP4_SEGMENTS) {
            throw FontMergeException("cmap format 4 would need ${segments.size + 1} segments")
        }
        val segCount = segments.size + 1
        val segCountX2 = segCount * 2
        var searchRange = 1; var entrySelector = -1
        while (searchRange * 2 <= segCount) { searchRange *= 2; entrySelector++ }
        searchRange *= 2
        val rangeShift = segCountX2 - searchRange
        val out = ByteArrayOutputStream()
        w16(out, 4); w16(out, 16 + 8 * segCount); w16(out, 0)
        w16(out, segCountX2); w16(out, searchRange); w16(out, entrySelector); w16(out, rangeShift)
        for (s in segments) w16(out, s.second)
        w16(out, 0xFFFF)
        w16(out, 0) // reservedPad
        for (s in segments) w16(out, s.first)
        w16(out, 0xFFFF)
        for (s in segments) w16(out, s.third)
        w16(out, 1)
        repeat(segCount) { w16(out, 0) } // idRangeOffset: all segments use idDelta
        return out.toByteArray()
    }

    /** Locates a table inside a built sfnt for post-assembly patching. */
    private fun findTable(sfnt: ByteArray, tag: String): ByteArray {
        val count = u16(sfnt, 4)
        for (i in 0 until count) {
            val o = 12 + i * 16
            if (String(sfnt, o, 4, Charsets.US_ASCII) == tag) {
                val offset = be32(sfnt, o + 8).toInt()
                val length = be32(sfnt, o + 12).toInt()
                return sfnt.copyOfRange(offset, offset + length)
            }
        }
        throw FontMergeException("table missing from assembled sfnt: " + tag)
    }

    private fun buildCmapFormat12(mapping: Map<Int, Int>): ByteArray {
        val sorted = mapping.entries.filter { it.value >= 1 }.sortedBy { it.key }
        val groups = ArrayList<Triple<Int, Int, Int>>() // firstCp, lastCp, firstGid
        var first = -1; var last = -1; var firstGid = -1
        for ((cp, gid) in sorted) {
            if (gid < 1) continue
            if (first >= 0 && cp == last + 1 && gid == firstGid + (cp - first)) {
                last = cp
            } else {
                if (first >= 0) groups.add(Triple(first, last, firstGid))
                first = cp; last = cp; firstGid = gid
            }
        }
        if (first >= 0) groups.add(Triple(first, last, firstGid))
        val out = ByteArrayOutputStream()
        w16(out, 12); w16(out, 0); w32(out, (16 + groups.size * 12).toLong()); w32(out, 0); w32(out, groups.size.toLong())
        for (g in groups) { w32(out, g.first.toLong()); w32(out, g.second.toLong()); w32(out, g.third.toLong()) }
        return out.toByteArray()
    }

    // ---- sfnt / WOFF1 writing --------------------------------------------

    private fun checksum(table: ByteArray): Long {
        var sum = 0L
        var i = 0
        while (i + 4 <= table.size) {
            sum = (sum + be32(table, i)) and 0xFFFFFFFFL
            i += 4
        }
        if (i < table.size) {
            var last = 0L
            for (k in i until table.size) last = (last shl 8) or (table[k].toLong() and 0xFF)
            last = last shl (8 * (4 - (table.size - i)))
            sum = (sum + last) and 0xFFFFFFFFL
        }
        return sum
    }

    private fun assembleSfnt(tables: Map<String, ByteArray>): ByteArray {
        val tags = tables.keys.sortedBy { tagBytes(it).fold(0L) { a, b -> (a shl 8) or (b.toLong() and 0xFF) } }
        val numTables = tags.size
        var searchRange = 1; var entrySelector = -1
        while (searchRange * 2 <= numTables) { searchRange *= 2; entrySelector++ }
        searchRange *= 16
        val rangeShift = numTables * 16 - searchRange
        val out = ByteArrayOutputStream()
        w32(out, 0x00010000)
        w16(out, numTables); w16(out, searchRange); w16(out, entrySelector); w16(out, rangeShift)
        var offset = 12 + numTables * 16
        val directory = ArrayList<Pair<String, Int>>(tags.size)
        for (tag in tags) {
            directory += tag to offset
            offset += (tables.getValue(tag).size + 3) and 3.inv()
        }
        tags.forEachIndexed { index, tag ->
            out.write(tagBytes(tag))
            w32(out, checksum(tables.getValue(tag)))
            w32(out, directory[index].second.toLong())
            w32(out, tables.getValue(tag).size.toLong())
        }
        for (tag in tags) {
            out.write(tables.getValue(tag))
            while (out.size() % 4 != 0) out.write(0)
        }
        val bytes = out.toByteArray()
        // checkSumAdjustment: patch into the head table (directory checksum used 0).
        val headOffset = directory[tags.indexOf("head")].second
        val adjustment = (0xB1B0AFBAL - checksum(bytes)) and 0xFFFFFFFFL
        bytes[headOffset + 8] = ((adjustment ushr 24) and 0xFF).toByte()
        bytes[headOffset + 9] = ((adjustment ushr 16) and 0xFF).toByte()
        bytes[headOffset + 10] = ((adjustment ushr 8) and 0xFF).toByte()
        bytes[headOffset + 11] = (adjustment and 0xFF).toByte()
        return bytes
    }

    /** Re-wraps final sfnt tables (head already carries its adjustment) as WOFF1. */
    private fun wrapWoff1(tables: Map<String, ByteArray>, sfnt: ByteArray): ByteArray {
        val tags = tables.keys.sortedBy { tagBytes(it).fold(0L) { a, b -> (a shl 8) or (b.toLong() and 0xFF) } }
        val out = ByteArrayOutputStream()
        val numTables = tags.size
        val directorySize = 44 + numTables * 20
        val payloads = tags.map { tag ->
            val original = tables.getValue(tag)
            val compressed = deflate(original)
            tag to if (compressed.size < original.size) compressed else original
        }
        var total = directorySize
        for ((_, payload) in payloads) total += (payload.size + 3) and 3.inv()
        w32(out, 0x774F4646) // 'wOFF'
        w32(out, 0x00010000)
        w32(out, total.toLong())
        w16(out, numTables); w16(out, 0)
        w32(out, sfnt.size.toLong())
        w16(out, 1); w16(out, 0)
        w32(out, 0); w32(out, 0); w32(out, 0); w32(out, 0); w32(out, 0)
        var offset = directorySize
        for ((tag, payload) in payloads) {
            out.write(tagBytes(tag))
            w32(out, offset.toLong())
            w32(out, payload.size.toLong())
            w32(out, tables.getValue(tag).size.toLong())
            w32(out, checksum(tables.getValue(tag)))
            offset += (payload.size + 3) and 3.inv()
        }
        for ((_, payload) in payloads) {
            out.write(payload)
            while (out.size() % 4 != 0) out.write(0)
        }
        return out.toByteArray()
    }

    private fun deflate(data: ByteArray): ByteArray {
        val deflater = Deflater(9)
        try {
            deflater.setInput(data)
            deflater.finish()
            val out = ByteArrayOutputStream(data.size / 2 + 64)
            val buffer = ByteArray(8192)
            while (!deflater.finished()) {
                val n = deflater.deflate(buffer)
                if (n <= 0) break
                out.write(buffer, 0, n)
            }
            return out.toByteArray()
        } finally {
            deflater.end()
        }
    }

    // ---- scalar helpers ----------------------------------------------------


    private fun tagBytes(tag: String): ByteArray {
        val b = tag.toByteArray(Charsets.US_ASCII)
        require(b.size == 4) { "bad table tag: $tag" }
        return b
    }

    private fun be32(b: ByteArray, o: Int): Long =
        ((b[o].toLong() and 0xFF) shl 24) or ((b[o + 1].toLong() and 0xFF) shl 16) or
            ((b[o + 2].toLong() and 0xFF) shl 8) or (b[o + 3].toLong() and 0xFF)

    private fun u16(b: ByteArray, o: Int): Int = ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)
    private fun i16(b: ByteArray, o: Int): Int = u16(b, o).toShort().toInt()

    private fun w16(out: ByteArrayOutputStream, v: Int) {
        out.write((v ushr 8) and 0xFF); out.write(v and 0xFF)
    }

    private fun w32(out: ByteArrayOutputStream, v: Long) {
        out.write(((v ushr 24) and 0xFF).toInt()); out.write(((v ushr 16) and 0xFF).toInt())
        out.write(((v ushr 8) and 0xFF).toInt()); out.write((v and 0xFF).toInt())
    }

    private fun put16(b: ByteArray, o: Int, v: Int) {
        b[o] = ((v ushr 8) and 0xFF).toByte(); b[o + 1] = (v and 0xFF).toByte()
    }

    private fun put32(b: ByteArray, o: Int, v: Long) {
        b[o] = ((v ushr 24) and 0xFF).toByte(); b[o + 1] = ((v ushr 16) and 0xFF).toByte()
        b[o + 2] = ((v ushr 8) and 0xFF).toByte(); b[o + 3] = (v and 0xFF).toByte()
    }

    // ---- parsed glyf font ---------------------------------------------------

    private class GlyfFont(
        val format: String,
        val tables: Map<String, ByteArray>,
        val unitsPerEm: Int,
        val numGlyphs: Int,
        val cmap: Map<Int, Int>,
    ) {
        private val glyf = tables.getValue("glyf")
        private val loca: LongArray
        private val hmtx = tables.getValue("hmtx")
        private val numHMetrics: Int
        private val compositeCache = HashMap<Int, List<Int>>()

        init {
            val head = tables.getValue("head")
            val locFormat = i16(head, 50)
            val locaTable = tables.getValue("loca")
            loca = LongArray(numGlyphs + 1)
            when (locFormat) {
                0 -> {
                    if (locaTable.size < (numGlyphs + 1) * 2) throw FontMergeException("truncated short loca")
                    for (i in 0..numGlyphs) loca[i] = u16(locaTable, i * 2) * 2L
                }
                1 -> {
                    if (locaTable.size < (numGlyphs + 1) * 4L) throw FontMergeException("truncated long loca")
                    for (i in 0..numGlyphs) loca[i] = be32(locaTable, i * 4)
                }
                else -> throw FontMergeException("unknown loca format $locFormat")
            }
            if (loca[numGlyphs] > glyf.size) throw FontMergeException("loca points beyond the glyf table")
            val hhea = tables.getValue("hhea")
            numHMetrics = u16(hhea, 34)
            if (numHMetrics < 1 || numHMetrics > numGlyphs) throw FontMergeException("invalid hmtx metric count")
            if (hmtx.size < numHMetrics * 4 + (numGlyphs - numHMetrics) * 2) {
                throw FontMergeException("truncated hmtx table")
            }
        }

        fun advance(gid: Int): Int = u16(hmtx, minOf(gid, numHMetrics - 1) * 4)
        fun lsb(gid: Int): Int = if (gid < numHMetrics) i16(hmtx, gid * 4 + 2)
            else i16(hmtx, numHMetrics * 4 + (gid - numHMetrics) * 2)

        fun rawGlyph(gid: Int): ByteArray {
            val from = loca[gid].toInt()
            val to = loca[gid + 1].toInt()
            if (to <= from) return ByteArray(0)
            if (to > glyf.size) throw FontMergeException("glyph $gid is outside the glyf table")
            return glyf.copyOfRange(from, to)
        }

        fun compositeComponents(gid: Int): List<Int> = compositeCache.getOrPut(gid) {
            val raw = rawGlyph(gid)
            if (raw.size < 10 || i16(raw, 0) != -1) return@getOrPut emptyList()
            val out = ArrayList<Int>()
            var p = 10
            while (p + 4 <= raw.size) {
                val flags = u16(raw, p)
                out.add(u16(raw, p + 2))
                p += 4
                p += if (flags and 0x0001 != 0) 4 else 2
                when {
                    flags and 0x0008 != 0 -> p += 2
                    flags and 0x0040 != 0 -> p += 4
                    flags and 0x0080 != 0 -> p += 8
                }
                if (flags and 0x0020 == 0) break
            }
            out
        }

        companion object {
            fun parse(bytes: ByteArray, label: String): GlyfFont {
                val (format, tables) = try {
                    FontAnalyzer.decodeTables(bytes)
                } catch (error: FontAnalyzer.FontParseException) {
                    throw FontMergeException("$label font: ${error.message}")
                }
                for (required in listOf("glyf", "loca", "head", "hhea", "hmtx", "maxp", "cmap")) {
                    if (!tables.containsKey(required)) {
                        throw FontMergeException("$label font has no $required table (CFF/OTF and bitmap fonts cannot be merged)")
                    }
                }
                val head = tables.getValue("head")
                if (head.size < 54) throw FontMergeException("$label font: truncated head table")
                val unitsPerEm = u16(head, 18)
                if (unitsPerEm !in 16..16384) throw FontMergeException("$label font: invalid unitsPerEm")
                val maxp = tables.getValue("maxp")
                if (maxp.size < 6) throw FontMergeException("$label font: truncated maxp table")
                val numGlyphs = u16(maxp, 4)
                val cmap = readCmapMapping(tables.getValue("cmap"), label)
                return GlyfFont(format, tables, unitsPerEm, numGlyphs, cmap)
            }

            private fun readCmapMapping(cmap: ByteArray, label: String): Map<Int, Int> {
                if (cmap.size < 4) throw FontMergeException("$label font: truncated cmap table")
                val count = u16(cmap, 2)
                if (4L + count * 8L > cmap.size) throw FontMergeException("$label font: invalid cmap directory")
                var fmt4 = -1; var fmt12 = -1
                for (i in 0 until count) {
                    val platform = u16(cmap, 4 + i * 8)
                    val encoding = u16(cmap, 6 + i * 8)
                    val offset = be32(cmap, 8 + i * 8).toInt()
                    if (offset + 2 > cmap.size) continue
                    val format = u16(cmap, offset)
                    if (format == 12 && ((platform == 3 && encoding == 10) || platform == 0) && fmt12 < 0) fmt12 = offset
                    if (format == 4 && (platform == 3 || platform == 0) && fmt4 < 0) fmt4 = offset
                }
                val out = HashMap<Int, Int>()
                if (fmt12 >= 0) out.putAll(parseCmap12(cmap, fmt12, label))
                if (fmt4 >= 0) out.putAll(parseCmap4(cmap, fmt4, label))
                return out
            }

            private fun parseCmap12(cmap: ByteArray, offset: Int, label: String): Map<Int, Int> {
                if (offset + 16 > cmap.size) throw FontMergeException("$label font: truncated cmap format 12")
                val length = be32(cmap, offset + 4)
                val groups = be32(cmap, offset + 12)
                if (length < 16 || offset + length > cmap.size || 16L + groups * 12L > length) {
                    throw FontMergeException("$label font: invalid cmap format 12")
                }
                val out = HashMap<Int, Int>()
                var p = offset + 16
                repeat(groups.toInt()) {
                    val first = be32(cmap, p); val last = be32(cmap, p + 4); val firstGid = be32(cmap, p + 8)
                    p += 12
                    if (first > last || last - first > 0x10FFFF) throw FontMergeException("$label font: bad cmap group")
                    for (cp in first..last) out[cp.toInt()] = (firstGid + (cp - first)).toInt()
                }
                return out
            }

            private fun parseCmap4(cmap: ByteArray, offset: Int, label: String): Map<Int, Int> {
                if (offset + 14 > cmap.size) throw FontMergeException("$label font: truncated cmap format 4")
                val length = u16(cmap, offset + 2)
                if (length < 16 || offset + length > cmap.size) {
                    throw FontMergeException("$label font: invalid cmap format 4 length")
                }
                val segments = u16(cmap, offset + 6) / 2
                if (segments < 1 || 16L + segments * 8L > length) {
                    throw FontMergeException("$label font: invalid cmap format 4 segments")
                }
                val endBase = offset + 14
                val startBase = endBase + segments * 2 + 2
                val deltaBase = startBase + segments * 2
                val rangeBase = deltaBase + segments * 2
                val out = HashMap<Int, Int>()
                for (i in 0 until segments) {
                    val end = u16(cmap, endBase + i * 2)
                    val start = u16(cmap, startBase + i * 2)
                    if (start == 0xFFFF && end == 0xFFFF) continue
                    if (start > end) continue
                    val delta = i16(cmap, deltaBase + i * 2)
                    val rangeOffset = u16(cmap, rangeBase + i * 2)
                    for (cp in start..end) {
                        val gid = if (rangeOffset == 0) {
                            (cp + delta) and 0xFFFF
                        } else {
                            val at = rangeBase + i * 2 + rangeOffset + (cp - start) * 2
                            if (at + 2 > offset + length) throw FontMergeException("$label font: cmap glyph array out of range")
                            val g = u16(cmap, at)
                            if (g == 0) 0 else (g + delta) and 0xFFFF
                        }
                        if (gid != 0) out[cp] = gid
                    }
                }
                return out
            }
        }
    }
}
