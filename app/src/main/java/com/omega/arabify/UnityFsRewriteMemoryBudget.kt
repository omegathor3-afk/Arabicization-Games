package com.omega.arabify

/**
 * Applies a conservative heap-derived limit to the UnityFS write path.
 *
 * UnityFsBundleRewriter currently retains several full-size representations
 * during compression and decode-back verification. Until that pipeline is
 * file-backed, keep the decoded stream to at most 1/64 of the app heap and
 * 16 MiB, leaving headroom for the UI, model runtime and transient copies.
 */
internal object UnityFsRewriteMemoryBudget {
    private const val MIN_SUPPORTED_HEAP_BYTES = 32L * 1024L * 1024L
    private const val MAX_REWRITE_DECODED_BYTES = 16L * 1024L * 1024L
    private const val HEAP_DECODED_DIVISOR = 64L

    fun constrain(
        limits: UnityFsDecodeLimits,
        maxHeapBytes: Long = Runtime.getRuntime().maxMemory()
    ): UnityFsDecodeLimits {
        require(maxHeapBytes >= MIN_SUPPORTED_HEAP_BYTES) {
            "ذاكرة التطبيق المتاحة صغيرة جدًا لإعادة كتابة حزم UnityFS بأمان."
        }

        val workingSetBytes = minOf(limits.maxWorkingSetBytes, maxHeapBytes / 4L)
        val decodedBytes = minOf(
            limits.maxTotalDecodedBytes,
            MAX_REWRITE_DECODED_BYTES,
            maxHeapBytes / HEAP_DECODED_DIVISOR
        )
        require(workingSetBytes >= 4L * 1024L * 1024L && decodedBytes > 0L) {
            "ميزانية الذاكرة لا تسمح بإعادة كتابة حزمة UnityFS بأمان."
        }

        return limits.copy(
            maxCompressedBlockBytes = minOf(limits.maxCompressedBlockBytes, decodedBytes),
            maxDecodedBlockBytes = minOf(limits.maxDecodedBlockBytes, decodedBytes),
            maxTotalDecodedBytes = decodedBytes,
            maxBlockInfoBytes = minOf(limits.maxBlockInfoBytes, workingSetBytes / 4L),
            maxLzmaMemoryBytes = minOf(limits.maxLzmaMemoryBytes, workingSetBytes / 2L),
            maxWorkingSetBytes = workingSetBytes
        )
    }
}
