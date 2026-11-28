package com.omega.arabify

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Visual RTL/font proof for the libGDX-bundle runtime on the REAL Mindustry
 * game. The CI script installs the deterministic pseudo build ("العربية N"
 * labels), reboots into an ar-EG locale and launches the game; this test then
 * screenshots the running game and checks that rendered menu lines actually
 * contain the shaped Arabic word, template-matched against an in-test Canvas
 * render of ArabicShaper.toVisual("العربية") drawn with each catalog font
 * that covers the full presentation-form set (FontSelector picks among these
 * to replace the game's font.woff; the matcher keeps the best).
 *
 * Shared analysis lives in ArabicVisualRenderCheck; frames stay in the
 * private workflow artifact per the repo privacy rule.
 */
@RunWith(AndroidJUnit4::class)
class MindustryVisualArabicInstrumentedTest {

    @Test
    fun arabicMenuLabelsAreVisiblyRenderedWithCatalogFont() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // Only catalog fonts covering every ArabicShaper-emitted codepoint can
        // be picked by FontSelector (Tajawal/Cairo/Almarai omit isolated forms
        // and fail coverage), so the replaced font is one of these four.
        val typefaces = listOf(
            "catalog/amiri-regular.ttf",
            "catalog/notonaskharabic-var.ttf",
            "catalog/rooyinfree-regular.ttf",
            "catalog/rooyinfree-bold.ttf",
        ).mapIndexed { index, path ->
            ArabicVisualRenderCheck.renderTypefaceFromAsset(context, "fonts/$path", "mindustry-template-$index.ttf")
        }
        val outcome = ArabicVisualRenderCheck.run(
            context = context,
            instrumentation = InstrumentationRegistry.getInstrumentation(),
            templateWord = "العربية",
            typefaces = typefaces,
            reportPrefix = "mindustry",
            minMatchedLines = 3,
            minF1 = 0.35,
        )
        assertTrue(
            "expected at least 3 menu lines visibly rendering the shaped Arabic word. ${outcome.report}",
            outcome.matchedLines >= 3,
        )
    }
}
