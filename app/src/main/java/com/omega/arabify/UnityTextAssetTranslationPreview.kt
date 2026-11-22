package com.omega.arabify

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal data class UnityTextAssetTranslationResult(
    /** Complete translated source; never silently replaced by its display preview. */
    val text: String,
    val previewText: String,
    val previewTruncated: Boolean,
    val translatedStrings: Int,
    val skippedStrings: Int,
    val format: String
)

/**
 * Translates the complete decoded TextAsset source only when its declared
 * path/name identifies a supported text format. The UI preview may be shorter;
 * the source itself is retained under UnityBundleDeepInspector's byte budgets.
 *
 * This is still a review preview: it never writes a SerializedFile or AssetBundle.
 */
internal object UnityTextAssetPreviewTranslator {
    private const val MAX_TRANSLATED_PREVIEW_CODE_POINTS = 1_200
    private val supportedFormats = setOf("json", "txt", "properties", "strings")

    fun supportedFormat(preview: UnityTextAssetPreview): String? {
        // A declared in-game path is authoritative. Never fall back to a
        // misleading filename when the actual path has an unsupported extension.
        val candidate = preview.pathName?.takeIf { it.isNotBlank() } ?: preview.name
        val extension = candidate.substringAfterLast('.', "").lowercase()
        return extension.takeIf { it in supportedFormats }
    }

    suspend fun translate(
        preview: UnityTextAssetPreview,
        translate: suspend (String) -> String
    ): UnityTextAssetTranslationResult {
        require(preview.fullScript.isNotBlank()) {
            "لا يوجد نص كامل قابل للترجمة في TextAsset."
        }
        val format = supportedFormat(preview)
            ?: error("صيغة TextAsset غير معروفة؛ الترجمة التلقائية متاحة فقط لـ JSON وTXT وProperties وStrings.")
        val transformed = AssetTextTransformer.transform(
            path = "textasset.$format",
            source = preview.fullScript,
            translate = translate
        )
        val codePointCount = transformed.text.codePointCount(0, transformed.text.length)
        val previewEnd = transformed.text.offsetByCodePoints(
            0,
            minOf(codePointCount, MAX_TRANSLATED_PREVIEW_CODE_POINTS)
        )
        return UnityTextAssetTranslationResult(
            text = transformed.text,
            previewText = transformed.text.substring(0, previewEnd),
            previewTruncated = codePointCount > MAX_TRANSLATED_PREVIEW_CODE_POINTS,
            translatedStrings = transformed.translated,
            skippedStrings = transformed.skipped,
            format = format
        )
    }
}

@Composable
internal fun UnityTextAssetTranslationPreview(
    preview: UnityTextAssetPreview,
    translator: OfflineArabicTranslator,
    modelReady: Boolean
) {
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val stateKey = listOf(
        preview.bundlePath,
        preview.serializedFilePath,
        preview.pathId.toString(),
        preview.name,
        preview.pathName.orEmpty(),
        preview.fullScript
    )
    var translatedResult by remember(stateKey) { mutableStateOf<UnityTextAssetTranslationResult?>(null) }
    var message by remember(stateKey) { mutableStateOf<String?>(null) }
    var busy by remember(stateKey) { mutableStateOf(false) }
    var translationJob by remember(stateKey) { mutableStateOf<Job?>(null) }
    val format = UnityTextAssetPreviewTranslator.supportedFormat(preview)

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Button(
            onClick = {
                busy = true
                translatedResult = null
                message = null
                val job = scope.launch(start = CoroutineStart.LAZY) {
                    try {
                        val result = UnityTextAssetPreviewTranslator.translate(preview) { value ->
                            translator.translateEnglishToArabic(value)
                        }
                        if (result.translatedStrings > 0) {
                            translatedResult = result
                            message = "تمت ترجمة ${result.translatedStrings} قيمة؛ تُركت ${result.skippedStrings} قيمة دون تغيير."
                        } else {
                            message = "لم تُقبل أي قيمة للترجمة؛ لم يتغير النص الأصلي."
                        }
                    } catch (cancelled: CancellationException) {
                        message = "تم إلغاء الترجمة؛ النص الأصلي محفوظ دون تغيير."
                        throw cancelled
                    } catch (error: Exception) {
                        message = error.message ?: "تعذرت ترجمة TextAsset."
                    } finally {
                        busy = false
                        translationJob = null
                    }
                }
                translationJob = job
                job.start()
            },
            enabled = modelReady && format != null && !busy,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (busy) "جارٍ ترجمة TextAsset كاملًا..." else "ترجمة TextAsset كاملًا")
        }
        if (busy) {
            TextButton(
                onClick = { translationJob?.cancel() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("إلغاء الترجمة")
            }
        }
        if (!modelReady) {
            Text("جهّز نموذج العربية أولًا لاستخدام الترجمة المحلية.", color = ArabifyPalette.warningText, fontSize = 10.sp)
        } else if (preview.truncated) {
            Text(
                "المعاينة المعروضة مختصرة، لكن الترجمة تستخدم النص الكامل المستخرج ضمن حدود الذاكرة.",
                color = ArabifyPalette.warningText,
                fontSize = 10.sp
            )
        } else if (format == null) {
            Text("الصيغة غير معروفة؛ لن نترجم محتوى قد يكون ثنائيًا أو كودًا.", color = ArabifyPalette.warningText, fontSize = 10.sp)
        }
        translatedResult?.let { result ->
            Text("معاينة الترجمة — لم تُكتب داخل اللعبة", color = ArabifyPalette.accent, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
            Text(result.previewText, color = ArabifyPalette.textBody, fontSize = 11.sp, lineHeight = 17.sp)
            if (result.previewTruncated) {
                Text(
                    "عرض الترجمة مختصر إلى أول 1200 محرف؛ زر النسخ ينقل النص الكامل.",
                    color = ArabifyPalette.warningText,
                    fontSize = 10.sp
                )
            }
            TextButton(
                onClick = { clipboard.setText(AnnotatedString(result.text)) },
            ) {
                Text("نسخ الترجمة كاملة")
            }
        }
        message?.let { Text(it, color = ArabifyPalette.textSecondary, fontSize = 10.sp) }
    }
}
