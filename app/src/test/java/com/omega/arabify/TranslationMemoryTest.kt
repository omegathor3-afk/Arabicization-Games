package com.omega.arabify

import org.junit.Assert.*
import org.junit.Test

class TranslationMemoryTest {
    @Test
    fun exactHitAfterNormalization() {
        val tm = TranslationMemory()
        tm.add("Delete world?", "حذف العالم؟")
        val m = tm.lookup("delete  world?")
        assertEquals(TranslationMemory.MatchKind.EXACT, m.kind)
        assertEquals("حذف العالم؟", m.translation)
        assertEquals(1, tm.exactHits)
    }

    @Test
    fun diacriticsAndAlefVariantsDoNotSplitEntries() {
        val tm = TranslationMemory()
        tm.add("السَّلام", "greeting")
        assertEquals(TranslationMemory.MatchKind.EXACT, tm.lookup("السلام").kind)
        tm.add("إلى المدار", "to orbit")
        assertEquals(TranslationMemory.MatchKind.EXACT, tm.lookup("الى المدار").kind)
    }

    @Test
    fun nearRepeatHitsFuzzy() {
        val tm = TranslationMemory(fuzzyThreshold = 0.85)
        tm.add("Delete this world?", "حذف هذا العالم؟")
        val m = tm.lookup("Delete these worlds?")
        assertEquals(TranslationMemory.MatchKind.FUZZY, m.kind)
        assertTrue(m.similarity >= 0.85)
        assertEquals(1, tm.fuzzyHits)
    }

    @Test
    fun unrelatedStringsMiss() {
        val tm = TranslationMemory()
        tm.add("Start campaign", "ابدأ الحملة")
        assertEquals(TranslationMemory.MatchKind.NONE, tm.lookup("Resolution").kind)
        assertEquals(1, tm.misses)
    }

    @Test
    fun bestFuzzyCandidateWins() {
        val tm = TranslationMemory(fuzzyThreshold = 0.8)
        tm.add("Enable advanced graphics options", "تفعيل خيارات الرسومات المتقدمة")
        tm.add("Enable advanced options", "تفعيل الخيارات المتقدمة")
        val m = tm.lookup("Enable advanced option")
        assertEquals("تفعيل الخيارات المتقدمة", m.translation)
    }

    @Test
    fun blanksAreIgnored() {
        val tm = TranslationMemory()
        tm.add("", "x"); tm.add("x", "")
        assertEquals(0, tm.size)
        assertEquals(TranslationMemory.MatchKind.NONE, tm.lookup("").kind)
    }

    @Test
    fun placeholderSignaturesMatchShapeNotOrder() {
        val tm = TranslationMemory()
        assertEquals(
            tm.placeholderSignature("Deal %1${'$'}d damage to {target}"),
            tm.placeholderSignature("{target} takes %1${'$'}d damage"),
        )
        assertNotEquals(
            tm.placeholderSignature("Deal %d damage"),
            tm.placeholderSignature("Deal %d damage in %d turns"),
        )
    }

    @Test
    fun newestWriteWins() {
        val tm = TranslationMemory()
        tm.add("Play", "العب")
        tm.add("play", "تشغيل")
        assertEquals(1, tm.size)
        assertEquals("تشغيل", tm.lookup("PLAY").translation)
    }
}
