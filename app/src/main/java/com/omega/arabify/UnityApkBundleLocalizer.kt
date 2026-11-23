package com.omega.arabify

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

internal data class UnityApkBundleReport(
    val bundlesProcessed: Int,
    val textAssetsTranslated: Int,
    val stringsTranslated: Int,
    val stringsSkipped: Int,
    val outputApk: File
)

/**
 * APK-level integration for the Unity binary chain: finds UnityFS bundles
 * inside an APK, localizes every translatable TextAsset in their
 * SerializedFiles through UnityTextAssetBundleLocalizer's strict stages, and
 * rebuilds the APK with ApkZipEntryPolicy so entry methods and alignment are
 * preserved. Old signatures are dropped; signing happens later in the UI
 * layer. The source APK is never modified, and any bundle that fails a strict
 * gate aborts the whole operation rather than producing a broken APK.
 */
internal object UnityApkBundleLocalizer {
    private const val MAX_BUNDLES = 64
    private const val MAX_BUNDLE_BYTES = 512L * 1024L * 1024L
    private const val MAX_TOTAL_REBUILT_BYTES = 2L * 1024L * 1024L * 1024L
    private const val MAX_SINGLE_REBUILT_ENTRY_BYTES = 512L * 1024L * 1024L
    private const val MAX_ARCHIVE_ENTRIES = 20_000
    private val supportedFormats = setOf("json", "csv", "txt", "properties", "strings")

    suspend fun localizeBundlesInApk(
        inputApk: File,
        outputApk: File,
        workingDir: File,
        translate: suspend (String) -> String,
        limits: UnityFsDecodeLimits = UnityFsDecodeLimits()
    ): UnityApkBundleReport {
        require(inputApk.isFile) { "ملف APK الأصلي غير موجود." }
        require(inputApk.absoluteFile != outputApk.absoluteFile) { "لا يمكن الكتابة فوق ملف APK الأصلي." }
        require(workingDir.isDirectory || workingDir.mkdirs()) { "تعذر إنشاء مجلد العمل المؤقت." }

        var bundlesProcessed = 0
        var textAssetsTranslated = 0
        var stringsTranslated = 0
        var stringsSkipped = 0

        ZipFile(inputApk).use { zip ->
            // Pass 1: find UnityFS bundle entries and localize each in the
            // working directory. Byte-identical entries stream through later.
            val replaced = HashMap<String, ByteArray>()
            val entries = zip.entries()
            var scanned = 0
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory) continue
                scanned++
                require(scanned <= MAX_ARCHIVE_ENTRIES) { "عدد عناصر APK يتجاوز الحد الآمن." }
                if (entry.size !in 1L..MAX_BUNDLE_BYTES) continue
                val probe = ByteArray(8)
                val read = zip.getInputStream(entry).use { it.read(probe) }
                if (read < 8 || String(probe, Charsets.US_ASCII) != "UnityFS\u0000") continue

                val bundleIn = File(workingDir, "bundle-$scanned.in")
                val bundleOut = File(workingDir, "bundle-$scanned.out")
                val nodeDir = File(workingDir, "bundle-$scanned.nodes")
                try {
                    zip.getInputStream(entry).use { input ->
                        bundleIn.outputStream().use { input.copyTo(it) }
                    }
                    require(bundleIn.length() == entry.size) { "حجم الحزمة المستخرجة لا يطابق بيانات ZIP." }
                    nodeDir.mkdirs()
                    val result = localizeBundle(bundleIn, bundleOut, nodeDir, translate, limits)
                    if (result != null) {
                        bundlesProcessed++
                        textAssetsTranslated += result.first
                        stringsTranslated += result.second
                        stringsSkipped += result.third
                        replaced[entry.name] = bundleOut.readBytes()
                    }
                } finally {
                    bundleIn.delete()
                    bundleOut.delete()
                    nodeDir.deleteRecursively()
                }
            }

            require(stringsTranslated > 0) {
                "لم يتم العثور على TextAssets قابلة للترجمة داخل حزم UnityFS في هذا الملف."
            }

