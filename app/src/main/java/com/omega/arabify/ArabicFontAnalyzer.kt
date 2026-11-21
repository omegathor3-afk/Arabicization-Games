package com.omega.arabify

import java.nio.ByteBuffer
import java.nio.ByteOrder

internal data class ArabicFontAssessment(
    val path: String,
    val format: String,
    val mappedArabicLetters: Int,
    val totalArabicLetters: Int,
    val coveragePercent: Int,
    val hasArabicShapingTables: Boolean,
    val suitableForArabic: Boolean,
    val fileSize: Int,
    val familyName: String? = null,
    val subfamilyName: String? = null,
    val weightClass: Int? = null,
    val italic: Boolean? = null
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
        val shapingTables = runCatching { hasArabicShapingTables(bytes) }.getOrDefault(false)
        val metadata = runCatching { readFontMetadata(bytes) }.getOrDefault(FontMetadata())
        val ext = path.substringAfterLast('.', "font").uppercase()
        return ArabicFontAssessment(
            path = path,
            format = ext,
            mappedArabicLetters = count,
            totalArabicLetters = arabicLetters.size,
            coveragePercent = percent,
            hasArabicShapingTables = shapingTables,
            suitableForArabic = percent >= 75 && shapingTables,
            fileSize = bytes.size,
            familyName = metadata.familyName,
            subfamilyName = metadata.subfamilyName,
            weightClass = metadata.weightClass,
            italic = metadata.italic
        )
    }

    fun rank(fonts: List<ArabicFontAssessment>): List<ArabicFontAssessment> =
        fonts.sortedWith(compareByDescending<ArabicFontAssessment> { it.suitableForArabic }
            .thenByDescending { it.hasArabicShapingTables }
            .thenByDescending { it.coveragePercent }
            .thenBy { it.fileSize })
    
    internal data class FontStyleProfile(
        val familyName: String? = null,
        val weightClass: Int? = null,
        val italic: Boolean? = null
    )

    private data class FontMetadata(
        val familyName: String? = null,
        val subfamilyName: String? = null,
        val weightClass: Int? = null,
        val italic: Boolean? = null
    )

    /** Ranks Arabic-capable fonts against the original game's family/weight/style. */
    fun rankForStyle(fonts: List<ArabicFontAssessment>, target: FontStyleProfile): List<ArabicFontAssessment> =
        fonts.sortedWith(
            compareByDescending<ArabicFontAssessment> { it.suitableForArabic }
                .thenByDescending { it.hasArabicShapingTables }
                .thenByDescending { familyMatch(it.familyName, target.familyName) }
                .thenBy { weightDistance(it.weightClass, target.weightClass) }
                .thenBy { italicMismatch(it.italic, target.italic) }
                .thenByDescending { it.coveragePercent }
                .thenBy { it.fileSize }
        )

    private fun familyMatch(actual: String?, expected: String?): Int {
        if (actual.isNullOrBlank() || expected.isNullOrBlank()) return 0
        return if (actual.trim().equals(expected.trim(), ignoreCase = true)) 1 else 0
    }

    private fun weightDistance(actual: Int?, expected: Int?): Int =
        if (actual == null || expected == null) Int.MAX_VALUE else kotlin.math.abs(actual - expected)

    private fun italicMismatch(actual: Boolean?, expected: Boolean?): Int =
        if (actual == null || expected == null) 1 else if (actual == expected) 0 else 1

    private fun readFontMetadata(bytes: ByteArray): FontMetadata {
        val nameTable = findTable(bytes, "name")?.let { parseNameTable(bytes, it.first, it.second) } ?: emptyMap()
        val os2 = findTable(bytes, "OS/2")
        var weight: Int? = null
        var italic: Boolean? = null
        if (os2 != null && os2.second >= 64) {
            val data = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            val offset = os2.first
            weight = u16(data, offset + 4).takeIf { it in 1..1000 }
            val selection = u16(data, offset + 62)
            italic = (selection and 0x0001) != 0
        }
        return FontMetadata(
            familyName = nameTable[16] ?: nameTable[1],
            subfamilyName = nameTable[17] ?: nameTable[2],
            weightClass = weight,
            italic = italic
        )
    }

    private fun parseNameTable(bytes: ByteArray, offset: Int, length: Int): Map<Int, String> {
        val data = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val limit = offset.toLong() + length
        if (length < 6 || limit > bytes.size) return emptyMap()
        val format = u16(data, offset)
        if (format !in 0..1) return emptyMap()
        val count = u16(data, offset + 2)
        val storageOffset = u16(data, offset + 4)
        if (count > 4096 || offset.toLong() + 6L + count * 12L > limit) return emptyMap()
        val priorities = mutableMapOf<Int, Pair<Int, String>>()
        for (i in 0 until count) {
            val record = offset + 6 + i * 12
            val platform = u16(data, record)
            val encoding = u16(data, record + 2)
            val language = u16(data, record + 4)
            val nameId = u16(data, record + 6)
            if (nameId !in setOf(1, 2, 4, 6, 16, 17)) continue
            val byteLength = u16(data, record + 8)
            val stringOffset = u16(data, record + 10)
            val start = offset.toLong() + storageOffset + stringOffset
            val end = start + byteLength
            if (start < offset || end > limit || end > bytes.size) continue
            val raw = bytes.copyOfRange(start.toInt(), end.toInt())
            val value = when {
                platform == 0 || platform == 3 -> if (raw.size % 2 == 0) String(raw, Charsets.UTF_16BE) else ""
                platform == 1 -> String(raw, Charsets.ISO_8859_1)
                else -> ""
            }.trim().trim('\u0000')
            if (value.isBlank()) continue
            val priority = when {
                platform == 3 && language == 0x0409 -> 4
                platform == 3 -> 3
                platform == 0 -> 2
                platform == 1 -> 1
                else -> 0
            } + if (encoding == 10) 1 else 0
            if (priority > (priorities[nameId]?.first ?: -1)) priorities[nameId] = priority to value
        }
        return priorities.mapValues { it.value.second }
    }

    private fun hasArabicShapingTables(bytes: ByteArray): Boolean {
        val gsub = findTable(bytes, "GSUB") ?: return false
        val gpos = findTable(bytes, "GPOS") ?: return false
        return hasArabicScriptRecord(bytes, gsub.first, gsub.second) &&
            hasArabicScriptRecord(bytes, gpos.first, gpos.second)
    }

    private fun findTable(bytes: ByteArray, wantedTag: String): Pair<Int, Int>? {
        if (bytes.size < 12) return null
        val data = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val tableCount = u16(data, 4)
        if (tableCount !in 1..256 || 12L + tableCount * 16L > bytes.size) return null
        for (i in 0 until tableCount) {
            val record = 12 + i * 16
            val tag = String(bytes, record, 4, Charsets.US_ASCII)
            if (tag != wantedTag) continue
            val offset = data.getInt(record + 8)
            val length = data.getInt(record + 12)
            if (offset < 0 || length < 10 || offset.toLong() + length > bytes.size) return null
            return offset to length
        }
        return null
    }

    /** Checks for an actual `arab` ScriptRecord in an OpenType GSUB/GPOS table. */
    private fun hasArabicScriptRecord(bytes: ByteArray, offset: Int, length: Int): Boolean {
        val data = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val limit = offset + length
        if (offset < 0 || length < 10 || limit > bytes.size || u16(data, offset) != 1) return false
        val scriptListOffset = u16(data, offset + 4)
        if (scriptListOffset == 0) return false
        val scriptList = offset + scriptListOffset
        if (scriptList < offset || scriptList + 2 > limit) return false
        val scriptCount = u16(data, scriptList)
        if (scriptCount !in 1..1024 || scriptList.toLong() + 2L + scriptCount * 6L > limit) return false
        for (i in 0 until scriptCount) {
            val record = scriptList + 2 + i * 6
            if (String(bytes, record, 4, Charsets.US_ASCII) != "arab") continue
            val relativeOffset = u16(data, record + 4)
            if (relativeOffset == 0) continue
            val script = scriptList + relativeOffset
            if (script < scriptList || script + 4 > limit) continue
            val defaultLangSysOffset = u16(data, script)
            val languageSystemCount = u16(data, script + 2)
            if (defaultLangSysOffset > 0 || languageSystemCount > 0) return true
        }
        return false
    }

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
