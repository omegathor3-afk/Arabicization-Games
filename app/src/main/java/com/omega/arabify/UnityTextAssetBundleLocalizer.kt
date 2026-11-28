package com.omega.arabify

import java.io.ByteArrayOutputStream
import java.io.File

internal data class UnityBundleLocalizationReport(
    val nodePath: String,
    val pathId: Long,
    val format: String,
    val translatedStrings: Int,
    val skippedStrings: Int,
    val patch: UnityTextAssetPatchReport,
    val rewrite: UnityFsRewriteReport,
    val outputBundle: File
)

/**
 * First end-to-end Unity binary localization chain, library level:
 * UnityFS bundle -> extract SerializedFile node -> read TextAsset ->
 * translate its text with placeholder protection and the quality gate ->
 * patch the SerializedFile -> rewrite the bundle -> verify everything again.
 *
 * Every stage revalidates before and after writing, and the source bundle is
 * never modified. Managed DLLs are never touched. IL2CPP remains unsupported.
 * UI wiring (game picker, progress, backup hand-off) is a separate step.
 */
internal object UnityTextAssetBundleLocalizer {
    private val supportedFormats = setOf("json", "csv", "txt", "properties", "strings")

    suspend fun localizeTextAsset(
        bundle: File,
        outputBundle: File,
        nodePath: String,
        pathId: Long,
        workingDir: File,
        translate: suspend (String) -> String,
        limits: UnityFsDecodeLimits = UnityFsDecodeLimits()
    ): UnityBundleLocalizationReport {
        require(bundle.isFile) { "ملف الحزمة الأصلي غير موجود." }
        require(bundle.absoluteFile != outputBundle.absoluteFile) { "لا يمكن الكتابة فوق الحزمة الأصلية." }
        require(workingDir.isDirectory || workingDir.mkdirs()) { "تعذر إنشاء مجلد العمل المؤقت." }

        // 1. Extract the target SerializedFile node from the bundle.
        val stream = ByteArrayOutputStream()
        val decoded = UnityFsBundleDecoder.decode(bundle, stream, limits)
            ?: error("تعذر فك ترميز الحزمة الأصلية.")
        val node = decoded.blockInfo.nodes.singleOrNull { it.path == nodePath }
            ?: error("لم يتم العثور على العقدة المطلوبة داخل الحزمة: $nodePath")
        require(node.size in 1L..Int.MAX_VALUE.toLong()) { "حجم العقدة غير صالح." }
        val streamBytes = stream.toByteArray()
        val serializedBytes = streamBytes.copyOfRange(
            node.offset.toInt(), (node.offset + node.size).toInt()
        )
        val serializedFile = File(workingDir, "node.serialized")
        val patchedFile = File(workingDir, "node.patched.serialized")
        try {
            serializedFile.writeBytes(serializedBytes)

            // 2. Inspect the SerializedFile and read the TextAsset strictly.
            val inspection = serializedFile.inputStream().use {
                UnitySerializedFileInspector.inspect(it, serializedFile.length())
            } ?: error("العقدة المستخرجة ليست SerializedFile صالحًا.")
            val typeTable = inspection.typeTable
                ?: error("جدول أنواع SerializedFile غير مقروء؛ لا ترجمة آمنة بدونه.")
            val objectTable = inspection.objectTable
                ?: error("جدول كائنات SerializedFile غير مقروء؛ لا ترجمة آمنة بدونه.")
            val objectInfo = objectTable.objects.singleOrNull { it.pathId == pathId }
                ?: error("لم يتم العثور على كائن TextAsset بالمعرّف المطلوب.")
            val content = UnityTextAssetReader.read(
                serializedFile, inspection.header, typeTable, objectInfo
            ) ?: error("تعذر قراءة TextAsset بشكل صارم؛ الملف ثنائي أو مخططه غير مدعوم.")

            // 3. Translate the text content with the shared transformer.
            val formatSource = content.pathName?.takeIf { it.isNotBlank() } ?: content.name
            val format = formatSource.substringAfterLast('.', "").lowercase()
            require(format in supportedFormats) {
                "صيغة TextAsset ($format) غير مدعومة للترجمة الآمنة."
            }
            val transformed = AssetTextTransformer.transform(formatSource, content.script, translate)
            require(transformed.translated > 0) { "لم يتم العثور على نصوص قابلة للترجمة داخل TextAsset." }

            // 4. Patch the SerializedFile (verifies itself, deletes output on failure).
            val patch = UnityTextAssetSerializedPatcher.patch(
                serializedFile, patchedFile, inspection.header, typeTable, objectTable,
                pathId, transformed.text
            )

            // 5. Rewrite the bundle (decodes itself again and byte-verifies all nodes).
            val rewrite = UnityFsBundleRewriter.rewriteNode(
                bundle, outputBundle, nodePath, patchedFile.readBytes(), limits
            )

            // 6. Independent end-to-end verification of the final bundle.
            verifyFinalBundle(outputBundle, nodePath, pathId, transformed.text, limits)

            return UnityBundleLocalizationReport(
                nodePath = nodePath,
                pathId = pathId,
                format = format,
                translatedStrings = transformed.translated,
                skippedStrings = transformed.skipped,
                patch = patch,
                rewrite = rewrite,
                outputBundle = outputBundle
            )
        } finally {
            serializedFile.delete()
            patchedFile.delete()
        }
    }

