package com.omega.arabify

import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FontMergerTest {
    private val assets = File("src/main/assets")
    private fun catalog(id: String): ByteArray =
        File(assets, FontSelector.catalog.first { it.id == id }.assetPath).readBytes()

    @Test
    fun mergeAddsArabicAndKeepsOriginalGlyphsByteIdentical() {
        val game = TinyFont.build("fontello")
        val result = FontMerger.mergeArabicInto(game, catalog("tajawal-regular"))
        val merged = result.fontBytes

        val profile = FontAnalyzer.analyze(merged)
        assertTrue("merged font must cover Arabic core", profile.hasArabicCore)
        assertTrue("merged font must cover presentation forms B", profile.presentationFormsB >= 80)
        assertTrue("Latin A must still map", profile.supports(0x41))
        assertTrue("PUA icon must still map", profile.supports(0xE800))
        assertEquals("fontello", profile.familyName)

        // Original glyphs byte-identical and at the same glyph ids.
        val srcGlyf = extractGlyphs(game)
        val outGlyf = extractGlyphs(merged)
        assertEquals(srcGlyf.size, outGlyf.size - result.glyphsAdded)
        srcGlyf.forEachIndexed { i, g -> assertTrue("glyph $i changed", g.contentEquals(outGlyf[i])) }

        // Original cmap mappings unchanged; Arabic codepoints now mapped to new gids.
        val srcCmap = readCmapGids(game)
        val outCmap = readCmapGids(merged)
        assertEquals(srcCmap[0x41], outCmap[0x41])
        assertEquals(srcCmap[0xE800], outCmap[0xE800])
        assertNotEquals(null, outCmap[0x0639])
        assertTrue("new gid must be appended after original glyphs", outCmap.getValue(0x0639) >= srcGlyf.size)
        assertTrue(result.codepointsAdded > 100)
    }

    @Test
    fun compositeArabicGlyphsGetRealOutlines() {
        // Amiri implements presentation forms as composites; the merge must remap their components.
        val merged = FontMerger.mergeArabicInto(TinyFont.build("fontello"), catalog("amiri-regular"))
        val glyphs = extractGlyphs(merged.fontBytes)
        val cmap = readCmapGids(merged.fontBytes)
        val baseGid = cmap.getValue(0x0639) // ain
        val formGid = cmap.getValue(0xFEE0) // meem medial (composite in Amiri)
        assertTrue("base Arabic glyph must have outlines", extractGlyphs(merged.fontBytes)[baseGid].size > 10)
        assertTrue("presentation-form glyph must have outlines", glyphs[formGid].size > 10)
        assertTrue(merged.codepointsAdded > 200)
    }

    @Test
    fun woff1InputProducesWoff1Output() {
        val ttf = TinyFont.build("fontello")
        val woff = FontMergerSfntTools.toWoff1(ttf)
        assertEquals('w', woff[0].toInt().toChar())
        val result = FontMerger.mergeArabicInto(woff, catalog("tajawal-regular"))
        assertEquals('w', result.fontBytes[0].toInt().toChar())
        val profile = FontAnalyzer.analyze(result.fontBytes) // FontAnalyzer is the independent WOFF1 reader
        assertTrue(profile.hasArabicCore)
        assertTrue(profile.supports(0xE800))
        assertEquals("WOFF1", profile.format)
    }

    @Test
    fun scalesOutlinesWhenUnitsPerEmDiffer() {
        // Rooyin is 2000 upm; the tiny game font is 1000 upm.
        val result = FontMerger.mergeArabicInto(TinyFont.build("fontello"), catalog("rooyin-regular"))
        assertTrue(result.scaledToGameUpm)
        val profile = FontAnalyzer.analyze(result.fontBytes)
        assertTrue(profile.hasArabicCore)
        assertEquals(1000, profile.unitsPerEm)
        val glyphs = extractGlyphs(result.fontBytes)
        val gid = readCmapGids(result.fontBytes).getValue(0x0639)
        assertTrue("scaled glyph must keep outlines", glyphs[gid].size > 10)
    }

    @Test
    fun refusesCatalogWithoutArabic() {
        try {
            FontMerger.mergeArabicInto(TinyFont.build("fontello"), TinyFont.build("fontello"))
            throw AssertionError("expected FontMergeException")
        } catch (expected: FontMerger.FontMergeException) {
            // honest failure: nothing to merge
        }
    }

    // ---- glyph/cmap extraction helpers (independent of FontMerger internals) ----

    private fun extractGlyphs(font: ByteArray): List<ByteArray> {
        val tables = FontAnalyzer.decodeTables(font).second
        val maxp = tables.getValue("maxp")
        val numGlyphs = ((maxp[4].toInt() and 0xFF) shl 8) or (maxp[5].toInt() and 0xFF)
        val head = tables.getValue("head")
        val longLoca = (((head[50].toInt() and 0xFF) shl 8) or (head[51].toInt() and 0xFF)) == 1
        val loca = tables.getValue("loca")
        val glyf = tables.getValue("glyf")
        fun offset(i: Int): Long = if (longLoca) {
            ((loca[i * 4].toLong() and 0xFF) shl 24) or ((loca[i * 4 + 1].toLong() and 0xFF) shl 16) or
                ((loca[i * 4 + 2].toLong() and 0xFF) shl 8) or (loca[i * 4 + 3].toLong() and 0xFF)
        } else {
            (((loca[i * 2].toInt() and 0xFF) shl 8) or (loca[i * 2 + 1].toInt() and 0xFF)) * 2L
        }
        return (0 until numGlyphs).map { gid ->
            val from = offset(gid).toInt()
            val to = offset(gid + 1).toInt()
            if (to > from) glyf.copyOfRange(from, to) else ByteArray(0)
        }
    }

    private fun readCmapGids(font: ByteArray): Map<Int, Int> {
        // Minimal format-4 reader for the tiny fixture and merged output (idDelta only).
        val tables = FontAnalyzer.decodeTables(font).second
        val cmap = tables.getValue("cmap")
        val count = ((cmap[2].toInt() and 0xFF) shl 8) or (cmap[3].toInt() and 0xFF)
        var offset = -1
        for (i in 0 until count) {
            val o = ((cmap[8 + i * 8].toLong() and 0xFF) shl 24) or ((cmap[9 + i * 8].toLong() and 0xFF) shl 16) or
                ((cmap[10 + i * 8].toLong() and 0xFF) shl 8) or (cmap[11 + i * 8].toLong() and 0xFF)
            val fmt = ((cmap[o.toInt()].toInt() and 0xFF) shl 8) or (cmap[o.toInt() + 1].toInt() and 0xFF)
            if (fmt == 4 && offset < 0) offset = o.toInt()
        }
        check(offset >= 0)
        fun u16(o: Int) = ((cmap[o].toInt() and 0xFF) shl 8) or (cmap[o + 1].toInt() and 0xFF)
        val segCount = u16(offset + 6) / 2
        val endBase = offset + 14
        val startBase = endBase + segCount * 2 + 2
        val deltaBase = startBase + segCount * 2
        val rangeBase = deltaBase + segCount * 2
        val out = HashMap<Int, Int>()
        for (i in 0 until segCount) {
            val end = u16(endBase + i * 2)
            val start = u16(startBase + i * 2)
            if (start == 0xFFFF) continue
            val delta = u16(deltaBase + i * 2)
            val rangeOffset = u16(rangeBase + i * 2)
            check(rangeOffset == 0) { "test reader only supports idDelta segments" }
            for (cp in start..end) {
                val gid = (cp + delta.toShort()) and 0xFFFF
                if (gid != 0) out[cp] = gid
            }
        }
        return out
    }
}

