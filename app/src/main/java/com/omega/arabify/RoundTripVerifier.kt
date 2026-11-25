package com.omega.arabify

import kotlin.math.max

/**
 * Round-trip verification: translate the Arabic candidate BACK to the source
 * language and compare. A translation that cannot make the return trip
 * without losing the core content words is flagged - it usually means the
 * engine hallucinated or dropped meaning. This is a heuristic gate, not a
 * proof: legitimate rephrasings also diverge, so callers use it to FLAG for
 * review or fall back, never to invent a replacement.
 *
 * Comparison is content-word based (function words and punctuation ignored)
 * because round trips legitimately reorder and rephrase.
 */
internal class RoundTripVerifier(
    private val minContentOverlap: Double = 0.5,
) {
    data class Verdict(val trustworthy: Boolean, val overlap: Double, val missingWords: List<String>)

    fun verify(source: String, roundTripped: String): Verdict {
        val sourceWords = contentWords(source)
        if (sourceWords.isEmpty()) return Verdict(trustworthy = true, overlap = 1.0, missingWords = emptyList())
        val backWords = contentWords(roundTripped)
        val missing = sourceWords.filter { it !in backWords }
        val overlap = 1.0 - missing.size.toDouble() / max(1, sourceWords.size)
        return Verdict(overlap >= minContentOverlap, overlap, missing)
    }

    /** Content words: alphabetic tokens of length >= 3, lowercased, stopwords out. */
    internal fun contentWords(text: String): List<String> =
        text.lowercase()
            .split(Regex("[^\\p{L}]+"))
            .filter { it.length >= 3 && it.all(Char::isLetter) && it !in STOPWORDS }
            .distinct()

    private companion object {
        val STOPWORDS = setOf(
            "the", "and", "for", "are", "you", "your", "yours", "with", "this", "that",
            "these", "those", "from", "into", "onto", "will", "would", "could", "should",
            "have", "has", "had", "not", "but", "all", "any", "can", "out", "off",
        )
    }
}
