package com.omega.arabify

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal object UnityTextAssetPreviewInputPolicy {
    const val MAX_SOURCE_UTF16_CODE_UNITS = 1_048_576
    const val MAX_TEXT_LINES = 10_000
    const val MAX_JSON_DEPTH = 64
    const val MAX_JSON_DELIMITERS = 20_000
    const val MAX_JSON_STRING_TOKENS = 20_000
    const val MAX_TRANSLATION_CANDIDATES = 500

    fun validate(format: String, source: String) {
        require(source.length <= MAX_SOURCE_UTF16_CODE_UNITS) {
            "TextAsset يتجاوز حد حجم النص المسموح للمعاينة."
        }
        if (format == "json") validateJson(source) else validateLineCount(source)
    }

    private fun validateLineCount(source: String) {
        var lines = 1
        for (character in source) {
            if (character == '\n') {
                lines++
                require(lines <= MAX_TEXT_LINES) {
                    "TextAsset يحتوي على أسطر أكثر من الحد الآمن للترجمة."
                }
            }
        }
    }

    private fun validateJson(source: String) {
        var depth = 0
        var delimiters = 0
        var stringTokens = 0
        var inString = false
        var escaped = false

        for (character in source) {
            if (inString) {
                if (escaped) {
                    escaped = false
                } else {
                    when (character) {
                        '\\' -> escaped = true
                        '"' -> inString = false
                    }
                }
                continue
            }

            when (character) {
                '"' -> {
                    inString = true
                    stringTokens++
                    require(stringTokens <= MAX_JSON_STRING_TOKENS) {
                        "JSON يحتوي على عدد كبير جدًا من النصوص."
                    }
                }
                '{', '[' -> {
                    depth++
                    delimiters++
                    require(depth <= MAX_JSON_DEPTH) {
                        "JSON متداخل بعمق يتجاوز الحد الآمن."
                    }
                }
                '}', ']' -> {
                    depth--
                    delimiters++
                    require(depth >= 0) { "بنية JSON غير متوازنة." }
                }
                ',', ':' -> delimiters++
            }
            require(delimiters <= MAX_JSON_DELIMITERS) {
                "JSON يحتوي على عناصر أكثر من الحد الآمن للترجمة."
            }
        }

        require(!inString && depth == 0) { "بنية JSON غير مكتملة." }
    }
}

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
    private val supportedFormats = setOf("json", "csv", "txt", "properties", "strings")

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
        UnityTextAssetPreviewInputPolicy.validate(format, preview.fullScript)
        val transformed = AssetTextTransformer.transform(
            path = "textasset.$format",
            source = preview.fullScript,
            maxTranslationCandidates = UnityTextAssetPreviewInputPolicy.MAX_TRANSLATION_CANDIDATES,
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
    val context = LocalContext.current
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
    var editedText by remember(stateKey) { mutableStateOf("") }
    var exportBusy by remember(stateKey) { mutableStateOf(false) }
    var exportMessage by remember(stateKey) { mutableStateOf<String?>(null) }
    var pendingManifestJson by remember(stateKey) { mutableStateOf<String?>(null) }
    val format = UnityTextAssetPreviewTranslator.supportedFormat(preview)
    val manifestLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { targetUri: Uri? ->
        val manifestJson = pendingManifestJson
        pendingManifestJson = null
        if (targetUri != null && manifestJson != null) {
            exportBusy = true
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(targetUri, "wt")?.use { output ->
                            output.write(manifestJson.toByteArray(Charsets.UTF_8))
                            output.flush()
                        } ?: error("تعذر إنشاء ملف Patch Manifest في المكان المحدد.")
                    }
                    exportMessage = "تم تصدير ملف المراجعة؛ لم يتم تطبيقه على اللعبة."
                } catch (error: Exception) {
                    exportMessage = error.message ?: "تعذر تصدير ملف المراجعة."
                } finally {
                    exportBusy = false
                }
            }
        }
    }
    DisposableEffect(stateKey, translationJob) {
        onDispose { translationJob?.cancel() }
    }

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
                            editedText = result.text
                            exportMessage = null
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
        } else if (format == null) {
            Text("الصيغة غير معروفة؛ لن نترجم محتوى قد يكون ثنائيًا أو كودًا.", color = ArabifyPalette.warningText, fontSize = 10.sp)
        } else if (preview.truncated) {
            Text(
                "المعاينة مختصرة، لكن الترجمة تستخدم النص الكامل ضمن الحدود الآمنة: 500 قيمة كحد أقصى لكل TextAsset.",
                color = ArabifyPalette.warningText,
                fontSize = 10.sp
            )
        }
        translatedResult?.let { result ->
            Text(
                "مراجعة الترجمة — التعديل هنا لا يغيّر ملفات اللعبة",
                color = ArabifyPalette.accent,
                fontWeight = FontWeight.SemiBold,
                fontSize = 11.sp
            )
            OutlinedTextField(
                value = editedText,
                onValueChange = {
                    if (it.length <= UnityTextAssetPreviewInputPolicy.MAX_SOURCE_UTF16_CODE_UNITS) {
                        editedText = it
                        exportMessage = null
                    } else {
                        exportMessage = "النص يتجاوز حد التحرير الآمن البالغ 1 MiB من وحدات UTF-16."
                    }
                },
                label = { Text("النص العربي القابل للمراجعة") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 4,
                maxLines = 12,
                enabled = !busy && !exportBusy && pendingManifestJson == null
            )
            if (result.previewTruncated) {
                Text(
                    "النص الكامل موجود في حقل المراجعة؛ حد 1200 نقطة Unicode يخص المعاينة المختصرة فقط.",
                    color = ArabifyPalette.warningText,
                    fontSize = 10.sp
                )
            }
            TextButton(
                onClick = { clipboard.setText(AnnotatedString(editedText)) },
                enabled = editedText.isNotEmpty()
            ) {
                Text("نسخ الترجمة المراجعة كاملة")
            }
            Button(
                onClick = {
                    try {
                        val manifest = UnityTextAssetPatchManifest.create(
                            preview = preview,
                            result = result,
                            reviewedText = editedText
                        )
                        pendingManifestJson = manifest.toJsonString()
                        val fileStem = preview.name
                            .substringAfterLast('/')
                            .substringBeforeLast('.')
                            .replace(Regex("[^A-Za-z0-9_-]"), "_")
                            .take(48)
                            .ifBlank { "textasset" }
                        manifestLauncher.launch("arabify-$fileStem.patch.json")
                    } catch (error: Exception) {
                        pendingManifestJson = null
                        exportMessage = error.message ?: "تعذر تجهيز ملف المراجعة."
                    }
                },
                enabled = !busy && !exportBusy && pendingManifestJson == null &&
                    editedText.isNotBlank() && editedText != preview.fullScript,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (exportBusy || pendingManifestJson != null) "جارٍ تصدير ملف المراجعة..." else "تصدير Patch Manifest")
            }
            Text(
                "ملف المراجعة يحتوي النص الأصلي والمراجع وSHA-256 ومعرّف الكائن. لا يطبّق التعديل على اللعبة ولا يعيد بناء AssetBundle.",
                color = ArabifyPalette.warningText,
                fontSize = 10.sp
            )
            exportMessage?.let {
                Text(it, color = ArabifyPalette.textSecondary, fontSize = 10.sp)
            }
        }
        message?.let { Text(it, color = ArabifyPalette.textSecondary, fontSize = 10.sp) }
    }
}
