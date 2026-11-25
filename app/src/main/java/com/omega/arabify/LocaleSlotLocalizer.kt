package com.omega.arabify

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Game-native language slot tier: instead of overwriting a game's English
 * text, add a NEW locale file the engine picks up itself, leaving every
 * existing entry byte-identical. Two engine layouts are detected:
 *
 *  - libGDX bundles (assets/bundles/bundle.properties + per-locale
 *    siblings such as bundle_de.properties): writes bundle_ar.properties.
 *  - Translation folders (assets/jsons/translations/English.properties +
 *    one <Language>.properties per language, as in Unciv): writes
 *    العربية.properties (the autonym these registries key on); the game
 *    builds its language list from the folder.
 *
 * The game then lists Arabic in its own language settings or resolves it
 * from the device locale - the preferred "add Arabic to the game's
 * language list" outcome.
 *
 * Refuses honestly when the game matches neither layout or already
 * ships an Arabic slot.
 */
internal object LocaleSlotLocalizer {

    private const val MAX_ARCHIVE_ENTRIES = 100_000
    private const val MAX_BASE_BUNDLE_BYTES = 4L * 1024L * 1024L
    private const val MAX_TOTAL_REBUILT_BYTES = 4L * 1024L * 1024L * 1024L
    private const val MAX_SINGLE_REBUILT_ENTRY_BYTES = 2L * 1024L * 1024L * 1024L

    private val baseBundlePattern = Regex("(^|/)bundle\\.properties$")
    private val translationFolderPattern = Regex("(^|/)translations/English\\.properties$")

    /** Supplies the bytes of a catalog font (app assets on device, files in tests). */
    internal class FontInjection(val fontBytes: (SelectableFont) -> ByteArray)

    data class SlotReport(
        val baseEntry: String,
        val addedEntry: String,
        val stringsTranslated: Int,
        val stringsSkipped: Int,
        val outputApk: File,
        val fontReplaced: String? = null,
        val fontReason: String? = null,
        val skipReasons: Map<String, Int> = emptyMap(),
        val stringsRepaired: Int = 0,
        val repairReasons: Map<String, Int> = emptyMap()
    )

    private val mainFontPattern = Regex("(^|/)fonts/font\\.(woff|ttf|otf)$")

    // Folder-convention games key translation files by their language DISPLAY
    // name (English.properties, French.properties, ...). Arabic's display name
    // in the language registries these games use (Unciv's LocaleCode, CLDR) is
    // the autonym العربية, and the reader looks up
    // "<folder>/<display name>.properties" directly - an English-keyed
    // "Arabic.properties" is never read by such a game.
    fun arabicSlotPathFor(basePath: String): String =
        if (translationFolderPattern.containsMatchIn(basePath)) {
            basePath.removeSuffix("English.properties") + "العربية.properties"
        } else {
            basePath.removeSuffix(".properties") + "_ar.properties"
        }

    // Alternate slot names a game might already ship under (English-keyed
    // registries). Existence of any of them means the game has Arabic already.
    private fun existingArabicSlotPathsFor(basePath: String): List<String> =
        if (translationFolderPattern.containsMatchIn(basePath)) {
            listOf(
                basePath.removeSuffix("English.properties") + "العربية.properties",
                basePath.removeSuffix("English.properties") + "Arabic.properties"
            )
        } else {
            listOf(basePath.removeSuffix(".properties") + "_ar.properties")
        }

    suspend fun addArabicLocaleSlot(
        inputApk: File,
        outputApk: File,
        tx: suspend (String) -> String
    ): SlotReport = build(inputApk, outputApk, null, tx)

