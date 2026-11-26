package com.omega.arabify

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Gettext tier (Pixel Wheels layout): games that ship gettext .po files plus a
 * languages.xml picker. Adds an ar.po translated from messages.pot, registers
 * an Arabic <Language> and FontSet in languages.xml, and merges Arabic glyphs
 * into COPIES of the referenced fonts (originals stay byte-identical) because
 * libGDX has no Arabic shaper or glyph fallback. msgstr values are pre-shaped
 * to visual-order Presentation Forms-B by ArabicShaper for the same reason.
 *
 * Honest limits: plural entries emit all 6 Arabic plural forms, but the
 * plural translations reuse the singular/plural machine translations (no real
 * Arabic plural inflection); OTF/CFF fonts (Kwajong-Italic) cannot take a
 * glyph merge, so the Arabic FontSet falls back to a mergeable TTF and the
 * selection reason says so.
 */
internal object GettextPoLocalizer {

    private const val MAX_ARCHIVE_ENTRIES = 100_000
    private const val MAX_POT_BYTES = 4L * 1024L * 1024L
    private const val MAX_TOTAL_REBUILT_BYTES = 4L * 1024L * 1024L * 1024L
    private const val MAX_SINGLE_REBUILT_ENTRY_BYTES = 2L * 1024L * 1024L * 1024L

    private val potPattern = Regex("(^|/)po/messages\\.pot$")
    private val languagesXmlPattern = Regex("(^|/)ui/languages\\.xml$")

    data class PoReport(
        val addedEntry: String,
        val stringsTranslated: Int,
        val stringsSkipped: Int,
        val outputApk: File,
        val fontsMerged: List<String> = emptyList(),
        val fontReason: String? = null,
        val skipReasons: Map<String, Int> = emptyMap(),
        val stringsRepaired: Int = 0,
        val repairReasons: Map<String, Int> = emptyMap(),
    )

    internal class FontInjection(val fontBytes: (SelectableFont) -> ByteArray)

    private data class PotEntry(
        val comments: List<String>,
        val msgctxt: String?,
        val msgid: String,
        val msgidPlural: String?,
    )

    /** Arabic CLDR plural rule, written into the ar.po header. */
    private const val ARABIC_PLURALS =
        "nplurals=6; plural=(n==0 ? 0 : n==1 ? 1 : n==2 ? 2 : n%100>=3 && n%100<=10 ? 3 : n%100>=11 && n%100<=99 ? 4 : 5);"

