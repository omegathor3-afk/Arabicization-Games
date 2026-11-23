package com.omega.arabify

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.security.MessageDigest

/**
 * Portable review record for one read-only Unity TextAsset translation.
 *
 * This manifest is deliberately not an executable patch: it records the exact
 * source and reviewed text, their hashes, and the Unity object identity. A
 * future writer must independently re-open the original bundle and revalidate
 * every identity/hash before applying anything.
 */
internal data class UnityTextAssetPatchManifest private constructor(
    val bundlePath: String,
    val serializedFilePath: String,
    val pathId: Long,
    val objectName: String,
    val objectPath: String?,
    val format: String,
    val sourceText: String,
    val reviewedText: String,
    val sourceSha256: String,
    val reviewedSha256: String,
    val translatedStrings: Int,
    val skippedStrings: Int
) {
    fun toJsonString(): String {
        val target = JSONObject()
            .put("bundlePath", bundlePath)
            .put("serializedFilePath", serializedFilePath)
            .put("pathId", pathId)
            .put("classId", TEXT_ASSET_CLASS_ID)
            .put("name", objectName)
            .put("pathName", objectPath)
            .put("format", format)

        val source = JSONObject()
            .put("text", sourceText)
            .put("sha256", sourceSha256)

        val translation = JSONObject()
            .put("language", "ar")
            .put("text", reviewedText)
            .put("sha256", reviewedSha256)
            .put("translatedStrings", translatedStrings)
            .put("skippedStrings", skippedStrings)

        val validation = JSONObject()
            .put("placeholderSequencePreserved", true)
            .put("sourceHashRecorded", true)
            .put("reviewedHashRecorded", true)

        val application = JSONObject()
            .put("mode", "review-only")
            .put("applied", false)
            .put("writerAvailable", false)

        val json = JSONObject()
            .put("schemaVersion", SCHEMA_VERSION)
            .put("tool", "ARABIFY")
            .put("kind", "unity-textasset-review")
            .put("target", target)
            .put("source", source)
            .put("translation", translation)
            .put("validation", validation)
            .put("application", application)
            .toString(2)

        require(json.toByteArray(Charsets.UTF_8).size <= MAX_MANIFEST_BYTES) {
            "ملف المراجعة يتجاوز الحد الآمن للتصدير."
        }
        return json
    }

    companion object {
        const val SCHEMA_VERSION = 1
        const val MAX_SOURCE_BYTES = 1 * 1024 * 1024
        const val MAX_REVIEWED_BYTES = 2 * 1024 * 1024
        const val MAX_MANIFEST_BYTES = 8 * 1024 * 1024

        private const val TEXT_ASSET_CLASS_ID = 49
        private const val MAX_METADATA_LENGTH = 4_096
        private const val MAX_SKIPPED_STRINGS = 20_000
        private val supportedFormats = setOf("json", "txt", "properties", "strings")

        fun create(
            preview: UnityTextAssetPreview,
            result: UnityTextAssetTranslationResult,
            reviewedText: String
        ): UnityTextAssetPatchManifest {
            val sourceText = preview.fullScript
            require(sourceText.isNotBlank()) { "النص الأصلي فارغ؛ لا يمكن إنشاء ملف مراجعة." }
            require(reviewedText.isNotBlank()) { "الترجمة المراجعة فارغة." }
            require(reviewedText != sourceText) { "لا توجد تغييرات لترحيلها إلى ملف المراجعة." }

            val detectedFormat = UnityTextAssetPreviewTranslator.supportedFormat(preview)
                ?: error("صيغة TextAsset غير مدعومة لملف المراجعة.")
            require(detectedFormat == result.format && detectedFormat in supportedFormats) {
                "صيغة TextAsset لا تطابق نتيجة الترجمة."
            }

            UnityTextAssetPreviewInputPolicy.validate(detectedFormat, sourceText)
            UnityTextAssetPreviewInputPolicy.validate(detectedFormat, reviewedText)
            if (detectedFormat == "json") {
                val sourceRoot = parseJsonDocument(sourceText)
                val reviewedRoot = parseJsonDocument(reviewedText)
                validateJsonStructureAndPlaceholders(sourceRoot, reviewedRoot)
            } else {
                val originalTokens = PlaceholderProtector.protect(sourceText).tokens
                val reviewedTokens = PlaceholderProtector.protect(reviewedText).tokens
                require(originalTokens == reviewedTokens) {
                    "تم تغيير ترتيب أو عدد متغيرات اللعبة أو وسوم التنسيق؛ لن نصدر ملفًا غير آمن."
                }
            }

            val sourceBytes = sourceText.toByteArray(Charsets.UTF_8)
            val reviewedBytes = reviewedText.toByteArray(Charsets.UTF_8)
            require(sourceBytes.size <= MAX_SOURCE_BYTES) {
                "النص الأصلي يتجاوز حد ملف المراجعة."
            }
            require(reviewedBytes.size <= MAX_REVIEWED_BYTES) {
                "الترجمة المراجعة تتجاوز حد ملف المراجعة."
            }
            require(isPlainText(sourceText) && isPlainText(reviewedText)) {
                "النص يحتوي محارف تحكم غير مسموحة."
            }

            require(result.translatedStrings in 1..UnityTextAssetPreviewInputPolicy.MAX_TRANSLATION_CANDIDATES) {
                "عدد القيم المترجمة غير صالح لملف المراجعة."
            }
            require(result.skippedStrings in 0..MAX_SKIPPED_STRINGS) {
                "عدد القيم المتروكة غير صالح لملف المراجعة."
            }

            requireSafeArchivePath(preview.bundlePath, "مسار UnityFS")
            requireSafeArchivePath(preview.serializedFilePath, "مسار SerializedFile")
            validateMetadata(preview.name, "اسم TextAsset", allowBlank = true)
            preview.pathName?.let { validateMetadata(it, "مسار TextAsset", allowBlank = true) }

            return UnityTextAssetPatchManifest(
                bundlePath = preview.bundlePath,
                serializedFilePath = preview.serializedFilePath,
                pathId = preview.pathId,
                objectName = preview.name,
                objectPath = preview.pathName,
                format = detectedFormat,
                sourceText = sourceText,
                reviewedText = reviewedText,
                sourceSha256 = sha256(sourceBytes),
                reviewedSha256 = sha256(reviewedBytes),
                translatedStrings = result.translatedStrings,
                skippedStrings = result.skippedStrings
            )
        }

        private fun parseJsonDocument(text: String): Any {
            try {
                val tokenizer = JSONTokener(text)
                val root = tokenizer.nextValue()
                require(root is JSONObject || root is JSONArray) {
                    "ملف JSON يجب أن يبدأ بكائن أو مصفوفة."
                }
                require(tokenizer.nextClean() == '\u0000') {
                    "ملف JSON يحتوي بيانات زائدة بعد القيمة الرئيسية."
                }
                return root
            } catch (error: Exception) {
                throw IllegalArgumentException("بنية JSON المراجعة غير صالحة.", error)
            }
        }

        /**
         * JSON braces and object keys are syntax, not game placeholders.
         * Compare the parsed tree and inspect placeholders only inside string
         * values so a translated value can change without treating the whole
         * object as one brace-delimited token.
         */
        private fun validateJsonStructureAndPlaceholders(source: Any, reviewed: Any) {
            when (source) {
                is JSONObject -> {
                    require(reviewed is JSONObject) { "نوع كائن JSON تغيّر أثناء المراجعة." }
                    val sourceKeys = source.keys().asSequence().toSet()
                    val reviewedKeys = reviewed.keys().asSequence().toSet()
                    require(sourceKeys == reviewedKeys) { "مفاتيح JSON يجب أن تبقى كما هي." }
                    sourceKeys.forEach { key ->
                        validateJsonStructureAndPlaceholders(source.get(key), reviewed.get(key))
                    }
                }
                is JSONArray -> {
                    require(reviewed is JSONArray) { "نوع مصفوفة JSON تغيّر أثناء المراجعة." }
                    require(source.length() == reviewed.length()) {
                        "عدد عناصر مصفوفة JSON تغيّر أثناء المراجعة."
                    }
                    for (index in 0 until source.length()) {
                        validateJsonStructureAndPlaceholders(source.get(index), reviewed.get(index))
                    }
                }
                is String -> {
                    require(reviewed is String) { "نوع قيمة JSON تغيّر أثناء المراجعة." }
                    val sourceTokens = PlaceholderProtector.protect(source).tokens
                    val reviewedTokens = PlaceholderProtector.protect(reviewed).tokens
                    require(sourceTokens == reviewedTokens) {
                        "تم تغيير ترتيب أو عدد متغيرات اللعبة أو وسوم التنسيق داخل JSON."
                    }
                }
                else -> require(source == reviewed) {
                    "القيم غير النصية في JSON يجب أن تبقى دون تغيير."
                }
            }
        }

        private fun requireSafeArchivePath(value: String, label: String) {
            validateMetadata(value, label, allowBlank = false)
            val normalized = value.replace('\\', '/')
            require(!normalized.startsWith("/") && !Regex("^[A-Za-z]:").containsMatchIn(normalized)) {
                label + " يجب أن يكون مسارًا نسبيًا."
            }
            require(normalized.split('/').none { it == ".." }) {
                label + " يحتوي على مسار رجوع غير آمن."
            }
        }

        private fun validateMetadata(value: String, label: String, allowBlank: Boolean) {
            require((allowBlank || value.isNotBlank()) && value.length <= MAX_METADATA_LENGTH) {
                label + " فارغ أو أطول من الحد المسموح."
            }
            require(value.none { it.isISOControl() }) {
                label + " يحتوي محارف تحكم."
            }
        }

        private fun isPlainText(value: String): Boolean = value.none { character ->
            character == '\u0000' ||
                (character.isISOControl() && character !in setOf('\n', '\r', '\t'))
        }

        private fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256")
                .digest(bytes)
                .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
    }
}
