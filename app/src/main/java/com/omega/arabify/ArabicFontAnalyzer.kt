package com.omega.arabify

import java.nio.ByteBuffer
import java.nio.ByteOrder

internal data class ArabicFontAssessment(
    val path: String,
    val format: String,
    val mappedArabicLetters: Int,
    val totalArabicLetters: Int,
    val coveragePercent: Int,
    val suitableForArabic: Boolean,
    val fileSize: Int
)

internal object ArabicFontAnalyzer {
    private val arabicLetters: Set<Int> = buildSet {
        addAll(0x0621..0x063A)
        addAll(0x0641..0x064A)
        addAll(listOf(0x0671, 0x067E, 0x0686, 0x0698, 0x06A4, 0x06AF, 0x06BA, 0x06BE, 0x06C1, 0x06CC, 0x06D2))
        addAll(0x0750..0x077F)
        addAll(0x08A0..0x08C9)
    }

    fun isFontPath(path: String): Boolean =
        path.substringAfterLast('.', "").lowercase() in setOf("ttf", "otf")

    fun inspect(path: String, bytes: ByteArray): ArabicFontAssessment {
        val mapped = runCatching { mappedCodePoints(bytes) }.getOrDefault(emptySet())
        val count = arabicLetters.count { it in mapped }
        val percent = if (arabicLetters.isEmpty()) 0 else count * 100 / arabicLetters.size
        val ext = path.substringAfterLast('.', "font").uppercase()
        return ArabicFontAssessment(
            path = path,
            format = ext,
            mappedArabicLetters = count,
            totalArabicLetters = arabicLetters.size,
            coveragePercent = percent,
            suitableForArabic = percent >= 75,
            fileSize = bytes.size
        )
    }

    fun rank(fonts: List<ArabicFontAssessment>): List<ArabicFontAssessment> =
        fonts.sortedWith(compareByDescending<ArabicFontAssessment> { it.suitableForArabic }
            .thenByDescending { it.coveragePercent }
            .thenBy { it.fileSize })

    private fun mappedCodePoints(bytes: ByteArray): Set<Int> {
        require(bytes.size >= 12) { "Invalid font header" }
        val data = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val tableCount = u16(data, 4)
        require(tableCount in 1..256 && 12L + tableCount * 16L <= bytes.size) { "Invalid sfnt table directory" }
        var cmapOffset = -1
        var cmapLength = 0
        for (i in 0 until tableCount) {
            val record = 12 + i * 16
            val tag = String(bytes, record, 4, Charsets.US_ASCII)
            if (tag == "cmap") {
                cmapOffset = data.getInt(record + 8)
                cmapLength = data.getInt(record + 12)
                break
            }
        }
        require(cmapOffset >= 0 && cmapLength >= 4 && cmapOffset.toLong() + cmapLength <= bytes.size) { "No cmap table" }
        val cmap = cmapOffset
        val encCount = u16(data, cmap + 2)
        val result = mutableSetOf<Int>()
        for (i in 0 until encCount) {
            val rec = cmap + 4 + i * 8
            if (rec + 8 > cmap + cmapLength) break
            val subOffset = data.getInt(rec + 4)
            val sub = cmap + subOffset
            if (sub < cmap || sub + 2 > cmap + cmapLength) continue
            when (u16(data, sub)) {
                4 -> parseFormat4(data, sub, cmap + cmapLength, result)
                12 -> parseFormat12(data, sub, cmap + cmapLength, result)
            }
        }
        return result
    }

    private fun parseFormat12(data: ByteBuffer, base: Int, limit: Int, output: MutableSet<Int>) {
        require(base + 16 <= limit)
        val groups = data.getInt(base + 12)
        require(groups in 0..100_000 && base.toLong() + 16L + groups * 12L <= limit)
        for (i in 0 until groups) {
            val p = base + 16 + i * 12
            val start = data.getInt(p)
            val end = data.getInt(p + 4)
            val startGlyph = data.getInt(p + 8)
            if (start < 0 || end < start || startGlyph < 0) continue
            for (cp in arabicLetters) if (cp in start..end && startGlyph + (cp - start) > 0) output += cp
        }
    }

    private fun parseFormat4(data: ByteBuffer, base: Int, limit: Int, output: MutableSet<Int>) {
        require(base + 14 <= limit)
        val length = u16(data, base + 2)
        require(length >= 16 && base.toLong() + length <= limit)
        val segCount = u16(data, base + 6) / 2
        require(segCount in 1..8192)
        val endBase = base + 14
        val startBase = endBase + segCount * 2 + 2
        val deltaBase = startBase + segCount * 2
        val rangeBase = deltaBase + segCount * 2
        require(rangeBase + segCount * 2 <= base + length)
        for (i in 0 until segCount) {
            val start = u16(data, startBase + i * 2)
            val end = u16(data, endBase + i * 2)
            val delta = data.getShort(deltaBase + i * 2).toInt()
            val rangeWord = rangeBase + i * 2
            val range = u16(data, rangeWord)
            if (end < start) continue
            for (cp in arabicLetters) {
                if (cp !in start..end || cp > 0xFFFF) continue
                val glyph = if (range == 0) (cp + delta) and 0xFFFF else {
                    val glyphPos = rangeWord + range + (cp - start) * 2
                    if (glyphPos + 2 > base + length) 0 else u16(data, glyphPos).let { if (it == 0) 0 else (it + delta) and 0xFFFF }
                }
                if (glyph != 0) output += cp
            }
        }
    }

    private fun u16(data: ByteBuffer, offset: Int): Int = data.getShort(offset).toInt() and 0xFFFF
}