    /**
     * Same slot, plus the automatic font tier for libGDX bundles: when the
     * game's main font (fonts/font.*) cannot render Arabic, FontSelector picks
     * a catalog font and it replaces that asset; slot values are pre-shaped to
     * visual-order Presentation Forms-B because libGDX has no shaper. Games
     * whose main font already covers Arabic, or that use the translation-folder
     * layout, are left font-untouched. The report records the choice and reason.
     */
    internal suspend fun addArabicLocaleSlotWithFont(
        inputApk: File,
        outputApk: File,
        fonts: FontInjection,
        tx: suspend (String) -> String
    ): SlotReport = build(inputApk, outputApk, fonts, tx)

    private suspend fun build(
        inputApk: File,
        outputApk: File,
        fonts: FontInjection?,
        txIn: suspend (String) -> String
    ): SlotReport {
        require(inputApk.isFile) { "ملف APK الأصلي غير موجود." }
        require(inputApk.absoluteFile != outputApk.absoluteFile) { "لا يمكن الكتابة فوق ملف APK الأصلي." }

        ZipFile(inputApk).use { zip ->
            val candidates = zip.entries().asSequence()
                .filter { !it.isDirectory && it.size in 1..MAX_BASE_BUNDLE_BYTES }
                .map { it.name }
                .toList()
            val bases = candidates.filter { baseBundlePattern.containsMatchIn(it) }.sorted() +
                candidates.filter { translationFolderPattern.containsMatchIn(it) }.sorted()
            require(bases.isNotEmpty()) {
                "لم يتم العثور على ملفات لغة معروفة داخل اللعبة (لا bundle.properties بصيغة libGDX ولا مجلد ترجمات English.properties)."
            }
            val base = bases.first()
            val slotPath = arabicSlotPathFor(base)
            val shipped = existingArabicSlotPathsFor(base).firstOrNull { zip.getEntry(it) != null }
            require(shipped == null) {
                "اللعبة تحتوي بالفعل على ملف لغة عربية ($shipped)."
            }

            val isGdx = !translationFolderPattern.containsMatchIn(base)
            var fontEntryName: String? = null
            var fontBytes: ByteArray? = null
            var fontReason: String? = null
            var shapeArabicSlot = false
            // Shaping+font injection applies to BOTH conventions. Folder-
            // convention games (Unciv-style) rendered logical Arabic through
            // their own text stack and dropped/mangled glyphs on device
            // (تاريخ rendered as تارايخ, عرب lost its ب) - their renderer
            // does not do full Arabic shaping, so they need the same
            // Presentation Forms-B visual order + catalog font as libGDX.
            if (fonts != null) {
                val fe = candidates.firstOrNull { mainFontPattern.containsMatchIn(it) }
                if (fe != null) {
                    val gameFont = zip.getInputStream(zip.getEntry(fe)).use {
                        BoundedArchiveInput.readAll(it, 16L * 1024L * 1024L)
                    }
                    val profile = try {
                        FontAnalyzer.analyze(gameFont)
                    } catch (error: FontAnalyzer.FontParseException) {
                        fontReason = "تم الحفاظ على الخط الأصلي؛ تعذر فحصه بأمان (${error.message ?: "صيغة غير مدعومة"})."
                        null
                    }
                    if (profile != null && !profile.hasArabicCore) {
                        if (profile.isIconFont) {
                            // Icon-merged font: never replace (icons would break). Instead merge
                            // Arabic glyphs from a catalog font into a copy, keeping every
                            // original glyph byte-identical, and shape the slot values.
                            try {
                                val choice = FontSelector.selectForMerge(profile) { f -> FontAnalyzer.analyze(fonts.fontBytes(f)) }
                                val merged = FontMerger.mergeArabicInto(gameFont, fonts.fontBytes(choice.font))
                                fontEntryName = fe
                                fontBytes = merged.fontBytes
                                fontReason = "تم دمج الحروف العربية من ${choice.font.family} داخل خط الأيقونات '${profile.familyName}' مع الحفاظ على كل أيقوناته (${merged.codepointsAdded} رمزاً عربياً، ${merged.glyphsAdded} شكلاً). " + choice.reason
                                shapeArabicSlot = true
                            } catch (error: Exception) {
                                fontReason = "تم الحفاظ على خط الأيقونات الأصلي '${profile.familyName}'؛ تعذر دمج الحروف العربية فيه (${error.message ?: "سبب غير معروف"}). عرض العربية بهذا الخط غير مدعوم حتى الآن."
                            }
                        } else {
                            try {
                                val choice = FontSelector.select(profile) { f -> FontAnalyzer.analyze(fonts.fontBytes(f)) }
                                val replacement = fonts.fontBytes(choice.font)
                                fontEntryName = fe
                                fontBytes = replacement
                                fontReason = choice.reason
                                shapeArabicSlot = true
                            } catch (error: Exception) {
                                fontReason = "تم الحفاظ على الخط الأصلي؛ لم يتم اختيار بديل عربي آمن (${error.message ?: "سبب غير معروف"})."
                            }
                        }
                    }
                }
            }
            val tx = txIn

            val baseEntry = zip.getEntry(base)!!
            val baseBytes = zip.getInputStream(baseEntry).use {
                BoundedArchiveInput.readAll(it, MAX_BASE_BUNDLE_BYTES)
            }
            require(baseBytes.size.toLong() == baseEntry.size) {
                "حجم حزمة اللغة الفعلي لا يطابق بيانات ZIP: $base"
            }
            val source = baseBytes.toString(Charsets.UTF_8)
            val result = if (translationFolderPattern.containsMatchIn(base)) {
                transformTranslationFolder(source, tx)
            } else {
                AssetTextTransformer.transform(slotPath, source) { value -> tx(value) }
            }
            // Presentation-form shaping is paired with the catalog font that was
            // actually injected. Never shape when preserving the game's own font:
            // it may already shape Arabic natively, or may be an icon font.
            val slotText = if (shapeArabicSlot) shapeProperties(result.text) else result.text
            require(result.translated > 0) { "لم يتم العثور على نصوص قابلة للترجمة في حزمة اللغة." }

            outputApk.parentFile?.mkdirs()
            val countingOutput = CountingOutputStream(outputApk.outputStream().buffered())
            ZipOutputStream(countingOutput).use { out ->
                val all = zip.entries()
                var written = 0
                var copiedArchiveBytes = 0L
                while (all.hasMoreElements()) {
                    val entry = all.nextElement()
                    written++
                    require(written <= MAX_ARCHIVE_ENTRIES) { "عدد عناصر APK يتجاوز حد إعادة البناء الآمن." }
                    if (AndroidAssetLocalizer.isOldSignature(entry.name)) continue
                    if (fontBytes != null && entry.name == fontEntryName) {
                        val ft = ApkZipEntryPolicy.create(entry, fontBytes, countingOutput.bytesWritten)
                        out.putNextEntry(ft)
                        out.write(fontBytes)
                        out.closeEntry()
                        continue
                    }
                    val target = ApkZipEntryPolicy.create(entry, null, countingOutput.bytesWritten)
                    out.putNextEntry(target)
                    if (!entry.isDirectory) {
                        val remaining = MAX_TOTAL_REBUILT_BYTES - copiedArchiveBytes
                        require(remaining >= 0L) { "الحجم المفكوك للأرشيف تجاوز حد إعادة البناء الآمن." }
                        val entryBudget = minOf(MAX_SINGLE_REBUILT_ENTRY_BYTES, remaining)
                        val copied = zip.getInputStream(entry).use { BoundedArchiveInput.copy(it, out, entryBudget) }
                        require(entry.size < 0L || copied == entry.size) {
                            "حجم عنصر ZIP الفعلي لا يطابق بياناته: ${entry.name}"
                        }
                        copiedArchiveBytes += copied
                    }
                    out.closeEntry()
                }
                val slotBytes = slotText.toByteArray(Charsets.UTF_8)
                require(slotBytes.size.toLong() <= MAX_SINGLE_REBUILT_ENTRY_BYTES) {
                    "حزمة اللغة العربية تجاوزت حد الحجم الآمن."
                }
                out.putNextEntry(ZipEntry(slotPath))
                out.write(slotBytes)
                out.closeEntry()
            }
            return SlotReport(base, slotPath, result.translated, result.skipped, outputApk, fontEntryName, fontReason, result.skipReasons, result.repaired, result.repairReasons)
        }
    }

