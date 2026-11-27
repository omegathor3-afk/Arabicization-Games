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

    @Test
    fun cancellationDuringPrimaryTranslationDoesNotStartFallbackOrInvalidateModel() = runBlocking {
        val expected = CancellationException("cancel primary translation")
        var fallbackAvailabilityChecked = false
        var primaryFailureHandled = false
        var fallbackCalled = false

        try {
            translateWithFallback(
                primary = { throw expected },
                fallbackAvailable = {
                    fallbackAvailabilityChecked = true
                    true
                },
                onPrimaryFailure = { primaryFailureHandled = true },
                fallback = {
                    fallbackCalled = true
                    "fallback"
                },
            )
            throw AssertionError("primary cancellation must propagate")
        } catch (actual: CancellationException) {
            assertSame(expected, actual)
        }

        assertFalse(fallbackAvailabilityChecked)
        assertFalse(primaryFailureHandled)
        assertFalse(fallbackCalled)
    }

    @Test
    fun ordinaryPrimaryFailureUsesFallbackAndInvalidatesPrimaryState() = runBlocking {
        val primaryFailure = IllegalStateException("ML Kit unavailable")
        var primaryInvalidated = false

        val result = translateWithFallback(
            primary = { throw primaryFailure },
            fallbackAvailable = { true },
            onPrimaryFailure = { primaryInvalidated = true },
            fallback = { "ترجمة احتياطية" },
        )

        assertEquals("ترجمة احتياطية", result)
        assertTrue(primaryInvalidated)
    }

    @Test
    fun cancellationDuringFallbackPropagatesInsteadOfMaskingCancellation() = runBlocking {
        val expected = CancellationException("cancel bundled fallback")

        try {
            translateWithFallback(
                primary = { throw IllegalStateException("ML Kit unavailable") },
                fallbackAvailable = { true },
                onPrimaryFailure = {},
                fallback = { throw expected },
            )
            throw AssertionError("fallback cancellation must propagate")
        } catch (actual: CancellationException) {
            assertSame(expected, actual)
        }
    }

    @Test
    fun fallbackFailureIsSuppressedOnOriginalPrimaryFailure() = runBlocking {
        val primaryFailure = IllegalStateException("ML Kit unavailable")
        val fallbackFailure = IllegalArgumentException("Bergamot unavailable")

        try {
            translateWithFallback(
                primary = { throw primaryFailure },
                fallbackAvailable = { true },
                onPrimaryFailure = {},
                fallback = { throw fallbackFailure },
            )
            throw AssertionError("the primary failure should be preserved")
        } catch (actual: IllegalStateException) {
            assertSame(primaryFailure, actual)
            assertEquals(1, actual.suppressed.size)
            assertSame(fallbackFailure, actual.suppressed.single())
        }
    }
}
