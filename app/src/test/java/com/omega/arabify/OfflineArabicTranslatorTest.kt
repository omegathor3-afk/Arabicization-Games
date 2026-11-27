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

    @Test
    fun bundledEngineMakesFirstInstallTranslationReadyWithoutMlKit() {
        assertTrue(hasUsableArabicTranslationEngine(mlKitModelDownloaded = false, bundledEngineUsable = true))
    }

    @Test
    fun downloadedMlKitModelMakesTranslationReadyWithoutBundledEngine() {
        assertTrue(hasUsableArabicTranslationEngine(mlKitModelDownloaded = true, bundledEngineUsable = false))
    }

    @Test
    fun translationIsNotReadyWhenNeitherEngineIsAvailable() {
        assertFalse(hasUsableArabicTranslationEngine(mlKitModelDownloaded = false, bundledEngineUsable = false))
    }
}
