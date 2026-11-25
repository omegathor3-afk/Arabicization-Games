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
        val words = text.lowercase().split(Regex("[^a-z]+")).filter { it.isNotEmpty() }
        if (words.size >= 3 && words.count { it in ENGLISH_MARKERS } == 0) return Verdict.NOT_ENGLISH
        return Verdict.ENGLISH
    }

    fun isSafeToTranslate(text: String): Boolean = classify(text) == Verdict.ENGLISH

    private val ENGLISH_MARKERS = setOf(
        "the", "a", "an", "and", "or", "of", "to", "in", "on", "for", "with", "is", "are",
        "you", "your", "new", "load", "save", "game", "play", "start", "exit", "quit",
        "options", "settings", "back", "next", "yes", "no", "ok", "cancel", "continue",
    )
}
