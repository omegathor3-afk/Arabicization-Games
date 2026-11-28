package com.omega.arabify

/**
 * Source-language guard: the pipeline assumes English source strings.
 * Strings that are already Arabic (re-running the app over its own output),
 * or clearly not English (another Latin-script language, CJK), must not be
 * fed to the EN->AR engine - the output would be garbage attributed to us.
 * Lightweight statistical classifier: script ratios + English function-word
 * signal. It answers "safe to translate as English?" - not "which language".
 */
internal object SourceLanguageGuard {

    enum class Verdict { ENGLISH, ALREADY_ARABIC, NOT_ENGLISH, TOO_SHORT }

    fun classify(text: String): Verdict {
        val letters = text.filter { it.isLetter() }
        if (letters.length < 3) return Verdict.TOO_SHORT
        val arabic = letters.count { it.code in 0x0600..0x06FF }
        if (arabic * 2 >= letters.length) return Verdict.ALREADY_ARABIC
        val latin = letters.count { it.code < 0x0250 }
        if (latin * 2 < letters.length) return Verdict.NOT_ENGLISH
        // Marker absence only convicts longer strings: short English game
        // strings routinely contain no function words ("Hit one vehicle"),
        // and skipping real English costs a translation; a rare French
        // false-pass costs one odd string. Five words is the balance.
        // Placeholder tokens ([unit], {0}, %1$s) are not words - counting them
        // convicted real English like "Provides [amountOfCulture] culture".
        // Note: ']' and '}' are escaped inside the negated classes because
        // Android's ICU regex engine rejects the unescaped '[^]]' idiom that
        // the JDK accepts (caught by every connected test at once).
        val stripped = text.replace(Regex("\\[[^\\]]*\\]|\\{[^\\}]*\\}|%[0-9]*\\$?[sdif]"), " ")
        val words = stripped.lowercase().split(Regex("[^a-z]+")).filter { it.isNotEmpty() }
        if (words.size >= 5 && words.count { it in ENGLISH_MARKERS } == 0) return Verdict.NOT_ENGLISH
        return Verdict.ENGLISH
    }

    fun isSafeToTranslate(text: String): Boolean = classify(text) == Verdict.ENGLISH

    private val ENGLISH_MARKERS = setOf(
        "the", "a", "an", "and", "or", "of", "to", "in", "on", "for", "with", "is", "are",
        "you", "your", "new", "load", "save", "game", "play", "start", "exit", "quit",
        "options", "settings", "back", "next", "yes", "no", "ok", "cancel", "continue",
        // Function words that appear in real game strings ("May our nations
        // forever remain united!"); measured against Unciv's 6,578-string
        // English.properties, whose false-positive count this list minimizes.
        "at", "by", "vs", "we", "us", "our", "they", "them", "their", "it", "its",
        "he", "she", "his", "her", "my", "mine", "me", "i", "am", "was", "were",
        "be", "been", "being", "do", "does", "did", "may", "might", "must", "shall",
        "will", "would", "can", "could", "should", "have", "has", "had", "not",
        "but", "all", "any", "this", "that", "these", "those", "from", "into",
        "out", "off", "up", "down", "every", "each", "per", "if", "then", "else",
        "when", "while", "until", "before", "after", "between", "over", "under",
    )
}
