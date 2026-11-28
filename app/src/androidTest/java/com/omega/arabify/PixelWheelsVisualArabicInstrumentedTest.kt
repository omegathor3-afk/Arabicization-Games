package com.omega.arabify

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Visual RTL/font proof for the gettext runtime on the REAL Pixel Wheels
 * game. The CI script installs the deterministic pseudo build ("العربية N"
 * labels), reboots into an ar-EG locale and launches the game; this test then
 * screenshots the running game and checks that rendered menu lines actually
 * contain the shaped Arabic word the pipeline wrote, template-matched against
 * an in-test Canvas render of ArabicShaper.toVisual("العربية") drawn with the
 * app's bundled Amiri, whose Arabic glyph outlines the merge copies verbatim
 * into the game's fonts (so the template is exactly a correct render).
 *
 * This is the pixel-level leg the uiautomator hierarchy cannot give (libGDX
 * renders labels in OpenGL with zero text nodes): it proves the pre-shaped
 * presentation-form strings survive rasterization into VISIBLE Arabic glyph
 * shapes, not tofu, not missing glyphs, not Latin leftovers. Shared analysis
 * lives in ArabicVisualRenderCheck; frames stay in the private workflow
 * artifact per the repo privacy rule.
 */
@RunWith(AndroidJUnit4::class)
class PixelWheelsVisualArabicInstrumentedTest {

    @Test
    fun arabicMenuLabelsAreVisiblyRenderedWithMergedFont() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val amiri = ArabicVisualRenderCheck.renderTypefaceFromAsset(
            context, "fonts/Amiri-Regular.ttf", "amiri-template.ttf",
        )
        val outcome = ArabicVisualRenderCheck.run(
            context = context,
            instrumentation = InstrumentationRegistry.getInstrumentation(),
            templateWord = "العربية",
            typefaces = listOf(amiri),
            reportPrefix = "pixelwheels",
            minMatchedLines = 3,
            minF1 = 0.35,
        )
        assertTrue(
            "expected at least 3 menu lines visibly rendering the shaped Arabic word. ${outcome.report}",
            outcome.matchedLines >= 3,
        )
    }
}
