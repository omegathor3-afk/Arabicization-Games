package com.omega.arabify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationQualityGateTest {
    @Test fun acceptsArabicSentence() {
        assertTrue(TranslationQualityGate.review("The door is locked.", "الباب مغلق.").accepted)
    }

    @Test fun rejectsEnglishOnlySentence() {
        assertEquals("no_arabic_output", TranslationQualityGate.review(
            "The ancient door is locked.", "The ancient door is locked."
        ).reason)
    }

    @Test fun rejectsLostPlaceholders() {
        assertEquals("placeholder_lost", TranslationQualityGate.review(
            "Hello {player}", "مرحبًا", listOf("{player}")
        ).reason)
    }

    @Test fun allowsShortProperNames() {
        assertTrue(TranslationQualityGate.review("Darkwood", "Darkwood").accepted)
    }

    @Test fun rejectsDuplicatedPlaceholders() {
        val token = "__ARABIFY_TOKEN_0__"
        assertEquals(
            "placeholder_duplicated",
            TranslationQualityGate.review("Hello {player}", "مرحبا $token $token", listOf(token)).reason
        )
    }
}
