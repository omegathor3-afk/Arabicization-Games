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
    fun compactModeAbbreviationsUseReviewedArabicLabels() {
        assertEquals("دون تخمين", ArabicUiGlossary.translateExact("NG"))
        assertEquals("دون أعلام", ArabicUiGlossary.translateExact("NF"))
        assertEquals("دون تخمين", ArabicUiGlossary.translateExact(" ng "))
    }

    @Test
    fun gameTermsFoundWrongInRealTranslationRunAreOverridden() {
        // Real-translation CI evidence (bb58a25): ML Kit produced استقال for
        // Quit, الاعتمادات for Credits, تعديل for Mods, noun لعب for Play.
        assertEquals("خروج", ArabicUiGlossary.translateExact("Quit"))
        // Same imperative class as quit: ML Kit is unreliable on standalone verbs.
        assertEquals("خروج", ArabicUiGlossary.translateExact("Exit"))
        assertEquals("خيارات", ArabicUiGlossary.translateExact("Options"))
        assertEquals("حول", ArabicUiGlossary.translateExact("About"))
        assertEquals("استئناف", ArabicUiGlossary.translateExact("Resume"))
        assertEquals("إيقاف مؤقت", ArabicUiGlossary.translateExact("Pause"))
        assertEquals("العب", ArabicUiGlossary.translateExact("Play"))
        assertEquals("فريق العمل", ArabicUiGlossary.translateExact("Credits"))
        assertEquals("إضافات", ArabicUiGlossary.translateExact("Mods"))
        assertEquals("انضم للعبة", ArabicUiGlossary.translateExact("Join Game"))
    }

    @Test
    fun longerSentencesAreNotReplacedBySubstringMatches() {
        assertNull(ArabicUiGlossary.translateExact("Create a new profile"))
        assertNull(ArabicUiGlossary.translateExact("Continue playing where you left off"))
    }
}
