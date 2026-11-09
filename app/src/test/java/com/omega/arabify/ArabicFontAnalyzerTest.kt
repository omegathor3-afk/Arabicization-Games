package com.omega.arabify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ArabicFontAnalyzerTest {
    @Test fun detectsArabicCoverageFromTrueTypeCmapFormat12() {
        val assessment = ArabicFontAnalyzer.inspect("assets/fonts/test.ttf", fontWithArabicBasicCmap())
        assertEquals(26, assessment.mappedArabicLetters)
        assertTrue(assessment.coveragePercent > 0)
        assertFalse(assessment.suitableForArabic)
    }

    @Test fun rejectsMalformedFontWithoutCrashing() {
        val assessment = ArabicFontAnalyzer.inspect("font.otf", byteArrayOf(1, 2, 3))
        assertEquals(0, assessment.mappedArabicLetters)
        assertFalse(assessment.suitableForArabic)
    }

    @Test fun ranksArabicSuitableFontAheadOfLatinOnlyFont() {
        val latin = ArabicFontAssessment("latin.ttf", "TTF", 0, 100, 0, false, false, 1000)
        val arabic = ArabicFontAssessment("arabic.ttf", "TTF", 95, 100, 95, true, true, 2000)
        assertEquals("arabic.ttf", ArabicFontAnalyzer.rank(listOf(latin, arabic)).first().path)
    }

    @Test fun requiresArabicScriptInBothGsubAndGposForSuitableRanking() {
        val complete = ArabicFontAnalyzer.inspect("fonts/arabic-complete.ttf", fontWithFullArabicCmap(includeShaping = true))
        assertEquals(100, complete.coveragePercent)
        assertTrue(complete.hasArabicShapingTables)
        assertTrue(complete.suitableForArabic)

        val cmapOnly = ArabicFontAnalyzer.inspect("fonts/arabic-cmap-only.ttf", fontWithFullArabicCmap(includeShaping = false))
        assertEquals(100, cmapOnly.coveragePercent)
        assertFalse(cmapOnly.hasArabicShapingTables)
        assertFalse(cmapOnly.suitableForArabic)
    }

    @Test fun recognizesSupportedFontExtensions() {
        assertTrue(ArabicFontAnalyzer.isFontPath("assets/fonts/ui.TTF"))
        assertTrue(ArabicFontAnalyzer.isFontPath("assets/fonts/dialog.otf"))
        assertFalse(ArabicFontAnalyzer.isFontPath("assets/fonts/atlas.bin"))
    }

    private fun fontWithFullArabicCmap(includeShaping: Boolean): ByteArray {
        val tableCount = if (includeShaping) 3 else 1
        val cmapOffset = 12 + tableCount * 16
        val cmapLength = 40
        val shapingLength = 22
        val gsubOffset = cmapOffset + cmapLength
        val gposOffset = gsubOffset + shapingLength
        val bytes = ByteArray(cmapOffset + cmapLength + if (includeShaping) shapingLength * 2 else 0)
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        b.putInt(0, 0x00010000)
        b.putShort(4, tableCount.toShort())
        writeTableRecord(bytes, b, 12, "cmap", cmapOffset, cmapLength)
        if (includeShaping) {
            writeTableRecord(bytes, b, 28, "GSUB", gsubOffset, shapingLength)
            writeTableRecord(bytes, b, 44, "GPOS", gposOffset, shapingLength)
        }
        b.putShort(cmapOffset, 0)
        b.putShort(cmapOffset + 2, 1)
        b.putShort(cmapOffset + 4, 3)
        b.putShort(cmapOffset + 6, 10)
        b.putInt(cmapOffset + 8, 12)
        val subtable = cmapOffset + 12
        b.putShort(subtable, 12)
        b.putShort(subtable + 2, 0)
        b.putInt(subtable + 4, 28)
        b.putInt(subtable + 8, 0)
        b.putInt(subtable + 12, 1)
        b.putInt(subtable + 16, 0x0621)
        b.putInt(subtable + 20, 0x08C9)
        b.putInt(subtable + 24, 1)
        if (includeShaping) {
            writeArabicScriptTable(b, gsubOffset)
            writeArabicScriptTable(b, gposOffset)
        }
        return bytes
    }

    private fun writeTableRecord(bytes: ByteArray, buffer: ByteBuffer, record: Int, tag: String, offset: Int, length: Int) {
        tag.toByteArray(Charsets.US_ASCII).copyInto(bytes, record)
        buffer.putInt(record + 8, offset)
        buffer.putInt(record + 12, length)
    }

    private fun writeArabicScriptTable(buffer: ByteBuffer, base: Int) {
        buffer.putShort(base, 1)
        buffer.putShort(base + 2, 0)
        buffer.putShort(base + 4, 10)
        buffer.putShort(base + 6, 0)
        buffer.putShort(base + 8, 0)
        buffer.putShort(base + 10, 1)
        buffer.put(base + 12, 97.toByte())
        buffer.put(base + 13, 114.toByte())
        buffer.put(base + 14, 97.toByte())
        buffer.put(base + 15, 98.toByte())
        buffer.putShort(base + 16, 8)
        buffer.putShort(base + 18, 4)
        buffer.putShort(base + 20, 0)
    }

    private fun fontWithArabicBasicCmap(): ByteArray {        val bytes = ByteArray(28 + 4 + 8 + 28)
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        b.putInt(0, 0x00010000)
        b.putShort(4, 1)
        bytes[12] = 'c'.code.toByte(); bytes[13] = 'm'.code.toByte()
        bytes[14] = 'a'.code.toByte(); bytes[15] = 'p'.code.toByte()
        b.putInt(20, 28)
        b.putInt(24, 40)
        b.putShort(28, 0)
        b.putShort(30, 1)
        b.putShort(32, 3)
        b.putShort(34, 10)
        b.putInt(36, 12)
        b.putShort(40, 12)
        b.putShort(42, 0)
        b.putInt(44, 28)
        b.putInt(48, 0)
        b.putInt(52, 1)
        b.putInt(56, 0x0621)
        b.putInt(60, 0x063A)
        b.putInt(64, 1)
        // The second range is intentionally omitted from this compact fixture;
        // this still validates the parser against a real cmap format-12 group.
        return bytes
    }
}
