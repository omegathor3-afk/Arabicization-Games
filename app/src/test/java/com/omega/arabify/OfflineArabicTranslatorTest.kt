package com.omega.arabify

import com.google.mlkit.nl.translate.TranslateLanguage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineArabicTranslatorTest {
    @Test
    fun downloadedArabicModelMarksTranslationReady() {
        assertTrue(hasArabicTranslationModel(setOf(TranslateLanguage.ARABIC)))
    }

    @Test
    fun missingArabicModelDoesNotMarkTranslationReady() {
        assertFalse(hasArabicTranslationModel(emptySet()))
    }

    @Test
    fun unrelatedLanguageModelDoesNotMarkArabicTranslationReady() {
        assertFalse(hasArabicTranslationModel(setOf(TranslateLanguage.FRENCH)))
    }
}
