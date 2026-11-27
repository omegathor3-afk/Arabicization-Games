package com.omega.arabify

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.CancellationException
import org.junit.Assert.assertTrue

class BoundedArchiveInputTest {
    @Test
    fun treatsNullProviderSizeAsUnknownInsteadOfZero() {
        assertNull(BoundedArchiveInput.declaredSizeOrNull(columnIsNull = true, value = 0L))
    }

    @Test
    fun treatsNegativeProviderSizeAsUnknown() {
        assertNull(BoundedArchiveInput.declaredSizeOrNull(columnIsNull = false, value = -1L))
    }

    @Test
    fun preservesKnownZeroAndPositiveProviderSizes() {
        assertEquals(0L, BoundedArchiveInput.declaredSizeOrNull(columnIsNull = false, value = 0L))
        assertEquals(42L, BoundedArchiveInput.declaredSizeOrNull(columnIsNull = false, value = 42L))
    }

    @Test
    fun copiesWithinLimitAndReturnsExactByteCount() {
        val bytes = "ARABIFY — ملف لعبة".toByteArray(Charsets.UTF_8)
        val output = ByteArrayOutputStream()

        val copied = BoundedArchiveInput.copy(ByteArrayInputStream(bytes), output, bytes.size.toLong())

        assertEquals(bytes.size.toLong(), copied)
        assertArrayEquals(bytes, output.toByteArray())
    }

    @Test
    fun refusesActualBytesAboveLimitWithoutWritingTheOverflowChunk() {
        val bytes = ByteArray(64 * 1024 + 10) { (it % 251).toByte() }
        val output = ByteArrayOutputStream()
        try {
            BoundedArchiveInput.copy(ByteArrayInputStream(bytes), output, 64L * 1024L)
            throw AssertionError("Copy should stop when the actual byte limit is exceeded")
        } catch (_: IllegalArgumentException) {
            // Expected: the second read is rejected before it is written.
        }

        assertEquals(64 * 1024, output.size())
        assertArrayEquals(bytes.copyOf(64 * 1024), output.toByteArray())
    }

    @Test
    fun readAllPropagatesCancellationBetweenChunks() {
        var checks = 0
        var cancelled = false
        try {
            BoundedArchiveInput.readAll(ByteArrayInputStream(ByteArray(32 * 1024)), 32L * 1024L) {
                checks++
                if (checks == 2) throw CancellationException("cancel read")
            }
        } catch (_: CancellationException) {
            cancelled = true
        }

        assertTrue(cancelled)
        assertEquals(2, checks)
    }

    @Test
    fun copyPropagatesCancellationBeforeReadingTheNextChunk() {
        val output = ByteArrayOutputStream()
        var checks = 0
        var cancelled = false
        try {
            BoundedArchiveInput.copy(
                ByteArrayInputStream(ByteArray(128 * 1024)),
                output,
                128L * 1024L
            ) {
                checks++
                if (checks == 2) throw CancellationException("cancel copy")
            }
        } catch (_: CancellationException) {
            cancelled = true
        }

        assertTrue(cancelled)
        assertEquals(2, checks)
        assertEquals(64 * 1024, output.size())
    }

    @Test
    fun acceptsAnEmptyStreamWithAZeroByteLimit() {
        val output = ByteArrayOutputStream()
        val copied = BoundedArchiveInput.copy(ByteArrayInputStream(byteArrayOf()), output, 0L)

        assertEquals(0L, copied)
        assertEquals(0, output.size())
    }
}