    suspend fun addArabicGettextSlot(
        inputApk: File,
        outputApk: File,
        fonts: FontInjection,
        tx: suspend (String) -> String,
    ): PoReport {
        require(inputApk.isFile) { "ملف APK الأصلي غير موجود." }
        require(inputApk.absoluteFile != outputApk.absoluteFile) { "لا يمكن الكتابة فوق ملف APK الأصلي." }

        ZipFile(inputApk).use { zip ->
            val names = zip.entries().asSequence().filter { !it.isDirectory }.map { it.name }.toList()
            val potPath = names.firstOrNull { potPattern.containsMatchIn(it) }
                ?: throw IllegalArgumentException("لم يتم العثور على قالب gettext (po/messages.pot) داخل اللعبة.")
            val languagesPath = names.firstOrNull { languagesXmlPattern.containsMatchIn(it) }
                ?: throw IllegalArgumentException("لم يتم العثور على languages.xml داخل اللعبة.")
            val arPoPath = potPath.removeSuffix("messages.pot") + "ar.po"
            require(zip.getEntry(arPoPath) == null) { "اللعبة تحتوي بالفعل على ملف ترجمة عربية ($arPoPath)." }

            val pot = readEntry(zip, potPath, MAX_POT_BYTES).toString(Charsets.UTF_8)
            val entries = parsePot(pot)
            require(entries.isNotEmpty()) { "قالب gettext فارغ من النصوص." }

            // Translate everything first (plural singular + plural forms).
            var translated = 0
            var skipped = 0
            var repaired = 0
            val skipReasons = mutableMapOf<String, Int>()
            val repairReasons = mutableMapOf<String, Int>()
            fun bump(map: MutableMap<String, Int>, reason: String?) {
                if (reason != null) map[reason] = (map[reason] ?: 0) + 1
            }
            val arEntries = entries.map { entry ->
                val singularResult = translateValue(entry.msgid, tx)
                val singular = singularResult.text.takeIf { singularResult.translated && it.isNotBlank() }
                if (singular != null) translated++ else { skipped++; bump(skipReasons, singularResult.reason) }
                if (singularResult.repair != null) { repaired++; bump(repairReasons, singularResult.repair) }
                val plural = entry.msgidPlural?.let { pluralId ->
                    val pluralResult = translateValue(pluralId, tx)
                    if (pluralResult.repair != null) { repaired++; bump(repairReasons, pluralResult.repair) }
                    pluralResult.text.takeIf { pluralResult.translated && it.isNotBlank() }
                }
                entry to (singular to plural)
            }

            // Fonts: clone the default FontSet, merge Arabic into copies of its TTF fonts.
            val languagesXml = readEntry(zip, languagesPath, MAX_POT_BYTES).toString(Charsets.UTF_8)
            val fontPlan = planFonts(zip, languagesXml, fonts)
            val shaped = fontPlan.fontsMerged.isNotEmpty()
            val arPo = buildArPo(arEntries, shaped)
            val newLanguagesXml = patchLanguagesXml(languagesXml, fontPlan)

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
                    if (entry.name == languagesPath) {
                        val bytes = newLanguagesXml.toByteArray(Charsets.UTF_8)
                        out.putNextEntry(ApkZipEntryPolicy.create(entry, bytes, countingOutput.bytesWritten))
                        out.write(bytes)
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
                for ((name, bytes) in fontPlan.newFontEntries) {
                    require(bytes.size.toLong() <= MAX_SINGLE_REBUILT_ENTRY_BYTES) { "الخط المدمج تجاوز حد الحجم الآمن." }
                    out.putNextEntry(ZipEntry(name))
                    out.write(bytes)
                    out.closeEntry()
                }
                val arBytes = arPo.toByteArray(Charsets.UTF_8)
                require(arBytes.size.toLong() <= MAX_SINGLE_REBUILT_ENTRY_BYTES) { "ملف ar.po تجاوز حد الحجم الآمن." }
                out.putNextEntry(ZipEntry(arPoPath))
                out.write(arBytes)
                out.closeEntry()
            }
            return PoReport(
                arPoPath, translated, skipped, outputApk, fontPlan.fontsMerged, fontPlan.reason,
                skipReasons = skipReasons.toMap(), stringsRepaired = repaired, repairReasons = repairReasons.toMap(),
            )
        }
    }

    // ---- fonts ------------------------------------------------------------

    private class FontPlan(
        val fontsMerged: List<String>,      // new asset paths written into the APK
        val newFontEntries: Map<String, ByteArray>,
        val fontSetId: String?,             // null when nothing could be merged
        val fontSetXml: String?,
        val reason: String?,
    )

