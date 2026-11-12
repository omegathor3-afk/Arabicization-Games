package com.omega.arabify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArabicUiGlossaryTest {
    @Test
    fun exactCommonButtonsUseReviewedArabicLabels() {
        assertEquals("إنشاء", ArabicUiGlossary.translateExact("Create"))
        assertEquals("إنشاء", ArabicUiGlossary.translateExact("  CREATE  "))
        assertEquals("لعبة جديدة", ArabicUiGlossary.translateExact("New Game"))
        assertEquals("إلغاء", ArabicUiGlossary.translateExact("Cancel"))
    }

    @Test
    fun gameDifficultyLabelsRemainDeterministic() {
        assertEquals("سهل", ArabicUiGlossary.translateExact("Easy"))
        assertEquals("متوسط", ArabicUiGlossary.translateExact("Medium"))
        assertEquals("صعب", ArabicUiGlossary.translateExact("Hard"))
    }

    @Test
    fun longerSentencesAreNotReplacedBySubstringMatches() {
        assertNull(ArabicUiGlossary.translateExact("Create a new profile"))
        assertNull(ArabicUiGlossary.translateExact("Continue playing where you left off"))
    }
}
