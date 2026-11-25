package com.omega.arabify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class UnityFsRewriteMemoryBudgetTest {
    private val mib = 1024L * 1024L

    @Test
    fun derivesConservativeLimitsFromA256MiBAppHeap() {
        val result = UnityFsRewriteMemoryBudget.constrain(
            UnityFsDecodeLimits(),
            maxHeapBytes = 256L * mib
        )

        assertEquals(4L * mib, result.maxTotalDecodedBytes)
        assertEquals(4L * mib, result.maxDecodedBlockBytes)
        assertEquals(4L * mib, result.maxCompressedBlockBytes)
        assertEquals(16L * mib, result.maxBlockInfoBytes)
        assertEquals(32L * mib, result.maxLzmaMemoryBytes)
        assertEquals(64L * mib, result.maxWorkingSetBytes)
    }

    @Test
    fun keepsHardDecodedCeilingEvenOnLargeHeap() {
        val result = UnityFsRewriteMemoryBudget.constrain(
            UnityFsDecodeLimits(),
            maxHeapBytes = 2L * 1024L * mib
        )

        assertEquals(16L * mib, result.maxTotalDecodedBytes)
        assertEquals(16L * mib, result.maxDecodedBlockBytes)
        assertEquals(16L * mib, result.maxCompressedBlockBytes)
    }


    @Test
    fun usesOneSixtyFourthOfHeapWhenCallerAllowsMore() {
        val result = UnityFsRewriteMemoryBudget.constrain(
            UnityFsDecodeLimits(),
            maxHeapBytes = 512L * mib
        )

        assertEquals(8L * mib, result.maxTotalDecodedBytes)
        assertEquals(8L * mib, result.maxDecodedBlockBytes)
        assertEquals(8L * mib, result.maxCompressedBlockBytes)
    }

    @Test
    fun neverRaisesCallerProvidedBudgets() {
        val supplied = UnityFsDecodeLimits(
            maxCompressedBlockBytes = 2L * mib,
            maxDecodedBlockBytes = 3L * mib,
            maxTotalDecodedBytes = 4L * mib,
            maxBlockInfoBytes = 5L * mib,
            maxLzmaMemoryBytes = 6L * mib,
            maxWorkingSetBytes = 7L * mib
        )

        val result = UnityFsRewriteMemoryBudget.constrain(
            supplied,
            maxHeapBytes = 512L * mib
        )

        assertEquals(2L * mib, result.maxCompressedBlockBytes)
        assertEquals(3L * mib, result.maxDecodedBlockBytes)
        assertEquals(4L * mib, result.maxTotalDecodedBytes)
        assertEquals(7L * mib / 4L, result.maxBlockInfoBytes)
        assertEquals(7L * mib / 2L, result.maxLzmaMemoryBytes)
        assertEquals(7L * mib, result.maxWorkingSetBytes)
    }

    @Test
    fun rejectsHeapsBelowTheSupportedMinimum() {
        assertThrows(IllegalArgumentException::class.java) {
            UnityFsRewriteMemoryBudget.constrain(
                UnityFsDecodeLimits(),
                maxHeapBytes = 16L * mib
            )
        }
    }
}