/** Builds a minimal valid TTF: glyphs .notdef, 'A' (triangle), PUA 0xE800 (square), space. */
internal object TinyFont {
    fun build(family: String): ByteArray {
        val glyphA = simpleGlyph(
            contours = 1,
            points = listOf(50 to 0, 450 to 0, 250 to 700),
        )
        val glyphIcon = simpleGlyph(
            contours = 1,
            points = listOf(100 to 100, 900 to 100, 900 to 900, 100 to 900),
        )
        val glyphs = listOf(ByteArray(0), glyphA, glyphIcon, ByteArray(0))
        val upm = 1000

        val glyf = ByteArrayOutputStream()
        val loca = ByteArrayOutputStream()
        for (g in glyphs) {
            w32(loca, glyf.size().toLong())
            glyf.write(g)
        }
        w32(loca, glyf.size().toLong())

        val hmtx = ByteArrayOutputStream()
        intArrayOf(500, 500, 1000, 500).zip(intArrayOf(0, 50, 100, 0)).forEach { (adv, lsb) ->
            w16(hmtx, adv); w16(hmtx, lsb)
        }

        val cmap = cmapFormat4(mapOf(0x20 to 3, 0x41 to 1, 0xE800 to 2))
        val head = head(upm)
        val hhea = hhea(glyphs.size)
        val maxp = maxp(glyphs.size)
        val name = nameTable(family)
        val post = ByteArray(32).also { it[3] = 3 } // version 3.0

        val tables = linkedMapOf(
            "cmap" to cmap, "glyf" to glyf.toByteArray(), "head" to head, "hhea" to hhea,
            "hmtx" to hmtx.toByteArray(), "loca" to loca.toByteArray(), "maxp" to maxp,
            "name" to name, "post" to post,
        )
        return FontMergerSfntTools.toSfnt(tables)
    }