            // Pass 2: rebuild the APK, replacing localized bundles.
            outputApk.parentFile?.mkdirs()
            val countingOutput = CountingOutputStream(outputApk.outputStream().buffered())
            ZipOutputStream(countingOutput).use { out ->
                val all = zip.entries()
                var writtenEntries = 0
                var copiedArchiveBytes = 0L
                while (all.hasMoreElements()) {
                    val entry = all.nextElement()
                    writtenEntries++
                    require(writtenEntries <= MAX_ARCHIVE_ENTRIES) { "عدد عناصر APK تغير أثناء إعادة البناء." }
                    if (isOldSignature(entry.name)) continue
                    val replacement = replaced[entry.name]
                    val target = ApkZipEntryPolicy.create(entry, replacement, countingOutput.bytesWritten)
                    out.putNextEntry(target)
                    if (!entry.isDirectory) {
                        val remaining = MAX_TOTAL_REBUILT_BYTES - copiedArchiveBytes
                        require(remaining >= 0L) { "الحجم المفكوك للأرشيف تجاوز حد إعادة البناء الآمن." }
                        val entryBudget = minOf(MAX_SINGLE_REBUILT_ENTRY_BYTES, remaining)
                        if (replacement != null) {
                            require(replacement.size.toLong() <= entryBudget) { "الحزمة المعربة تجاوزت حد الحجم الآمن." }
                            out.write(replacement)
                            copiedArchiveBytes += replacement.size.toLong()
                        } else {
                            val copied = zip.getInputStream(entry).use { BoundedArchiveInput.copy(it, out, entryBudget) }
                            require(entry.size < 0L || copied == entry.size) {
                                "حجم عنصر ZIP الفعلي لا يطابق بياناته: ${entry.name}"
                            }
                            copiedArchiveBytes += copied
                        }
                    }
                    out.closeEntry()
                }
            }
        }

        return UnityApkBundleReport(
            bundlesProcessed, textAssetsTranslated, stringsTranslated, stringsSkipped, outputApk
        )
    }

    /** Returns (textAssets, translatedStrings, skippedStrings) or null when nothing qualified. */
    private suspend fun localizeBundle(
        bundleIn: File,
        bundleOut: File,
        nodeDir: File,
        translate: suspend (String) -> String,
        limits: UnityFsDecodeLimits
    ): Triple<Int, Int, Int>? {
        val stream = java.io.ByteArrayOutputStream()
        val decoded = UnityFsBundleDecoder.decode(bundleIn, stream, limits)
            ?: error("حزمة UnityFS داخل APK لا تُفك؛ أُوقفت العملية حفاظًا على سلامة الملف.")
        val streamBytes = stream.toByteArray()

        var textAssets = 0
        var translated = 0
        var skipped = 0
        val replacements = mutableListOf<Pair<String, ByteArray>>()

        decoded.blockInfo.nodes.forEach { node ->
            if (node.size !in 1L..Int.MAX_VALUE.toLong()) return@forEach
            val nodeBytes = streamBytes.copyOfRange(
                node.offset.toInt(), (node.offset + node.size).toInt()
            )
            val nodeFile = File(nodeDir, "node-${node.path.hashCode()}.serialized")
            nodeFile.writeBytes(nodeBytes)
            val inspection = nodeFile.inputStream().use {
                UnitySerializedFileInspector.inspect(it, nodeFile.length())
            } ?: return@forEach.also { nodeFile.delete() }
            val typeTable = inspection.typeTable ?: return@forEach.also { nodeFile.delete() }
            val objectTable = inspection.objectTable ?: return@forEach.also { nodeFile.delete() }

            var working = nodeFile
            var patched = false
            inspection.objectTable.objects.forEach { objectInfo ->
                val record = typeTable.records.getOrNull(objectInfo.typeId) ?: return@forEach
                if (record.classId != 49) return@forEach // TextAsset only
                // Re-inspect the working file: earlier patches shift tables.
                val currentInspection = working.inputStream().use {
                    UnitySerializedFileInspector.inspect(it, working.length())
                } ?: error("فشلت إعادة فحص SerializedFile بعد التعديل.")
                val currentTable = currentInspection.objectTable ?: return@forEach
                val currentObject = currentTable.objects.singleOrNull { it.pathId == objectInfo.pathId }
                    ?: return@forEach
                val content = UnityTextAssetReader.read(
                    working, currentInspection.header, currentInspection.typeTable!!, currentObject
                ) ?: return@forEach // binary or unsupported schema: skip, never guess
                val formatSource = content.pathName?.takeIf { it.isNotBlank() } ?: content.name
                val format = formatSource.substringAfterLast('.', "").lowercase()
                if (format !in supportedFormats) return@forEach

                val transformed = try {
                    AssetTextTransformer.transform(formatSource, content.script, translate)
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    return@forEach
                }
                skipped += transformed.skipped
                if (transformed.translated == 0) return@forEach

                val patchedFile = File(nodeDir, "patched-${objectInfo.pathId}.serialized")
                UnityTextAssetSerializedPatcher.patch(
                    working, patchedFile, currentInspection.header,
                    currentInspection.typeTable, currentTable,
                    objectInfo.pathId, transformed.text
                )
                if (working != nodeFile) working.delete()
                working = patchedFile
                patched = true
                textAssets++
                translated += transformed.translated
            }

            if (patched) {
                replacements += node.path to working.readBytes()
            }
            if (working != nodeFile) working.delete()
            nodeFile.delete()
        }

        if (replacements.isEmpty()) return null
        // One node per rewrite call; chain through temp files when a bundle
        // carries several SerializedFiles. Each step re-decodes and verifies.
        var source = bundleIn
        var step = 0
        replacements.forEach { (nodePath, nodeBytes) ->
            step++
            val target = if (step == replacements.size) {
                bundleOut
            } else {
                File(bundleOut.parentFile, "bundle-chain-$step.tmp")
            }
            UnityFsBundleRewriter.rewriteNode(source, target, nodePath, nodeBytes, limits)
            if (source != bundleIn) source.delete()
            source = target
        }
        return Triple(textAssets, translated, skipped)
    }

    private fun isOldSignature(path: String): Boolean {
        if (!path.startsWith("META-INF/", true)) return false
        val name = path.substringAfterLast('/').uppercase()
        return name.endsWith(".RSA") || name.endsWith(".DSA") || name.endsWith(".EC") ||
            name.endsWith(".SF") || name == "MANIFEST.MF"
    }
}
