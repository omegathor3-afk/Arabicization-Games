package com.omega.arabify

import org.junit.Assert.*
import org.junit.Test

class RoundTripVerifierTest {
    private val verifier = RoundTripVerifier()

    @Test
    fun faithfulRoundTripPasses() {
        val v = verifier.verify(
            "Delete this world permanently?",
            "Do you want to delete this world permanently?",
        )
        assertTrue(v.trustworthy)
        assertTrue(v.overlap >= 0.5)
    }

    @Test
    fun hallucinatedRoundTripFails() {
        val v = verifier.verify(
            "Upgrade the refinery to level 3",
            "Welcome to the main menu",
        )
        assertFalse(v.trustworthy)
        assertTrue(v.missingWords.containsAll(listOf("upgrade", "refinery", "level")))
    }

    @Test
    fun functionWordsDoNotAffectOverlap() {
        val v = verifier.verify("The unit will move with the army", "unit move army")
        assertTrue(v.trustworthy)
    }

    @Test
    fun emptySourcePasses() {
        assertTrue(verifier.verify("...", "anything").trustworthy)
    }

    @Test
    fun thresholdIsRespected() {
        val strict = RoundTripVerifier(minContentOverlap = 1.0)
        val v = strict.verify("gold iron copper", "gold iron")
        assertFalse(v.trustworthy)
        assertEquals(listOf("copper"), v.missingWords)
    }
}