    private fun simpleGlyph(contours: Int, points: List<Pair<Int, Int>>): ByteArray {
        val out = ByteArrayOutputStream()
        w16(out, contours)
        w16(out, points.minOf { it.first }); w16(out, points.minOf { it.second })
        w16(out, points.maxOf { it.first }); w16(out, points.maxOf { it.second })
        w16(out, points.size - 1)
        w16(out, 0) // no instructions
        val xs = points.map { it.first }; val ys = points.map { it.second }
        for (i in points.indices) {
            var f = 0x01
            val dx = xs[i] - if (i == 0) 0 else xs[i - 1]
            val dy = ys[i] - if (i == 0) 0 else ys[i - 1]
            if (dx in 1..255) f = f or 0x02 or 0x10 else if (dx == 0) f = f or 0x10 else if (dx in -255..-1) f = f or 0x02
            if (dy in 1..255) f = f or 0x04 or 0x20 else if (dy == 0) f = f or 0x20 else if (dy in -255..-1) f = f or 0x04
            out.write(f)
        }
        for (i in points.indices) {
            val dx = xs[i] - if (i == 0) 0 else xs[i - 1]
            when {
                dx == 0 -> Unit
                dx in 1..255 -> out.write(dx)
                dx in -255..-1 -> out.write(-dx)
                else -> w16(out, dx)
            }
        }
        for (i in points.indices) {
            val dy = ys[i] - if (i == 0) 0 else ys[i - 1]
            when {
                dy == 0 -> Unit
                dy in 1..255 -> out.write(dy)
                dy in -255..-1 -> out.write(-dy)
                else -> w16(out, dy)
            }
        }
        return out.toByteArray()
    }

