package com.omega.arabify

/**
 * Applies a conservative heap-derived limit to the UnityFS write path.
 *
 * UnityFsBundleRewriter currently retains the decoded stream, a rebuilt stream,
 * compressed chunks, the assembled bundle, and a decoded verification copy.
 * Until that pipeline is file-backed, its decoded input must be much smaller
 * than the decoder's general read-only inspection budget.
 */
internal object UnityFsRewriteMemoryBudget {
    private const val MIN_SUPPORTED_HEAP_BYTES = 32L * 1024L * 1024L
    private const val MAX_REWRITE_DECODED_BYTES = 32L * 1024L * 1024L
    private const val HEAP_DECODED_DIVISOR = 32L

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
