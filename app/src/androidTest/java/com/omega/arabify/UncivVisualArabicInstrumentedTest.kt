package com.omega.arabify

import android.graphics.Typeface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Visual RTL/font proof for the translation-folder runtime on the REAL Unciv
 * game. The CI script installs the deterministic pseudo build ("عرN" labels),
 * seeds GameSettings.json with language=العربية, reboots into an ar-EG locale
 * and launches the game; this test then screenshots the running game and
 * checks that rendered menu lines actually contain the shaped pseudo word,
 * template-matched against an in-test Canvas render of ArabicShaper.toVisual(
 * "عر") drawn with the SYSTEM typeface - Unciv bundles no TTF and builds its
 * fonts from Android system fonts at runtime (see LocaleSlotLocalizer), so
 * the system Arabic font is exactly what its text stack rasterizes with.
 *
 * Shared analysis lives in ArabicVisualRenderCheck; frames stay in the
 * private workflow artifact per the repo privacy rule.
 */
@RunWith(AndroidJUnit4::class)
class UncivVisualArabicInstrumentedTest {

    @Test
    fun arabicMenuLabelsAreVisiblyRenderedWithSystemFont() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val outcome = ArabicVisualRenderCheck.run(
            context = context,
            instrumentation = InstrumentationRegistry.getInstrumentation(),
            templateWord = "عر",
            typefaces = listOf(Typeface.DEFAULT, Typeface.DEFAULT_BOLD),
            reportPrefix = "unciv",
            minMatchedLines = 3,
            minF1 = 0.35,
            maxFrames = 36,
        )
        assertTrue(
            "expected at least 3 menu lines visibly rendering the shaped Arabic pseudo word. ${outcome.report}",
            outcome.matchedLines >= 3,
        )
    }
}
