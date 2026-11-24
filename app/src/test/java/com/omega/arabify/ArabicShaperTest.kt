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
}