    private fun verifyFinalBundle(
        outputBundle: File,
        nodePath: String,
        pathId: Long,
        expectedText: String,
        limits: UnityFsDecodeLimits
    ) {
        val stream = ByteArrayOutputStream()
        val decoded = UnityFsBundleDecoder.decode(outputBundle, stream, limits)
            ?: error("فشل التحقق النهائي: الحزمة الناتجة لا تُفك.")
        val node = decoded.blockInfo.nodes.singleOrNull { it.path == nodePath }
            ?: error("فشل التحقق النهائي: العقدة غابت من الحزمة الناتجة.")
        val streamBytes = stream.toByteArray()
        val nodeBytes = streamBytes.copyOfRange(
            node.offset.toInt(), (node.offset + node.size).toInt()
        )
        val inspection = nodeBytes.inputStream().use {
            UnitySerializedFileInspector.inspect(it, nodeBytes.size.toLong())
        } ?: error("فشل التحقق النهائي: SerializedFile الناتج غير قابل للفحص.")
        val typeTable = inspection.typeTable ?: error("فشل التحقق النهائي: جدول الأنواع غير مقروء.")
        val objectTable = inspection.objectTable ?: error("فشل التحقق النهائي: جدول الكائنات غير مقروء.")
        val objectInfo = objectTable.objects.singleOrNull { it.pathId == pathId }
            ?: error("فشل التحقق النهائي: الكائن الهدف غاب من الحزمة الناتجة.")

        // The strict reader tolerates no trailing pad; the patcher may add up to
        // 3 alignment zeros. Read the fields manually with that exact allowance.
        val order = if (inspection.header.endian == "little") {
            java.nio.ByteOrder.LITTLE_ENDIAN
        } else {
            java.nio.ByteOrder.BIG_ENDIAN
        }
        val payload = ByteArray(objectInfo.byteSize.toInt())
        System.arraycopy(
            nodeBytes, objectInfo.absoluteDataOffset.toInt(),
            payload, 0, payload.size
        )
        val buffer = java.nio.ByteBuffer.wrap(payload).order(order)
        var script: String? = null
        var name: String? = null
        while (buffer.remaining() > 3) {
            val length = buffer.int
            require(length in 0..buffer.remaining()) { "فشل التحقق النهائي: حقل مبتور." }
            val bytes = ByteArray(length)
            buffer.get(bytes)
            if (name == null) name = String(bytes, Charsets.UTF_8) else if (script == null) {
                script = String(bytes, Charsets.UTF_8)
            }
            val absolute = objectInfo.absoluteDataOffset + buffer.position()
            val pad = ((4L - (absolute % 4L)) % 4L).toInt()
            if (pad <= buffer.remaining()) buffer.position(buffer.position() + pad)
        }
        require(script == expectedText) { "فشل التحقق النهائي: النص داخل الحزمة لا يطابق الترجمة." }
        while (buffer.hasRemaining()) {
            require(buffer.get().toInt() == 0) { "فشل التحقق النهائي: حشوة غير صفرية." }
        }
    }
}
