package com.omega.arabify

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import kotlinx.coroutines.launch

internal data class UnityTextAssetTranslationResult(
    val text: String,
    val translatedStrings: Int,
    val skippedStrings: Int,
    val format: String
)

/**
 * Translates only a complete, explicitly recognized TextAsset text format.
 * This is a review preview: it never writes a SerializedFile or AssetBundle.
 */
internal object UnityTextAssetPreviewTranslator {
    private val supportedFormats = setOf("json", "txt", "properties", "strings")

    fun supportedFormat(preview: UnityTextAssetPreview): String? {
        val candidates = listOfNotNull(preview.pathName, preview.name)
        for (candidate in candidates) {
            val extension = candidate.substringAfterLast('.', "").lowercase()
            if (extension in supportedFormats) return extension
        }
        return null
    }

    suspend fun translate(
        preview: UnityTextAssetPreview,
        translate: suspend (String) -> String
    ): UnityTextAssetTranslationResult {
        require(!preview.truncated) {
            "المعاينة مختصرة؛ أعد الفحص بحد نص كامل قبل الترجمة."
        }
        val format = supportedFormat(preview)
            ?: error("صيغة TextAsset غير معروفة؛ الترجمة التلقائية متاحة فقط لـ JSON وTXT وProperties وStrings.")
        val transformed = AssetTextTransformer.transform(
            path = "textasset.$format",
            source = preview.scriptPreview,
            translate = translate
        )
        return UnityTextAssetTranslationResult(
            text = transformed.text,
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
        preview.scriptPreview
    )
    var translatedText by remember(stateKey) { mutableStateOf<String?>(null) }
    var message by remember(stateKey) { mutableStateOf<String?>(null) }
    var busy by remember(stateKey) { mutableStateOf(false) }
    val format = UnityTextAssetPreviewTranslator.supportedFormat(preview)

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Button(
            onClick = {
                busy = true
                translatedText = null
                message = null
                scope.launch {
                    try {
                        val result = UnityTextAssetPreviewTranslator.translate(preview) { value ->
                            translator.translateEnglishToArabic(value)
                        }
                        if (result.translatedStrings > 0) {
                            translatedText = result.text
                            message = "تمت ترجمة ${result.translatedStrings} قيمة؛ تُركت ${result.skippedStrings} قيمة دون تغيير."
                        } else {
                            message = "لم تُقبل أي قيمة للترجمة؛ لم يتغير النص الأصلي."
                        }
                    } catch (error: Exception) {
                        message = error.message ?: "تعذرت ترجمة معاينة TextAsset."
                    } finally {
                        busy = false
                    }
                }
            },
            enabled = modelReady && !preview.truncated && format != null && !busy,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (busy) "جارٍ ترجمة المعاينة..." else "ترجمة معاينة TextAsset")
        }
        if (!modelReady) {
            Text("جهّز نموذج العربية أولًا لاستخدام الترجمة المحلية.", color = ArabifyPalette.warningText, fontSize = 10.sp)
        } else if (preview.truncated) {
            Text("لن نترجم معاينة مختصرة حتى لا ننتج ملفًا ناقصًا.", color = ArabifyPalette.warningText, fontSize = 10.sp)
        } else if (format == null) {
            Text("الصيغة غير معروفة؛ لن نترجم محتوى قد يكون ثنائيًا أو كودًا.", color = ArabifyPalette.warningText, fontSize = 10.sp)
        }
        translatedText?.let { value ->
            Text("معاينة الترجمة — لم تُكتب داخل اللعبة", color = ArabifyPalette.accent, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
            Text(value, color = ArabifyPalette.textBody, fontSize = 11.sp, lineHeight = 17.sp)
            TextButton(
                onClick = { clipboard.setText(AnnotatedString(value)) },
                modifier = Modifier.padding(start = 0.dp)
            ) {
                Text("نسخ الترجمة")
            }
        }
        message?.let { Text(it, color = ArabifyPalette.textSecondary, fontSize = 10.sp) }
    }
}