    /**
     * Shapes every value of a .properties text to visual-order presentation
     * forms. Done AFTER translation because glossary hits bypass the translate
     * callback; keys, comments and non-Arabic values are left untouched.
     */
    internal fun shapeProperties(text: String): String = text.split("\n").joinToString("\n") { line ->
        val trimmed = line.trimStart()
        if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("!")) return@joinToString line
        val index = line.indexOfFirst { it == '=' || it == ':' }
        if (index < 0) return@joinToString line
        val value = line.substring(index + 1).trim()
        if (!ArabicShaper.containsArabic(value)) line
        else line.substring(0, index + 1) + " " + ArabicShaper.toVisual(value)
    }

    /**
     * Translation-folder language files (Unciv layout): each line is
     * "English source = translation". The English template leaves most
     * values empty (the key is the display text), so the Arabic file
     * translates the KEY when the value is empty or identical to it.
     * Header directives configure the language's rules and get fixed
     * Arabic values: Arabic uses spaces, has no letter case, keeps the
     * default effect-before-cause order and places conditionals after
     * the body ("+20% قوة <للوحدات>"). Diacritic entries stay empty.
     * Lines whose value is already filled keep their line shape; the
     * separator is always '=' so keys containing ':' stay intact.
     */
    private val folderDirectives = mapOf(
        "\" \"" to "\" \"",
        "StartWithCapitalLetter" to "false",
        "EffectBeforeCause" to "true",
        "ConditionalsPlacement" to "after",
        "ConditionalsOrder" to "",
        "diacritics_support" to "",
        "unicode_block_start_character" to "",
        "unicode_block_end_character" to "",
        "left_joining_diacritics" to "",
        "right_joining_diacritics" to "",
        "left_and_right_joiners" to ""
    )

    private suspend fun transformTranslationFolder(
        source: String,
        tx: suspend (String) -> String
    ): AssetTextTransformer.Result {
        val output = mutableListOf<String>()
        var translated = 0
        var skipped = 0
        var repaired = 0
        val skipReasons = mutableMapOf<String, Int>()
        val repairReasons = mutableMapOf<String, Int>()
        fun countSkip(reason: String) {
            skipReasons[reason] = (skipReasons[reason] ?: 0) + 1
        }
        fun countRepair(reason: String) {
            repairReasons[reason] = (repairReasons[reason] ?: 0) + 1
        }
        for (line in source.split("\n")) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("!")) {
                output += line
                continue
            }
            val separator = line.indexOf('=')
            if (separator < 0) {
                skipped++
                countSkip("malformed_line")
                output += line
                continue
            }
            val key = line.substring(0, separator).trim()
            val directive = folderDirectives[key]
            if (directive != null) {
                output += if (directive.isEmpty()) "$key = " else "$key = $directive"
                continue
            }
            val value = line.substring(separator + 1).trim()
            val sourceText = if (value.isEmpty() || value == key) key else value
            val result = AssetTextTransformer.transformValue(sourceText, tx)
            if (result.translated) {
                translated++
                result.repair?.let { repaired++; countRepair(it) }
                output += "$key = ${result.text}"
            } else {
                skipped++
                countSkip(result.reason ?: "unknown")
                output += if (value.isEmpty()) "$key = " else line
            }
        }
        return AssetTextTransformer.Result(output.joinToString("\n"), translated, skipped, skipReasons, repaired, repairReasons)
    }
}
