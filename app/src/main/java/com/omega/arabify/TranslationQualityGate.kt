package com.omega.arabify

internal data class TranslationQualityResult(
    val accepted: Boolean,
    val reason: String? = null
)

/**
 * Conservative offline quality gate. It does not claim to be an AI reviewer:
 * it rejects empty output, lost/duplicated/reordered placeholders, newly
 * introduced placeholder syntax, and English-only output for English
 * sentences, while allowing short labels and proper names through.
 */
internal object TranslationQualityGate {
    private val arabic = Regex("[\\u0600-\\u06FF]")

    fun review(source: String, translated: String, expectedTokens: List<String> = emptyList()): TranslationQualityResult {
        if (translated.isBlank()) return TranslationQualityResult(false, "empty_translation")
        if (expectedTokens.any { PlaceholderProtector.countOccurrences(translated, it) == 0 }) {
            return TranslationQualityResult(false, "placeholder_lost")
        }
        if (expectedTokens.any { PlaceholderProtector.countOccurrences(translated, it) > 1 }) {
            return TranslationQualityResult(false, "placeholder_duplicated")
        }
        val markerPositions = expectedTokens.map { translated.indexOf(it) }
        if (markerPositions.zipWithNext().any { (previous, next) -> previous >= next }) {
            return TranslationQualityResult(false, "placeholder_order_changed")
        }
        if (PlaceholderProtector.containsUnprotectedTokens(translated)) {
            return TranslationQualityResult(false, "unexpected_placeholder")
        }
        val sourceHasLatinWords = Regex("[A-Za-z]{3,}").containsMatchIn(source)
        val sourceLooksLikeSentence = source.trim().length >= 12 && source.any(Char::isWhitespace)
        if (sourceHasLatinWords && sourceLooksLikeSentence && !arabic.containsMatchIn(translated)) {
            return TranslationQualityResult(false, "no_arabic_output")
        }
        if (translated.length > maxOf(1200, source.length * 4)) {
            return TranslationQualityResult(false, "unexpected_expansion")
        }
        return TranslationQualityResult(true)
    }
}
