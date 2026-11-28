package com.omega.arabify

import org.junit.Assert.*
import org.junit.Test

class PhraseTermBaseTest {
    private val base = PhraseTermBase(mapOf(
        "new game" to "لعبة جديدة",
        "settings" to "الإعدادات",
        "game" to "لعبة",
        "save" to "حفظ",
    ))

    @Test
    fun fullCoverageComposes() {
        val r = base.compose("New Game / Settings")
        assertNotNull(r)
        assertEquals("لعبة جديدة / الإعدادات", r!!.text)
        assertEquals(listOf("new game", "settings"), r.phrasesUsed)
        assertEquals(1, base.composed)
    }

    @Test
    fun longestMatchWins() {
        val r = base.compose("new game")
        assertEquals("لعبة جديدة", r!!.text)
        assertEquals(listOf("new game"), r.phrasesUsed)
    }

    @Test
    fun unknownWordFallsThrough() {
        assertNull(base.compose("Delete world?"))
        assertEquals(1, base.partial)
    }

    @Test
    fun wordBoundariesRespected() {
        // "games" must NOT match the term "game"
        assertNull(base.compose("games"))
    }

    @Test
    fun punctuationSeparated() {
        val r = base.compose("Save, Settings!")
        assertEquals("حفظ, الإعدادات!", r!!.text)
    }

    @Test
    fun blankAndEmptyReturnNull() {
        assertNull(base.compose(""))
        assertNull(base.compose("   "))
    }
}