    private fun cmapFormat4(mapping: Map<Int, Int>): ByteArray {
        val segments = mapping.entries.sortedBy { it.key }.map { Triple(it.key, it.key, (it.value - it.key) and 0xFFFF) }
        val segCount = segments.size + 1
        val out = ByteArrayOutputStream()
        w16(out, 0); w16(out, 1)
        w16(out, 3); w16(out, 1); w32(out, 12)
        w16(out, 4); w16(out, 16 + 8 * segCount); w16(out, 0)
        var searchRange = 1; var entrySelector = -1
        while (searchRange * 2 <= segCount) { searchRange *= 2; entrySelector++ }
        searchRange *= 2
        w16(out, segCount * 2); w16(out, searchRange); w16(out, entrySelector); w16(out, segCount * 2 - searchRange)
        for (s in segments) w16(out, s.second)
        w16(out, 0xFFFF)
        w16(out, 0)
        for (s in segments) w16(out, s.first)
        w16(out, 0xFFFF)
        for (s in segments) w16(out, s.third)
        w16(out, 1)
        repeat(segCount) { w16(out, 0) }
        return out.toByteArray()
    }

    private fun head(upm: Int): ByteArray {
        val out = ByteArrayOutputStream()
        w32(out, 0x00010000); w32(out, 0x00010000); w32(out, 0); w32(out, 0x5F0F3CF5)
        w16(out, 0); w16(out, upm)
        w32(out, 0); w32(out, 0); w32(out, 0); w32(out, 0) // created/modified
        w16(out, 50); w16(out, 0); w16(out, 1000); w16(out, 1000) // bbox
        w16(out, 0); w16(out, 8); w16(out, 2); w16(out, 1); w16(out, 0)
        return out.toByteArray()
    }

    private fun hhea(numGlyphs: Int): ByteArray {
        val out = ByteArrayOutputStream()
        w32(out, 0x00010000)
        w16(out, 800); w16(out, -200); w16(out, 0); w16(out, 1000)
        w16(out, 0); w16(out, 0); w16(out, 1000); w16(out, 1); w16(out, 0); w16(out, 0)
        w16(out, 0); w16(out, 0); w16(out, 0); w16(out, 0); w16(out, 0)
        w16(out, numGlyphs)
        return out.toByteArray()
    }

    private fun maxp(numGlyphs: Int): ByteArray {
        val out = ByteArrayOutputStream()
        w32(out, 0x00010000)
        w16(out, numGlyphs)
        repeat(13) { w16(out, if (it == 0) 4 else 1) }
        return out.toByteArray()
    }

    private fun nameTable(family: String): ByteArray {
        val utf16 = family.toByteArray(Charsets.UTF_16BE)
        val out = ByteArrayOutputStream()
        w16(out, 0); w16(out, 1); w16(out, 18)
        w16(out, 3); w16(out, 1); w16(out, 0x0409); w16(out, 1); w16(out, utf16.size); w16(out, 0)
        out.write(utf16)
        return out.toByteArray()
    }

    private fun w16(out: ByteArrayOutputStream, v: Int) {
        out.write((v ushr 8) and 0xFF); out.write(v and 0xFF)
    }

    private fun w32(out: ByteArrayOutputStream, v: Long) {
        out.write(((v ushr 24) and 0xFF).toInt()); out.write(((v ushr 16) and 0xFF).toInt())
        out.write(((v ushr 8) and 0xFF).toInt()); out.write((v and 0xFF).toInt())
    }
}

/** Test-side sfnt/WOFF1 writers mirroring the TrueType spec (independent of FontMerger). */
internal object FontMergerSfntTools {
    fun toSfnt(tables: Map<String, ByteArray>): ByteArray {
        val tags = tables.keys.sorted()
        val out = ByteArrayOutputStream()
        val numTables = tags.size
        var searchRange = 1; var entrySelector = -1
        while (searchRange * 2 <= numTables) { searchRange *= 2; entrySelector++ }
        searchRange *= 16
        w32(out, 0x00010000)
        w16(out, numTables); w16(out, searchRange); w16(out, entrySelector); w16(out, numTables * 16 - searchRange)
        var offset = 12 + numTables * 16
        val offsets = tags.map { tag -> (tag to offset).also { offset += (tables.getValue(tag).size + 3) and 3.inv() } }
        tags.forEachIndexed { i, tag ->
            out.write(tag.toByteArray(Charsets.US_ASCII))
            w32(out, checksum(tables.getValue(tag)))
            w32(out, offsets[i].second.toLong())
            w32(out, tables.getValue(tag).size.toLong())
        }
        for (tag in tags) {
            out.write(tables.getValue(tag))
            while (out.size() % 4 != 0) out.write(0)
        }
        val bytes = out.toByteArray()
        val headOffset = offsets[tags.indexOf("head")].second
        val adjustment = (0xB1B0AFBAL - checksum(bytes)) and 0xFFFFFFFFL
        for (k in 0..3) bytes[headOffset + 8 + k] = ((adjustment ushr (8 * (3 - k))) and 0xFF).toByte()
        return bytes
    }

