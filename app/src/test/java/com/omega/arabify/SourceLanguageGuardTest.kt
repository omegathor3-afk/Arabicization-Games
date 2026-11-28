package com.omega.arabify

import org.junit.Assert.*
import org.junit.Test

class SourceLanguageGuardTest {
    @Test
    fun englishPasses() {
        assertEquals(SourceLanguageGuard.Verdict.ENGLISH, SourceLanguageGuard.classify("Start a new game"))
        assertTrue(SourceLanguageGuard.isSafeToTranslate("Load game"))
    }

    @Test
    fun arabicOutputIsRefused() {
        assertEquals(SourceLanguageGuard.Verdict.ALREADY_ARABIC, SourceLanguageGuard.classify("ابدأ لعبة جديدة"))
        assertFalse(SourceLanguageGuard.isSafeToTranslate("ابدأ لعبة جديدة"))
    }

    @Test
    fun cjkIsNotEnglish() {
        assertEquals(SourceLanguageGuard.Verdict.NOT_ENGLISH, SourceLanguageGuard.classify("ゲームを開始"))
    }

    @Test
    fun longLatinNonEnglishIsRefused() {
        assertEquals(SourceLanguageGuard.Verdict.NOT_ENGLISH, SourceLanguageGuard.classify("demarrer une nouvelle partie maintenant"))
    }

    @Test
    fun shortTokensAreTooShort() {
        assertEquals(SourceLanguageGuard.Verdict.TOO_SHORT, SourceLanguageGuard.classify("OK"))
    }

    @Test
    fun shortEnglishWithoutMarkersPasses() {
        assertEquals(SourceLanguageGuard.Verdict.ENGLISH, SourceLanguageGuard.classify("Refinery"))
    }

    @Test
    fun shortEnglishGameStringsWithoutFunctionWordsPass() {
        // regression: marker absence must not convict short strings
        assertEquals(SourceLanguageGuard.Verdict.ENGLISH, SourceLanguageGuard.classify("Hit one vehicle"))
        assertEquals(SourceLanguageGuard.Verdict.ENGLISH, SourceLanguageGuard.classify("Wave incoming"))
    }

    @Test
    fun placeholderHeavyEnglishPasses() {
        // regression: bracket/percent tokens are not words (Unciv false positives)
        assertEquals(SourceLanguageGuard.Verdict.ENGLISH, SourceLanguageGuard.classify("Provides [amountOfCulture] culture at 30 Influence"))
        assertEquals(SourceLanguageGuard.Verdict.ENGLISH, SourceLanguageGuard.classify("Gift [giftAmount] gold (+[influenceAmount] influence)"))
        assertEquals(SourceLanguageGuard.Verdict.ENGLISH, SourceLanguageGuard.classify("May our nations forever remain united!"))
        assertEquals(SourceLanguageGuard.Verdict.ENGLISH, SourceLanguageGuard.classify("THIS MEANS WAR! (Declare war)"))
    }
}
