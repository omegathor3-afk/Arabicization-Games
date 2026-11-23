package com.omega.arabify

import java.util.UUID

internal data class ProtectedText(
    val text: String,
    val tokens: List<String>,
    val markerPrefix: String
) {
    val markers: List<String>
        get() = tokens.indices.map { "$markerPrefix${it}__" }
}

internal object PlaceholderProtector {
    private const val LEGACY_MARKER_PREFIX = "__ARABIFY_TOKEN_"
    // Preserve common Java/C-style format strings (including width/precision),
    // literal percent escapes, named game tokens, markup tags and escaped newlines.
    private val tokenPattern = Regex(
        "%(?:[0-9]+\\$)?[-#+0,(<]*[0-9]*(?:\\.[0-9]+)?(?:[bBhHsScCdioxXuUeEfgGaApP]|[tT][a-zA-Z])|%%|%n|\\$\\{[^}]+\\}|\\{[^{}]+\\}|<[^>]+>|\\\\[nt]"
    )

    fun protect(source: String): ProtectedText {
        val markerPrefix = if (source.contains(LEGACY_MARKER_PREFIX)) {
            collisionFreeMarkerPrefix(source)
        } else {
            LEGACY_MARKER_PREFIX
        }
        val tokens = mutableListOf<String>()
        val protected = tokenPattern.replace(source) { match ->
            val index = tokens.size
            tokens += match.value
            "$markerPrefix${index}__"
        }
        return ProtectedText(protected, tokens, markerPrefix)
    }

    fun restore(translated: String, protectedText: ProtectedText): String {
        var result = translated
        protectedText.markers.forEachIndexed { index, marker ->
            result = result.replace(marker, protectedText.tokens[index])
        }
        return result
    }

    private fun collisionFreeMarkerPrefix(source: String): String {
        var candidate: String
        do {
            val nonce = UUID.randomUUID().toString().replace("-", "")
            candidate = "${LEGACY_MARKER_PREFIX}${nonce}_"
        } while (source.contains(candidate))
        return candidate
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

    /**
     * Detects placeholder-like syntax that a translator introduced instead of
     * preserving through the protected markers. Such output must not be written
     * into a game's format string or markup.
     */
    fun containsUnprotectedTokens(text: String): Boolean =
        tokenPattern.containsMatchIn(text)
}
