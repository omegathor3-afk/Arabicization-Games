package com.omega.arabify

/**
 * Validates UnityFS node paths and byte ranges before any filesystem extraction.
 * This is deliberately lexical: callers must still resolve the destination under
 * a private staging root and verify its canonical path before creating a file.
 */
internal object UnityBundleNodeSafety {
    fun safeRelativePath(rawPath: String): String? {
        if (rawPath.isBlank() || rawPath.length > 4096 || '\u0000' in rawPath) return null
        val normalized = rawPath.replace('\\', '/')
        if (normalized.startsWith('/') || normalized.startsWith("//")) return null
        if (Regex("^[A-Za-z]:").containsMatchIn(normalized)) return null
        if (normalized.startsWith("\\\\") || normalized.contains("://")) return null

        val segments = normalized.split('/')
        if (segments.isEmpty() || segments.any { it.isEmpty() || it == "." || it == ".." }) return null
        if (segments.any { segment ->
                segment.any { it.code < 0x20 } ||
                    segment.endsWith('.') || segment.endsWith(' ') ||
                    segment.any { it in charArrayOf('<', '>', ':', '"', '|', '?', '*') }
            }
        ) return null
        return segments.joinToString("/")
    }

    fun validateNodeLayout(nodes: List<UnityBundleNode>, totalUncompressedBytes: Long): Boolean {
        if (totalUncompressedBytes < 0L) return false
        val seenPaths = HashSet<String>()
        val ranges = ArrayList<Pair<Long, Long>>(nodes.size)
        for (node in nodes) {
            if (node.offset < 0L || node.size < 0L ||
                node.offset > totalUncompressedBytes ||
                node.size > totalUncompressedBytes - node.offset
            ) return false
            val path = safeRelativePath(node.path) ?: return false
            if (!seenPaths.add(path.lowercase(java.util.Locale.ROOT))) return false
            if (node.size > 0L) ranges += node.offset to (node.offset + node.size)
        }
        ranges.sortBy { it.first }
        for (index in 1 until ranges.size) {
            if (ranges[index].first < ranges[index - 1].second) return false
        }
        return true
    }
}
