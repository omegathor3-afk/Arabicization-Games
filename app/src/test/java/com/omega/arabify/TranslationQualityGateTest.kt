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
            TranslationQualityGate.review("Hello {player}", "مرحبا " + token + " " + token, listOf(token)).reason
        )
    }

    @Test fun rejectsReorderedFormatPlaceholders() {
        val protected = PlaceholderProtector.protect("You found %s after %d tries")
        val translated = "وجدت " + protected.markers[1] + " بعد " + protected.markers[0] + " محاولة"
        assertEquals(
            "placeholder_order_changed",
            TranslationQualityGate.review("You found %s after %d tries", translated, protected.markers).reason
        )
    }

    @Test fun rejectsPlaceholderSyntaxIntroducedByTranslation() {
        assertEquals(
            "unexpected_placeholder",
            TranslationQualityGate.review("Welcome home", "مرحبًا {enemy}").reason
        )
    }

    @Test fun rejectsNewFormatSpecifierEvenWhenOriginalMarkerSurvives() {
        val protected = PlaceholderProtector.protect("Hello {player}")
        val translated = "مرحبًا " + protected.markers.single() + " %s"
        assertEquals(
            "unexpected_placeholder",
            TranslationQualityGate.review("Hello {player}", translated, protected.markers).reason
        )
    }
}
