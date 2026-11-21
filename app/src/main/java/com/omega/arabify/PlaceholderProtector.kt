package com.omega.arabify

internal data class ProtectedText(val text: String, val tokens: List<String>)

internal object PlaceholderProtector {
    // Preserve common Java/C-style format strings (including width/precision),
    // literal percent escapes, named game tokens, markup tags and escaped newlines.
    private val tokenPattern = Regex(
        "%(?:[0-9]+\\$)?[-#+0,(<]*[0-9]*(?:\\.[0-9]+)?(?:[bBhHsScCdioxXuUeEfgGaApP]|[tT][a-zA-Z])|%%|%n|\\$\\{[^}]+\\}|\\{[^{}]+\\}|<[^>]+>|\\\\[nt]"
    )

    fun protect(source: String): ProtectedText {
        val tokens = mutableListOf<String>()
        val protected = tokenPattern.replace(source) { match ->
            val index = tokens.size
            tokens += match.value
            "__ARABIFY_TOKEN_${index}__"
        }
        return ProtectedText(protected, tokens)
    }

    fun restore(translated: String, tokens: List<String>): String {
        var result = translated
        tokens.forEachIndexed { index, token ->
            result = result.replace("__ARABIFY_TOKEN_${index}__", token)
        }
        return result
    }

    /** Count non-overlapping exact occurrences so duplicated game variables are rejected. */
    fun countOccurrences(text: String, token: String): Int {
        if (token.isEmpty()) return 0
        var count = 0
        var offset = 0
        while (true) {
            val index = text.indexOf(token, offset)
            if (index < 0) return count
            count++
            offset = index + token.length
        }
    }
}
