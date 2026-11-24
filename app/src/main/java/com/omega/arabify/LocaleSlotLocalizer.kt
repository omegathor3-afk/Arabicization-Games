package com.omega.arabify

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Game-native language slot tier: instead of overwriting a game's English
 * text, add a NEW locale file the engine picks up itself. For libGDX-style
 * games (assets/bundles/bundle.properties + per-locale siblings), this
 * writes bundle_ar.properties with the base bundle's values translated,
 * leaving every existing entry byte-identical. The game then lists Arabic
 * in its own language settings or resolves it from the device locale -
 * the preferred "add Arabic to the game's language list" outcome.
 *
 * Refuses honestly when the game has no libGDX-style bundle or already
 * ships an Arabic bundle.
 */
internal object LocaleSlotLocalizer {

    private const val MAX_ARCHIVE_ENTRIES = 100_000
    private const val MAX_BASE_BUNDLE_BYTES = 4L * 1024L * 1024L
    private const val MAX_TOTAL_REBUILT_BYTES = 4L * 1024L * 1024L * 1024L
    private const val MAX_SINGLE_REBUILT_ENTRY_BYTES = 2L * 1024L * 1024L * 1024L

    private val baseBundlePattern = Regex("(^|/)bundle\\.properties$")

    data class SlotReport(
        val baseEntry: String,
        val addedEntry: String,
        val stringsTranslated: Int,
        val stringsSkipped: Int,
        val outputApk: File
    )

    fun arabicSlotPathFor(basePath: String): String =
        basePath.removeSuffix(".properties") + "_ar.properties"

    suspend fun addArabicLocaleSlot(
        inputApk: File,
        outputApk: File,
        tx: suspend (String) -> String
    ): SlotReport {
        require(inputApk.isFile) { "ملف APK الأصلي غير موجود." }
        require(inputApk.absoluteFile != outputApk.absoluteFile) { "لا يمكن الكتابة فوق ملف APK الأصلي." }

        ZipFile(inputApk).use { zip ->
            val bases = zip.entries().asSequence()
                .filter { !it.isDirectory && baseBundlePattern.containsMatchIn(it.name) && it.size in 1..MAX_BASE_BUNDLE_BYTES }
                .map { it.name }
                .sorted()
                .toList()
            require(bases.isNotEmpty()) {
                "لم يتم العثور على حزمة لغة بصيغة libGDX (bundle.properties) داخل اللعبة."
            }
            val base = bases.first()
            val slotPath = arabicSlotPathFor(base)
            require(zip.getEntry(slotPath) == null) {
                "اللعبة تحتوي بالفعل على ملف لغة عربية ($slotPath)."
            }

            val baseEntry = zip.getEntry(base)!!
            val baseBytes = zip.getInputStream(baseEntry).use {
                BoundedArchiveInput.readAll(it, MAX_BASE_BUNDLE_BYTES)
            }
            require(baseBytes.size.toLong() == baseEntry.size) {
                "حجم حزمة اللغة الفعلي لا يطابق بيانات ZIP: $base"
            }
            val source = baseBytes.toString(Charsets.UTF_8)
            val result = AssetTextTransformer.transform(slotPath, source) { value -> tx(value) }
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
                val slotBytes = result.text.toByteArray(Charsets.UTF_8)
                require(slotBytes.size.toLong() <= MAX_SINGLE_REBUILT_ENTRY_BYTES) {
                    "حزمة اللغة العربية تجاوزت حد الحجم الآمن."
                }
                out.putNextEntry(ZipEntry(slotPath))
                out.write(slotBytes)
                out.closeEntry()
            }
            return SlotReport(base, slotPath, result.translated, result.skipped, outputApk)
        }
    }
}