    /**
     * Clones the FIRST FontSet in languages.xml (the default look) for Arabic:
     * every TTF font it names gets a merged "<name>-Ar.ttf" copy; unmergeable
     * fonts (OTF/CFF) fall back to the merged default and the reason says so.
     */
    private fun planFonts(zip: ZipFile, languagesXml: String, fonts: FontInjection): FontPlan {
        val setMatch = Regex("<FontSet\\s+id=\"([^\"]+)\"([^>]*)>").find(languagesXml)
            ?: return FontPlan(emptyList(), emptyMap(), null, null, "لم يتم العثور على FontSet في languages.xml؛ النصوص بدون خط عربي.")
        val body = setMatch.groupValues[2]
        val fontDir = "assets/fonts/"
        val merged = LinkedHashMap<String, ByteArray>()
        val reasons = ArrayList<String>()

        // One catalog font serves every merge; select once against the default font's profile.
        var choice: FontChoice? = null
        fun mergedCopy(assetName: String): String? {
            val mergedPath = fontDir + assetName.removeSuffix(".ttf") + "-Ar.ttf"
            if (merged.containsKey(mergedPath)) return mergedPath
            val entry = zip.getEntry(fontDir + assetName) ?: return null
            val bytes = zip.getInputStream(entry).use { BoundedArchiveInput.readAll(it, 16L * 1024L * 1024L) }
            val profile = try {
                FontAnalyzer.analyze(bytes)
            } catch (error: FontAnalyzer.FontParseException) {
                reasons.add("تعذر فحص $assetName (${error.message ?: "صيغة غير مدعومة"})")
                return null
            }
            if (profile.hasArabicCore) return fontDir + assetName // already Arabic-capable
            val pick = choice ?: try {
                FontSelector.selectForMerge(profile) { f -> FontAnalyzer.analyze(fonts.fontBytes(f)) }.also { choice = it }
            } catch (error: Exception) {
                reasons.add("لا يوجد خط عربي مناسب (${error.message ?: "سبب غير معروف"})")
                return null
            }
            return try {
                val result = FontMerger.mergeArabicInto(bytes, fonts.fontBytes(pick.font))
                val mergedName = assetName.removeSuffix(".ttf") + "-Ar.ttf"
                merged[fontDir + mergedName] = result.fontBytes
                reasons.add("تم دمج ${result.codepointsAdded} رمزاً عربياً من ${pick.font.family} في نسخة من $assetName (${pick.font.licenseId}).")
                fontDir + mergedName
            } catch (error: FontMerger.FontMergeException) {
                reasons.add("تعذر دمج العربية في $assetName (${error.message ?: "صيغة غير مدعومة"})")
                null
            }
        }

        fun attr(name: String): String? = Regex("$name=\"([^\"]+)\"").find(body)?.groupValues?.get(1)

        val defaultFont = attr("defaultFontName")
        val boldFont = attr("defaultBoldFontName") ?: defaultFont
        val titleFont = attr("titleFontName") ?: boldFont
        val hudFont = attr("hudFontName") ?: defaultFont
        if (defaultFont == null) {
            return FontPlan(emptyList(), emptyMap(), null, null, "FontSet بلا defaultFontName؛ النصوص بدون خط عربي.")
        }

        val newDefault = defaultFont.takeIf { it.endsWith(".ttf") }?.let { mergedCopy(it) }
        if (newDefault == null) {
            return FontPlan(emptyList(), emptyMap(), null, null,
                "تعذر توفير خط عربي للعبة ($defaultFont غير قابل للدمج). " + reasons.joinToString(" "))
        }
        val newBold = boldFont?.takeIf { it.endsWith(".ttf") }?.let { mergedCopy(it) } ?: newDefault
        val newTitle = titleFont?.takeIf { it.endsWith(".ttf") }?.let { mergedCopy(it) } ?: newBold
        if (titleFont != null && !titleFont.endsWith(".ttf")) {
            reasons.add("خط العنوان $titleFont غير قابل للدمج (CFF/OTF)؛ استُخدم خط عريض مدمج بدلاً منه.")
        }
        val newHud = hudFont?.takeIf { it.endsWith(".ttf") }?.let { mergedCopy(it) } ?: newDefault

        val setId = "arabic"
        val fontSetXml = """
    <FontSet id="$setId"
        defaultFontName="${newDefault.removePrefix(fontDir)}"
        defaultBoldFontName="${newBold.removePrefix(fontDir)}"
        defaultFontSize="${attr("defaultFontSize") ?: "24"}"

        titleFontName="${newTitle.removePrefix(fontDir)}"
        titleFontSize="${attr("titleFontSize") ?: "32"}"

        hudFontName="${newHud.removePrefix(fontDir)}"
    />
"""
        return FontPlan(merged.keys.toList(), merged, setId, fontSetXml, reasons.joinToString(" "))
    }

    private fun patchLanguagesXml(xml: String, plan: FontPlan): String {
        val setId = plan.fontSetId ?: return xml
        val languageLine = "    <Language id=\"ar\" name=\"العربية\" fontSet=\"$setId\"/>\n"
        val insert = languageLine + "\n" + (plan.fontSetXml ?: "")
        val closing = xml.lastIndexOf("</Languages>")
        require(closing >= 0) { "languages.xml بلا وسم إغلاق </Languages>." }
        return xml.substring(0, closing) + insert + xml.substring(closing)
    }

    // ---- .po ---------------------------------------------------------------

