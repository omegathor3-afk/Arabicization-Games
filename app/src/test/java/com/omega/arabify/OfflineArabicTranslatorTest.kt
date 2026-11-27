package com.omega.arabify

import com.google.mlkit.nl.translate.TranslateLanguage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
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

    @Test
    fun failedMlKitStatusProbeSelectsBundledEngineWhenAvailable() = runBlocking {
        val availability = MlKitModelAvailability()
        val ready = availability.resolve { throw IllegalStateException("model manager unavailable") }

        assertFalse(ready)
        assertTrue(shouldUseBundledTranslationEngine(ready, bundledEngineUsable = true))
    }

    @Test
    fun successfulModelDownloadRefreshesCachedNegativeProbe() = runBlocking {
        val availability = MlKitModelAvailability()
        assertFalse(availability.resolve { false })

        availability.markDownloaded()

        assertTrue(availability.resolve { throw AssertionError("cached state should avoid another query") })
        assertFalse(shouldUseBundledTranslationEngine(mlKitModelDownloaded = true, bundledEngineUsable = true))
    }

    @Test
    fun translationFailureCanInvalidatePreviouslyDownloadedModel() = runBlocking {
        val availability = MlKitModelAvailability()
        availability.markDownloaded()
        assertTrue(availability.resolve { false })

        availability.markUnavailable()

        assertFalse(availability.resolve { throw AssertionError("cached unavailable state should avoid another query") })
    }

    @Test
    fun cancellationDuringModelStatusProbeIsNotConvertedToUnavailable() = runBlocking {
        val availability = MlKitModelAvailability()
        val expected = CancellationException("cancel model status probe")

        try {
            availability.resolve { throw expected }
            throw AssertionError("cancellation must propagate")
        } catch (actual: CancellationException) {
            assertSame(expected, actual)
        }
    }
}
