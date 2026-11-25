package com.omega.arabify

/**
 * Translation memory: every string the app has translated becomes an asset,
 * so repeats and near-repeats (menus love near-repeats: "Delete world?" /
 * "Delete this world?") resolve locally instead of hitting the translator.
 *
 * Two tiers:
 *  - exact after normalization (diacritics, alef variants, whitespace, case)
 *  - fuzzy: Levenshtein similarity >= [fuzzyThreshold] returns the best
 *    candidate, but callers must treat it as a suggestion - a fuzzy match can
 *    still be wrong in context, so the report counts fuzzy uses separately.
 *
 * Placeholder-bearing sources are matched with their placeholders intact;
 * the caller is responsible for re-validating placeholder compatibility
 * (PlaceholderProtector) before accepting a hit.
 */
class TranslationMemory(
    private val fuzzyThreshold: Double = 0.85,
    private val maxEntries: Int = 50_000,
) {
    enum class MatchKind { NONE, EXACT, FUZZY }

    data class Match(val kind: MatchKind, val source: String, val translation: String, val similarity: Double)

    private data class Entry(val source: String, val translation: String)

    private val entries = LinkedHashMap<String, Entry>()

    var exactHits = 0; private set
    var fuzzyHits = 0; private set
    var misses = 0; private set

    val size: Int get() = entries.size

    fun add(source: String, translation: String) {
        if (source.isBlank() || translation.isBlank()) return
        val key = normalize(source)
        if (key.isEmpty()) return
        if (!entries.containsKey(key) && entries.size >= maxEntries) {
            entries.remove(entries.keys.first())
        }
        entries[key] = Entry(source, translation)
    }

    fun lookup(source: String): Match {
        val key = normalize(source)
        if (key.isEmpty()) { misses++; return Match(MatchKind.NONE, source, "", 0.0) }
        entries[key]?.let {
            exactHits++
            return Match(MatchKind.EXACT, it.source, it.translation, 1.0)
        }
        var best: Entry? = null
        var bestSim = 0.0
        for (entry in entries.values) {
            val candidate = normalize(entry.source)
            if (kotlin.math.abs(candidate.length - key.length) > key.length) continue
            val sim = similarity(key, candidate)
            if (sim > bestSim) { bestSim = sim; best = entry }
        }
        if (best != null && bestSim >= fuzzyThreshold) {
            fuzzyHits++
            return Match(MatchKind.FUZZY, best.source, best.translation, bestSim)
        }
        misses++
        return Match(MatchKind.NONE, source, "", 0.0)
    }

    /**
     * Normalization is the difference between a TM that hits and one that
     * doesn't: Arabic diacritics and alef hamza variants must not split
     * otherwise-identical strings, and game sources mix cases/whitespace.
     */
    internal fun normalize(value: String): String {
        val sb = StringBuilder(value.length)
        for (ch in value) {
            when {
                ch in 'ً'..'ْ' || ch == 'ـ' -> Unit // harakat + tatweel
                ch == 'أ' || ch == 'إ' || ch == 'آ' || ch == 'ٱ' -> sb.append('ا')
                ch == 'ى' -> sb.append('ي')
                ch.isWhitespace() -> sb.append(' ')
                else -> sb.append(ch.lowercaseChar())
            }
        }
        return sb.toString().replace(Regex(" +"), " ").trim()
    }

    internal fun similarity(a: String, b: String): Double {
        if (a == b) return 1.0
        val maxLen = maxOf(a.length, b.length)
        if (maxLen == 0) return 1.0
        return 1.0 - levenshtein(a, b).toDouble() / maxLen
    }

    private fun levenshtein(a: String, b: String): Int {
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var curr = IntArray(b.length + 1)
        for (i in 1..a.length) {
            curr[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = minOf(prev[j] + 1, curr[j - 1] + 1, prev[j - 1] + cost)
            }
            val tmp = prev; prev = curr; curr = tmp
        }
        return prev[b.length]
    }
}
