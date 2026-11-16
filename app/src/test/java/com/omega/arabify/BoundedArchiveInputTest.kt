package com.omega.arabify

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class BoundedArchiveInputTest {
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
    fun acceptsAnEmptyStreamWithAZeroByteLimit() {
        val output = ByteArrayOutputStream()
        val copied = BoundedArchiveInput.copy(ByteArrayInputStream(byteArrayOf()), output, 0L)

        assertEquals(0L, copied)
        assertEquals(0, output.size())
    }
}
