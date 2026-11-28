package com.omega.arabify

import java.io.File
import java.util.zip.Deflater
import org.junit.Assert.assertTrue
import org.junit.Test

class FontAnalyzerTest {
    private val dir = File("src/main/assets/fonts/catalog")
    private fun profile(name: String) = FontAnalyzer.analyze(File(dir, name).readBytes())

    @Test
    fun everyCatalogCandidateCoversArabicCoreAndPresentationForms() {
        val names = dir.listFiles { f -> f.name.endsWith(".ttf") }!!.map { it.name }.sorted()
        assertTrue("no catalog fonts found in " + dir.absolutePath, names.size >= 7)
        for (name in names) {
            val p = profile(name)
            assertTrue(name + " lacks Arabic core: " + p.arabicGlyphs, p.hasArabicCore)
        }
    }

    @Test
    fun amiriHasFullPresentationFormsB() {
        val p = profile("amiri-regular.ttf")
        assertTrue(p.presentationFormsB > 100)
        assertTrue(p.supports(0xFEED))
        assertTrue(p.familyName == "Amiri")
    }

    @Test
    fun boldVariantsReportHeavierWeight() {
        assertTrue(profile("tajawal-bold.ttf").weightClass > profile("tajawal-regular.ttf").weightClass)
    }

    @Test
    fun proportionalCatalogFontsAreNotMonospace() {
        assertTrue(!profile("amiri-regular.ttf").monospace)
        assertTrue(!profile("tajawal-regular.ttf").monospace)
    }

    @Test(expected = FontAnalyzer.FontParseException::class)
    fun rejectsGarbage() {
        FontAnalyzer.analyze(ByteArray(64) { 7 })
    }

    @Test(expected = FontAnalyzer.FontParseException::class)
    fun rejectsTruncatedWoffTableDirectory() {
        val bytes = ByteArray(44)
        put32(bytes, 0, 0x774F4646L)
        put32(bytes, 4, 0x00010000L)
        put32(bytes, 8, bytes.size.toLong())
        put16(bytes, 12, 1)
        FontAnalyzer.analyze(bytes)
    }

    @Test(expected = FontAnalyzer.FontParseException::class)
    fun rejectsOversizedWoffTableBeforeAllocation() {
        FontAnalyzer.analyze(woff(0x7FFFFFFFL))
    }

    @Test(expected = FontAnalyzer.FontParseException::class)
    fun rejectsOutOfRangeSfntTableOffset() {
        val bytes = ByteArray(28)
        put32(bytes, 0, 0x00010000L)
        put16(bytes, 4, 1)
        System.arraycopy("head".toByteArray(Charsets.US_ASCII), 0, bytes, 12, 4)
        put32(bytes, 20, 0xFFFFFFF0L)
        put32(bytes, 24, 64L)
        FontAnalyzer.analyze(bytes)
    }

    @Test(expected = FontAnalyzer.FontParseException::class)
    fun rejectsWoffDecompressionLengthMismatch() {
        val deflater = Deflater()
        val original = ByteArray(100) { 65 }
        deflater.setInput(original)
        deflater.finish()
        val buffer = ByteArray(1024)
        val count = deflater.deflate(buffer)
        deflater.end()
        val compressed = buffer.copyOf(count)
        assertTrue(compressed.size < 99)
        FontAnalyzer.analyze(woff(99L, compressed))
    }

    private fun woff(originalLength: Long, payload: ByteArray = byteArrayOf(1, 2, 3, 4)): ByteArray {
        val bytes = ByteArray(64 + payload.size)
        put32(bytes, 0, 0x774F4646L)
        put32(bytes, 4, 0x00010000L)
        put32(bytes, 8, bytes.size.toLong())
        put16(bytes, 12, 1)
        put16(bytes, 14, 0)
        put32(bytes, 16, 32L * 1024L * 1024L)
        System.arraycopy("head".toByteArray(Charsets.US_ASCII), 0, bytes, 44, 4)
        put32(bytes, 48, 64L)
        put32(bytes, 52, payload.size.toLong())
        put32(bytes, 56, originalLength)
        System.arraycopy(payload, 0, bytes, 64, payload.size)
        return bytes
    }

    private fun put16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 8).toByte()
        bytes[offset + 1] = value.toByte()
    }

    private fun put32(bytes: ByteArray, offset: Int, value: Long) {
        bytes[offset] = (value ushr 24).toByte()
        bytes[offset + 1] = (value ushr 16).toByte()
        bytes[offset + 2] = (value ushr 8).toByte()
        bytes[offset + 3] = value.toByte()
    }
}
