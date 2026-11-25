package com.omega.arabify

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

internal data class UnityApkBundleReport(
    val bundlesProcessed: Int,
    val textAssetsTranslated: Int,
    val stringsTranslated: Int,
    val stringsSkipped: Int,
    val outputApk: File,
    val rewrittenEntryNames: Set<String> = emptySet()
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
    private const val MAX_TOTAL_STAGED_BUNDLE_BYTES = 512L * 1024L * 1024L
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
    ): UnityApkBundleReport =
        localizeBundlesInApk(inputApk, outputApk, workingDir, translate, limits, requireTranslations = true)
            ?: error("لم يتم العثور على TextAssets قابلة للترجمة داخل حزم UnityFS في هذا الملف.")

    /**
     * Tolerant variant for chained pipelines: returns null when no bundle
     * holds translatable TextAssets, writing nothing, instead of failing.
     * Strict-gate failures (corrupt bundles) still abort.
     */
    internal suspend fun tryLocalizeBundlesInApk(
        inputApk: File,
        outputApk: File,
        workingDir: File,
        translate: suspend (String) -> String,
        limits: UnityFsDecodeLimits = UnityFsDecodeLimits()
    ): UnityApkBundleReport? =
        localizeBundlesInApk(inputApk, outputApk, workingDir, translate, limits, requireTranslations = false)

    private suspend fun localizeBundlesInApk(
        inputApk: File,
        outputApk: File,
        workingDir: File,
        translate: suspend (String) -> String,
        limits: UnityFsDecodeLimits,
        requireTranslations: Boolean
    ): UnityApkBundleReport? {
        require(inputApk.isFile) { "ملف APK الأصلي غير موجود." }
        require(inputApk.canonicalFile != outputApk.canonicalFile) {
            "لا يمكن الكتابة فوق ملف APK الأصلي نفسه، حتى عبر مسار بديل."
        }
        require(workingDir.isDirectory || workingDir.mkdirs()) { "تعذر إنشاء مجلد العمل المؤقت." }

        val operationDir = Files.createTempDirectory(workingDir.toPath(), ".arabify-unity-apk-").toFile()
        var temporaryApk: File? = null
        try {
            var bundlesProcessed = 0
            var textAssetsTranslated = 0
            var stringsTranslated = 0
            var stringsSkipped = 0
            var unityBundlesSeen = 0
            var stagedReplacementBytes = 0L
            val replacements = LinkedHashMap<String, File>()

            ZipFile(inputApk).use { zip ->
                val entries = zip.entries()
                var scanned = 0
                val seenEntryNames = HashSet<String>()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    require(seenEntryNames.add(entry.name)) {
                        "يحتوي APK على أسماء ZIP مكررة؛ أُوقفت إعادة البناء لتجنب اختلاف تفسير الملفات."
                    }
                    if (entry.isDirectory) continue
                    scanned++
                    require(scanned <= MAX_ARCHIVE_ENTRIES) { "عدد عناصر APK يتجاوز الحد الآمن." }
                    if (entry.size !in 1L..MAX_BUNDLE_BYTES) continue

                    val probe = ByteArray(8)
                    val read = zip.getInputStream(entry).use { input ->
                        var count = 0
                        while (count < probe.size) {
                            val next = input.read(probe, count, probe.size - count)
                            if (next < 0) break
                            if (next > 0) count += next
                        }
                        count
                    }
                    if (read < 8 || String(probe, Charsets.US_ASCII) != "UnityFS\u0000") continue

                    unityBundlesSeen++
                    require(unityBundlesSeen <= MAX_BUNDLES) {
                        "عدد حزم UnityFS يتجاوز الحد الآمن ($MAX_BUNDLES)."
                    }

                    val bundleIn = File(operationDir, "bundle-$scanned.in")
                    val bundleOut = File(operationDir, "bundle-$scanned.out")
                    val nodeDir = File(operationDir, "bundle-$scanned.nodes")
                    var keepBundleOut = false
                    try {
                        val copied = zip.getInputStream(entry).use { source ->
                            bundleIn.outputStream().buffered().use { destination ->
                                BoundedArchiveInput.copy(source, destination, MAX_BUNDLE_BYTES)
                            }
                        }
                        require(copied == entry.size) { "حجم حزمة UnityFS المستخرجة لا يطابق بيانات ZIP." }
                        require(nodeDir.mkdirs()) { "تعذر إنشاء مجلد عقد UnityFS المؤقت." }

                        val result = localizeBundle(bundleIn, bundleOut, nodeDir, translate, limits)
                        if (result != null) {
                            val replacementBytes = bundleOut.length()
                            require(bundleOut.isFile && replacementBytes in 1L..MAX_SINGLE_REBUILT_ENTRY_BYTES) {
                                "حجم حزمة UnityFS المعربة غير صالح أو يتجاوز الحد الآمن."
                            }
                            val nextStagedBytes = Math.addExact(stagedReplacementBytes, replacementBytes)
                            require(nextStagedBytes <= MAX_TOTAL_STAGED_BUNDLE_BYTES) {
                                "إجمالي الحزم المعربة المؤقتة يتجاوز حد التخزين الآمن."
                            }
                            stagedReplacementBytes = nextStagedBytes
                            replacements[entry.name] = bundleOut
                            keepBundleOut = true
                            bundlesProcessed++
                            textAssetsTranslated += result.first
                            stringsTranslated += result.second
                            stringsSkipped += result.third
                        }
                    } finally {
                        bundleIn.delete()
                        if (!keepBundleOut) bundleOut.delete()
                        nodeDir.deleteRecursively()
                    }
                }

                if (stringsTranslated == 0) {
                    require(!requireTranslations) {
                        "لم يتم العثور على TextAssets قابلة للترجمة داخل حزم UnityFS في هذا الملف."
                    }
                    return null
                }

                val outputParent = outputApk.absoluteFile.parentFile
                    ?: error("مجلد APK الناتج غير صالح.")
                require(outputParent.isDirectory || outputParent.mkdirs()) { "تعذر إنشاء مجلد APK الناتج." }
                val stagedApk = Files.createTempFile(outputParent.toPath(), ".arabify-apk-", ".tmp").toFile()
                temporaryApk = stagedApk

                val countingOutput = CountingOutputStream(stagedApk.outputStream().buffered())
                ZipOutputStream(countingOutput).use { out ->
                    val all = zip.entries()
                    var writtenEntries = 0
                    var copiedArchiveBytes = 0L
                    while (all.hasMoreElements()) {
                        val entry = all.nextElement()
                        writtenEntries++
                        require(writtenEntries <= MAX_ARCHIVE_ENTRIES) {
                            "عدد عناصر APK تغير أثناء إعادة البناء."
                        }
                        if (isOldSignature(entry.name)) continue
                        val replacement = replacements[entry.name]
                        val target = ApkZipEntryPolicy.createWithFileReplacement(
                            entry, replacement, countingOutput.bytesWritten
                        )
                        out.putNextEntry(target)
                        if (!entry.isDirectory) {
                            val remaining = MAX_TOTAL_REBUILT_BYTES - copiedArchiveBytes
                            require(remaining >= 0L) {
                                "الحجم المفكوك للأرشيف تجاوز حد إعادة البناء الآمن."
                            }
                            val entryBudget = minOf(MAX_SINGLE_REBUILT_ENTRY_BYTES, remaining)
                            if (replacement != null) {
                                val expectedBytes = replacement.length()
                                require(expectedBytes <= entryBudget) {
                                    "الحزمة المعربة تجاوزت حد الحجم الآمن."
                                }
                                val copied = replacement.inputStream().buffered().use {
                                    BoundedArchiveInput.copy(it, out, entryBudget)
                                }
                                require(copied == expectedBytes) {
                                    "تغير حجم الحزمة المعربة أثناء إعادة بناء APK."
                                }
                                copiedArchiveBytes = Math.addExact(copiedArchiveBytes, copied)
                            } else {
                                val copied = zip.getInputStream(entry).use {
                                    BoundedArchiveInput.copy(it, out, entryBudget)
                                }
                                require(entry.size < 0L || copied == entry.size) {
                                    "حجم عنصر ZIP الفعلي لا يطابق بياناته: ${entry.name}"
                                }
                                copiedArchiveBytes = Math.addExact(copiedArchiveBytes, copied)
                            }
                        }
                        out.closeEntry()
                    }
                }
            }

            val stagedApk = requireNotNull(temporaryApk)
            UnityAssetIntegrityVerifier.verifyUnchanged(inputApk, stagedApk, replacements.keys)
            Files.move(
                stagedApk.toPath(),
                outputApk.absoluteFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
            temporaryApk = null

            return UnityApkBundleReport(
                bundlesProcessed, textAssetsTranslated, stringsTranslated,
                stringsSkipped, outputApk, replacements.keys.toSet()
            )
        } finally {
            temporaryApk?.delete()
            operationDir.deleteRecursively()
        }
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