    fun toWoff1(ttf: ByteArray): ByteArray {
        val count = ((ttf[4].toInt() and 0xFF) shl 8) or (ttf[5].toInt() and 0xFF)
        val tables = LinkedHashMap<String, ByteArray>()
        for (i in 0 until count) {
            val o = 12 + i * 16
            val tag = String(ttf, o, 4, Charsets.US_ASCII)
            val offset = be32(ttf, o + 8).toInt()
            val length = be32(ttf, o + 12).toInt()
            tables[tag] = ttf.copyOfRange(offset, offset + length)
        }
        val tags = tables.keys.sorted()
        val out = ByteArrayOutputStream()
        var total = 44 + tags.size * 20
        val payloads = tags.map { tag ->
            val original = tables.getValue(tag)
            tag to original // store raw; WOFF1 permits uncompressed tables
        }
        for ((_, p) in payloads) total += (p.size + 3) and 3.inv()
        w32(out, 0x774F4646); w32(out, 0x00010000); w32(out, total.toLong())
        w16(out, tags.size); w16(out, 0)
        w32(out, ttf.size.toLong()); w16(out, 1); w16(out, 0)
        w32(out, 0); w32(out, 0); w32(out, 0); w32(out, 0); w32(out, 0)
        var offset = 44 + tags.size * 20
        for ((tag, p) in payloads) {
            out.write(tag.toByteArray(Charsets.US_ASCII))
            w32(out, offset.toLong()); w32(out, p.size.toLong()); w32(out, tables.getValue(tag).size.toLong())
            w32(out, checksum(tables.getValue(tag)))
            offset += (p.size + 3) and 3.inv()
        }
        for ((_, p) in payloads) {
            out.write(p)
            while (out.size() % 4 != 0) out.write(0)
        }
        return out.toByteArray()
    }

    private fun checksum(table: ByteArray): Long {
        var sum = 0L
        var i = 0
        while (i + 4 <= table.size) { sum = (sum + be32(table, i)) and 0xFFFFFFFFL; i += 4 }
        if (i < table.size) {
            var last = 0L
            for (k in i until table.size) last = (last shl 8) or (table[k].toLong() and 0xFF)
            last = last shl (8 * (4 - (table.size - i)))
            sum = (sum + last) and 0xFFFFFFFFL
        }
        return sum
    }

    private fun be32(b: ByteArray, o: Int): Long =
        ((b[o].toLong() and 0xFF) shl 24) or ((b[o + 1].toLong() and 0xFF) shl 16) or
            ((b[o + 2].toLong() and 0xFF) shl 8) or (b[o + 3].toLong() and 0xFF)

    private fun w16(out: ByteArrayOutputStream, v: Int) {
        out.write((v ushr 8) and 0xFF); out.write(v and 0xFF)
    }

    private fun w32(out: ByteArrayOutputStream, v: Long) {
        out.write(((v ushr 24) and 0xFF).toInt()); out.write(((v ushr 16) and 0xFF).toInt())
        out.write(((v ushr 8) and 0xFF).toInt()); out.write((v and 0xFF).toInt())
    }
}
