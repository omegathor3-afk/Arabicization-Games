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
        val latin = ArabicFontAssessment("latin.ttf", "TTF", 0, 100, 0, false, 1000)
        val arabic = ArabicFontAssessment("arabic.ttf", "TTF", 95, 100, 95, true, 2000)
        assertEquals("arabic.ttf", ArabicFontAnalyzer.rank(listOf(latin, arabic)).first().path)
    }

    @Test fun recognizesSupportedFontExtensions() {
        assertTrue(ArabicFontAnalyzer.isFontPath("assets/fonts/ui.TTF"))
        assertTrue(ArabicFontAnalyzer.isFontPath("assets/fonts/dialog.otf"))
        assertFalse(ArabicFontAnalyzer.isFontPath("assets/fonts/atlas.bin"))
    }

    private fun fontWithArabicBasicCmap(): ByteArray {
        val bytes = ByteArray(28 + 4 + 8 + 28)
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
