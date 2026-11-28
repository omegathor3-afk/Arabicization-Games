package com.omega.arabify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArabicShaperTest {
    private fun cp(vararg c: Int) = String(c, 0, c.size)

    @Test fun latinPassesThrough() = assertEquals("Play", ArabicShaper.toVisual("Play"))

    @Test fun isolatedSingleLetter() = assertEquals(cp(0xFE8F), ArabicShaper.toVisual("ب"))

    // "بب": initial + final, visual order = final first
    @Test fun twoDualLettersConnect() = assertEquals(cp(0xFE90, 0xFE91), ArabicShaper.toVisual("بب"))

    // "ابا": alef isolated, beh isolated? beh follows alef (alef does not join next) and precedes alef -> initial; alef after beh -> final
    @Test fun rightJoiningBreaksChain() = assertEquals(cp(0xFE8E, 0xFE91, 0xFE8D), ArabicShaper.toVisual("ابا"))

    // لا ligature isolated
    @Test fun lamAlefLigature() = assertEquals(cp(0xFEFB), ArabicShaper.toVisual("لا"))

    // "ملا": meem initial + lam-alef final ligature; visual: ligature first
    @Test fun lamAlefFinalAfterJoiner() = assertEquals(cp(0xFEFC, 0xFEE3), ArabicShaper.toVisual("ملا"))

    @Test fun diacriticsDoNotBreakJoining() {
        // beh + fatha + beh: initial beh, mark, final beh -> visual: final, initial, mark-after-base order check
        val v = ArabicShaper.toVisual("بَب")
        assertTrue(v.contains(cp(0xFE90)) && v.contains(cp(0xFE91)) && v.contains('\u064E'))
    }

    @Test fun latinRunKeepsOrderInsideArabic() {
        val v = ArabicShaper.toVisual("لعبة Mindustry جديدة")
        assertTrue(v.contains("Mindustry"))
    }

    @Test fun placeholdersAreNotMirroredOrShaped() {
        val v = ArabicShaper.toVisual("مرحبا {0} و %s")
        assertTrue(v.contains("{0}") && v.contains("%s"))
    }

    @Test fun newlinesPreserved() {
        assertEquals(2, ArabicShaper.toVisual("با\nبا").split("\n").size)
    }

    // Regression: Mindustry rendered "لعربية" instead of "العربية" because the merged
    // font lacked FE8D and libGDX silently dropped it. Pin the exact forms emitted.
    @Test fun wordStartingWithAlefKeepsIsolatedAlefForm() {
        val v = ArabicShaper.toVisual("العربية")
        assertTrue(v.contains(cp(0xFE8D)))
        assertEquals(cp(0xFE8D), v.last().toString())
    }

    // Regression: "الإعدادات" lost its leading alef AND the lam-hamza ligature forms.
    @Test fun wordWithLamHamzaLigatureKeepsLeadingAlef() {
        val v = ArabicShaper.toVisual("الإعدادات")
        assertTrue(v.contains(cp(0xFE8D)))
        assertTrue(v.contains(cp(0xFEF9)) || v.contains(cp(0xFEFA)))
    }

    @Test fun everyShapedFormCodepointIsInEmitSet() {
        listOf("العربية", "الإعدادات", "لعبة الملاحظات", "مرحبا بالعالم", "تادادعإلأ", "مستوى 8")
            .forEach { text ->
                ArabicShaper.toVisual(text).codePoints().forEach { c ->
                    if (c in 0xFE70..0xFEFF) {
                        assertTrue("emit set must contain U+%04X (from %s)".format(c, text),
                            c in ArabicShaper.emittedCodepoints)
                    }
                }
            }
    }
}
