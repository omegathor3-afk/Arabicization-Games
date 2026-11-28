package com.omega.arabify

/**
 * Phrase term base with FULL-COVERAGE composition: a string is translated
 * phrase-by-phrase only when every word in it belongs to a known term
 * (longest match first). Partial coverage returns null and the caller falls
 * through to the MT engine - a half-known string must never ship with
 * untranslated words glued to glossary Arabic.
 *
 * Separators (whitespace, punctuation, slashes) between covered phrases are
 * preserved. Arabic output joins in RTL order: Arabic reads right-to-left, so
 * the first English phrase's translation must come FIRST in the string.
 */
internal class PhraseTermBase(terms: Map<String, String>) {

    data class Result(val text: String, val phrasesUsed: List<String>)

    // Longest-first so "new game" beats "game" when both exist.
    private val ordered: List<Pair<String, String>> =
        terms.entries.map { it.key.lowercase() to it.value }.sortedByDescending { it.first.length }

    var composed = 0; private set
    var partial = 0; private set

    fun compose(text: String): Result? {
        if (text.isBlank()) return null
        val segments = mutableListOf<Segment>()
        var i = 0
        val lower = text.lowercase()
        while (i < text.length) {
            val ch = text[i]
            if (ch.isWhitespace() || ch in SEPARATOR_CHARS) {
                val start = i
                while (i < text.length && (text[i].isWhitespace() || text[i] in SEPARATOR_CHARS)) i++
                segments += Segment.Separator(text.substring(start, i))
                continue
            }
            val match = ordered.firstOrNull { (term, _) -> lower.startsWith(term, i) && boundaryOk(lower, i, term.length) }
            if (match == null) {
                partial++
                return null
            }
            segments += Segment.Term(match.second, match.first)
            i += match.first.length
        }
        val sb = StringBuilder()
        val used = mutableListOf<String>()
        for (segment in segments) when (segment) {
            is Segment.Separator -> sb.append(segment.text)
            is Segment.Term -> { sb.append(segment.translation); used += segment.source }
        }
        val composedText = sb.toString().trim()
        if (composedText.isEmpty()) { partial++; return null }
        composed++
        return Result(composedText, used)
    }

    private fun boundaryOk(lower: String, start: Int, length: Int): Boolean {
        val end = start + length
        val before = start == 0 || !lower[start - 1].isLetterOrDigit()
        val after = end >= lower.length || !lower[end].isLetterOrDigit()
        return before && after
    }

    private sealed interface Segment {
        data class Separator(val text: String) : Segment
        data class Term(val translation: String, val source: String) : Segment
    }

    private companion object {
        const val SEPARATOR_CHARS = ",;:/\\|()[]-–—.!?"
    }
}
