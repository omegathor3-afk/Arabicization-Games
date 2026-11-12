package com.omega.arabify

import com.reandroid.apk.ApkModule
import com.reandroid.arsc.chunk.PackageBlock
import com.reandroid.arsc.chunk.TableBlock
import com.reandroid.arsc.value.ResConfig
import java.io.File
import java.util.Locale

internal data class AndroidLocalizationReport(
 val packageName: String,
 val discoveredStrings: Int,
 val translatedStrings: Int,
 val existingArabicStrings: Int,
 val skippedStrings: Int,
 val signedApk: File
)

internal object AndroidResourceLocalizer {
 suspend fun localizeEnglishStrings(
  inputApk: File,
  outputApk: File,
  unsignedApk: File,
  translator: OfflineArabicTranslator,
  context: android.content.Context,
  onProgress: (Int, Int) -> Unit = { _, _ -> }
 ): AndroidLocalizationReport {
  require(inputApk.isFile) { "ملف اللعبة الأصلي غير موجود." }
  val module = ApkModule.loadApkFile(inputApk)
  try {
   module.setLoadDefaultFramework(false)
   val manifest = module.getAndroidManifest() ?: error("تعذر قراءة AndroidManifest.xml.")
   val packageName = manifest.getPackageName() ?: error("معرّف الحزمة غير موجود.")
   val table = module.getTableBlock() ?: error("لا يحتوي APK على جدول موارد Android.")
   val packageBlock = findPackage(table, packageName) ?: error("تعذر مطابقة موارد الحزمة مع $packageName.")
   val stringType = packageBlock.getSpecTypePair("string") ?: error("لم يتم العثور على موارد string داخل التطبيق.")
   val sources = mutableListOf<Pair<String, String>>()
   val typeBlocks = stringType.getTypeBlocks()
   while (typeBlocks.hasNext()) {
    val typeBlock = typeBlocks.next()
    if (!typeBlock.getResConfig().getQualifiers().isNullOrEmpty()) continue
    for (entry in typeBlock.listEntries(true)) {
     val name = entry.getName() ?: continue
     val value = entry.getValueAsString() ?: continue
     if (value.isNotBlank() && sources.none { it.first == name }) sources += name to value
    }
   }
   require(sources.isNotEmpty()) { "لم يتم العثور على نصوص string افتراضية قابلة للترجمة." }

   var translated = 0
   var existingArabic = 0
   var skipped = 0
   val arabicConfig = ResConfig.parse("-ar")
   sources.forEachIndexed { index, (name, source) ->
    val existing = packageBlock.getEntry(arabicConfig, "string", name)
    if (existing != null && !existing.isNull()) {
     existingArabic++
     onProgress(index + 1, sources.size)
     return@forEachIndexed
    }
    if (source.any { it in '\u0600'..'\u06FF' }) {
     skipped++
     onProgress(index + 1, sources.size)
     return@forEachIndexed
    }
    // Exact UI labels use a small reviewed glossary; longer phrases still
    // go through the offline translator and its placeholder/quality checks.
    val protected = PlaceholderProtector.protect(source)
    try {
     val translatedText = ArabicUiGlossary.translateExact(source)
      ?: translator.translateEnglishToArabic(protected.text)
     val tokenWasLost = protected.tokens.indices.any { tokenIndex ->
      !translatedText.contains("__ARABIFY_TOKEN_${tokenIndex}__")
     }
     if (tokenWasLost) {
      skipped++
     } else {
      val restored = PlaceholderProtector.restore(translatedText, protected.tokens)
      packageBlock.getOrCreate(arabicConfig, "string", name).setValueAsString(restored)
      translated++
     }
    } catch (e: Exception) {
     skipped++
    }
    onProgress(index + 1, sources.size)
   }
   require(translated > 0) { "لم تُترجم أي قيمة. راجع اللغة المصدر ونموذج الترجمة." }

   module.refreshTable()
   module.setApkSignatureBlock(null)
   val oldSignatureFiles = module.getInputSources().map { it.getAlias() }.filter { path ->
    path.startsWith("META-INF/", ignoreCase = true) &&
     listOf(".RSA", ".DSA", ".EC", ".SF", ".MF").any { path.endsWith(it, ignoreCase = true) }
   }
   oldSignatureFiles.forEach { module.removeInputSource(it) }
   unsignedApk.parentFile?.mkdirs()
   module.writeApk(unsignedApk)
   ApkSigningService.signAndVerify(context, unsignedApk, outputApk)

   return AndroidLocalizationReport(packageName, sources.size, translated, existingArabic, skipped, outputApk)
  } finally {
   module.close()
  }
 }

 private fun findPackage(table: TableBlock, packageName: String): PackageBlock? {
  for (packageBlock in table) {
   if (packageBlock.getName() == packageName) return packageBlock
  }
  return null
 }
}


/**
 * High-confidence exact UI labels that should not depend on model wording.
 * This is deliberately exact-match only: sentences and game-specific prose
 * continue through the offline translator instead of receiving a bad substring
 * replacement.
 */
internal object ArabicUiGlossary {
    private val exactTranslations = mapOf(
        "create" to "إنشاء",
        "new game" to "لعبة جديدة",
        "settings" to "الإعدادات",
        "easy" to "سهل",
        "medium" to "متوسط",
        "hard" to "صعب",
        "beginner" to "مبتدئ",
        "intermediate" to "متوسط",
        "expert" to "خبير",
        "more info" to "مزيد من المعلومات",
        "more information" to "مزيد من المعلومات",
        "support minesweeper" to "دعم كاسحة الألغام",
        "minesweeper" to "كاسحة الألغام",
        "add mode" to "إضافة وضع",
        "edit mode" to "تعديل الوضع",
        "create anyway" to "إنشاء على أي حال",
        "use existing" to "استخدام الموجود",
        "no guess mode" to "وضع دون تخمين",
        "no flag mode" to "وضع دون أعلام",
        "continue" to "متابعة",
        "back" to "رجوع",
        "save" to "حفظ",
        "add" to "إضافة",
        "edit" to "تعديل",
        "delete" to "حذف",
        "cancel" to "إلغاء",
        "close" to "إغلاق",
        "open" to "فتح",
        "apply" to "تطبيق",
        "mode" to "الوضع",
        "name" to "الاسم",
        "width" to "العرض",
        "height" to "الارتفاع",
        "mines" to "الألغام"
    )

    fun translateExact(source: String): String? =
        exactTranslations[source.trim().lowercase(Locale.ROOT)]
}
