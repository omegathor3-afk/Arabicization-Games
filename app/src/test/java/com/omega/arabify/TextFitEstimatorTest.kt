package com.omega.arabify

import org.junit.Assert.*
import org.junit.Test

class TextFitEstimatorTest {
    @Test
    fun shortTranslationDoesNotFlag() {
        val e = TextFitEstimator.estimate("Settings", "الإعدادات")
        assertFalse("ratio=" + e.widthRatio, e.overflowRisk)
    }

    @Test
    fun muchLongerArabicFlags() {
        val e = TextFitEstimator.estimate("Exit", "الخروج من اللعبة نهائيا")
        assertTrue(e.overflowRisk)
        assertTrue(e.widthRatio > 1.35)
    }

    @Test
    fun emptySourceNeverFlags() {
        assertFalse(TextFitEstimator.estimate("", "أي شيء").overflowRisk)
    }

    @Test
    fun arabicLettersAreNarrowerThanWideLatinCaps() {
        assertTrue(TextFitEstimator.widthUnits("ششش") < TextFitEstimator.widthUnits("WWW"))
        assertTrue(TextFitEstimator.widthUnits("ششش") > TextFitEstimator.widthUnits("iii"))
    }
}