    private fun parsePot(pot: String): List<PotEntry> {
        val entries = ArrayList<PotEntry>()
        var comments = ArrayList<String>()
        var msgctxt: String? = null
        var msgid: String? = null
        var msgidPlural: String? = null
        var field = ""

        fun flush() {
            val id = msgid
            if (id != null && id.isNotEmpty()) entries += PotEntry(comments, msgctxt, id, msgidPlural)
            comments = ArrayList(); msgctxt = null; msgid = null; msgidPlural = null; field = ""
        }

        for (line in pot.split("\n")) {
            when {
                line.startsWith("#") -> if (msgid == null) comments += line
                line.startsWith("msgctxt ") -> { flush(); msgctxt = unquote(line.removePrefix("msgctxt ")); field = "ctx" }
                line.startsWith("msgid_plural ") -> { msgidPlural = unquote(line.removePrefix("msgid_plural ")); field = "idp" }
                line.startsWith("msgid ") -> { msgid = unquote(line.removePrefix("msgid ")); field = "id" }
                line.startsWith("msgstr") -> field = "str"
                line.startsWith("\"") -> {
                    val text = unquote(line)
                    when (field) {
                        "ctx" -> msgctxt = (msgctxt ?: "") + text
                        "id" -> msgid = (msgid ?: "") + text
                        "idp" -> msgidPlural = (msgidPlural ?: "") + text
                    }
                }
                line.isBlank() -> flush()
            }
        }
        flush()
        return entries
    }

    private suspend fun translateValue(text: String, tx: suspend (String) -> String): AssetTextTransformer.SingleResult {
        if (text.isBlank()) return AssetTextTransformer.SingleResult(text, false, "not_translatable")
        // Keep gettext on the same reviewed path as loose assets: exact glossary,
        // placeholder/tag protection, and the conservative Arabic quality gate.
        return AssetTextTransformer.transformValue(text, tx)
    }

    private fun buildArPo(
        entries: List<Pair<PotEntry, Pair<String?, String?>>>,
        shaped: Boolean,
    ): String {
        fun shape(text: String): String = if (shaped) ArabicShaper.toVisual(text) else text
        val out = StringBuilder()
        out.append("msgid \"\"\nmsgstr \"\"\n")
        out.append("\"Content-Type: text/plain; charset=UTF-8\\n\"\n")
        out.append("\"Language: ar\\n\"\n")
        out.append("\"Plural-Forms: ").append(ARABIC_PLURALS.replace("\"", "\\\"")).append("\\n\"\n")
        for ((entry, texts) in entries) {
            out.append("\n")
            for (comment in entry.comments) out.append(comment).append("\n")
            if (entry.msgctxt != null) out.append("msgctxt ").append(quote(entry.msgctxt)).append("\n")
            out.append("msgid ").append(quote(entry.msgid)).append("\n")
            val (singular, plural) = texts
            if (entry.msgidPlural != null) {
                out.append("msgid_plural ").append(quote(entry.msgidPlural)).append("\n")
                val one = shape(singular ?: entry.msgid)
                val many = shape(plural ?: singular ?: entry.msgidPlural)
                // CLDR category order via ArabicPluralRules; without noun
                // inflection only ONE takes the singular - ZERO/TWO would need
                // "لا + اسم" / dual morphology, a documented limit.
                for (category in ArabicPluralRules.Category.entries) {
                    val i = ArabicPluralRules.gettextIndex(category)
                    out.append("msgstr[").append(i).append("] ").append(quote(ArabicPluralRules.pickText(category, one, many))).append("\n")
                }
            } else {
                out.append("msgstr ").append(quote(shape(singular ?: entry.msgid))).append("\n")
            }
        }
        return out.toString()
    }

    private fun quote(text: String): String = "\"" + text
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n") + "\""

    private fun unquote(token: String): String {
        val trimmed = token.trim()
        if (!trimmed.startsWith("\"") || !trimmed.endsWith("\"")) return trimmed
        val body = trimmed.substring(1, trimmed.length - 1)
        val out = StringBuilder()
        var i = 0
        while (i < body.length) {
            val c = body[i]
            if (c == '\\' && i + 1 < body.length) {
                when (body[i + 1]) {
                    'n' -> { out.append('\n'); i += 2 }
                    't' -> { out.append('\t'); i += 2 }
                    '\\' -> { out.append('\\'); i += 2 }
                    '"' -> { out.append('"'); i += 2 }
                    else -> { out.append(c); i += 1 }
                }
            } else {
                out.append(c); i += 1
            }
        }
        return out.toString()
    }

    private fun readEntry(zip: ZipFile, name: String, budget: Long): ByteArray {
        val entry = zip.getEntry(name) ?: throw IllegalArgumentException("عنصر مفقود داخل APK: $name")
        return zip.getInputStream(entry).use { BoundedArchiveInput.readAll(it, budget) }
    }
}
