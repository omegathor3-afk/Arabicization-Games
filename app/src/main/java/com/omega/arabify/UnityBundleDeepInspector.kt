package com.omega.arabify

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.zip.ZipFile

internal data class UnityBundleDeepInspectionLimits(
    val maxBundles: Int = 4,
    val maxSingleBundleBytes: Long = 128L * 1024L * 1024L,
    val maxTotalBundleBytes: Long = 256L * 1024L * 1024L,
    val maxNodes: Int = 10_000,
    val maxSingleNodeBytes: Long = 128L * 1024L * 1024L,
    val maxTotalExtractedBytes: Long = 256L * 1024L * 1024L,
    val maxSerializedMetadataBytes: Long = 64L * 1024L * 1024L,
    val maxZipEntriesToVerify: Int = 100_000,
    val scratchReserveBytes: Long = 16L * 1024L * 1024L
)

internal data class UnityBundleDeepInspectionItem(
    val path: String,
    val status: String,
    val nodesExtracted: Int = 0,
    val serializedFilesParsed: Int = 0,
    val objectRecordsIndexed: Int = 0,
    val details: List<String> = emptyList()
)

internal data class UnityBundleDeepInspectionReport(
    val bundlesFound: Int,
    val bundlesInspected: Int,
    val nodesExtracted: Int,
    val serializedFilesParsed: Int,
    val objectRecordsIndexed: Int,
    val items: List<UnityBundleDeepInspectionItem>,
    val notes: List<String>
) {
    val summary: String
        get() = "UnityFS: " + bundlesInspected + "/" + bundlesFound +
            " • CAB nodes: " + nodesExtracted +
            " • SerializedFiles: " + serializedFilesParsed +
            " • object records indexed: " + objectRecordsIndexed +
            " • قراءة فقط"
}

/**
 * Optional, read-only deep inspection for UnityFS bundles embedded in an APK.
 * All bundle copies and extracted CAB nodes live in a private temporary cache
 * directory and are deleted before this method returns.
 */
internal object UnityBundleDeepInspector {
    fun inspect(
        apk: File,
        inventory: UnityAssetInventory,
        scratchDirectory: File,
        limits: UnityBundleDeepInspectionLimits = UnityBundleDeepInspectionLimits(),
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): UnityBundleDeepInspectionReport {
        require(apk.isFile && apk.length() > 0L) { "ملف APK غير موجود أو فارغ." }
        require(validLimits(limits)) { "حدود فحص UnityFS غير صالحة." }
        val scratchPath = scratchDirectory.toPath().toAbsolutePath().normalize()
        require(Files.isDirectory(scratchPath, LinkOption.NOFOLLOW_LINKS) &&
            !Files.isSymbolicLink(scratchPath)
        ) { "مجلد الفحص المؤقت غير آمن." }

        // The visible inventory is intentionally capped at 100 rows. Use the
        // scanner's separate bounded bundle index so later ZIP entries are not
        // silently omitted from deep inspection.
        val indexedCandidates = inventory.bundleCandidates.ifEmpty {
            inventory.items.filter { it.kind == UnityAssetKind.UNITY_ARCHIVE }
        }
        val candidates = indexedCandidates.take(limits.maxBundles)
        val notes = mutableListOf<String>()
        if (inventory.scanTruncated) {
            notes += "الجرد توقف عند حد إدخالات ZIP؛ عدد الحزم المكتشفة يخص الجزء المفحوص فقط."
        }
        if (inventory.bundleFiles > indexedCandidates.size) {
            notes += "فهرس الحزم محدود؛ بعض UnityFS bundles لم تدخل قائمة المرشحين."
        }
        if (indexedCandidates.size > candidates.size) {
            notes += "تم الوصول إلى حد عدد UnityFS bundles في هذه الجولة؛ بقية الحزم لم تُفك."
        }
        if (inventory.scanTruncated) {
            notes += "تم إيقاف الفحص العميق لأن جرد APK جزئي ولا يمكن إثبات عدم وجود أسماء ZIP مكررة خارج الجزء المفحوص."
            return UnityBundleDeepInspectionReport(
                bundlesFound = inventory.bundleFiles,
                bundlesInspected = 0,
                nodesExtracted = 0,
                serializedFilesParsed = 0,
                objectRecordsIndexed = 0,
                items = candidates.map {
                    UnityBundleDeepInspectionItem(
                        it.path,
                        "تخطي: جرد APK جزئي؛ أعد الجرد بحد كامل قبل فحص UnityFS."
                    )
                },
                notes = notes
            )
        }
        if (candidates.isEmpty()) {
            return UnityBundleDeepInspectionReport(
                inventory.bundleFiles, 0, 0, 0, 0, emptyList(),
                notes + "لم يُعثر على Unity Archive ضمن الجزء المفحوص من الجرد."
            )
        }

        val results = mutableListOf<UnityBundleDeepInspectionItem>()
        var totalBundleBytes = 0L
        var bundlesInspected = 0
        var nodesExtracted = 0
        var serializedFilesParsed = 0
        var objectRecordsIndexed = 0

        ZipFile(apk).use { zip ->
            val candidatePaths = candidates.map { it.path }.toSet()
            val pathCounts = mutableMapOf<String, Int>()
            val allEntries = zip.entries()
            var entriesVerified = 0
            var verificationTruncated = false
            while (allEntries.hasMoreElements()) {
                if (entriesVerified >= limits.maxZipEntriesToVerify) {
                    verificationTruncated = true
                    break
                }
                val entry = allEntries.nextElement()
                entriesVerified++
                if (entry.name in candidatePaths) {
                    pathCounts[entry.name] = (pathCounts[entry.name] ?: 0) + 1
                }
            }

            if (verificationTruncated) {
                notes += "تم إيقاف الفحص العميق عند حد " + limits.maxZipEntriesToVerify +
                    " إدخال ZIP؛ لم تُفحص الحزم لأن تفرد المسارات لم يُثبت."
                candidates.forEach {
                    results += UnityBundleDeepInspectionItem(
                        it.path,
                        "تخطي: تجاوز فحص تفرد أسماء ZIP الحد الآمن؛ لم تتم قراءة بيانات الحزمة."
                    )
                }
                return@use
            }

            // Reserve enough cache space for the planned compressed bundle copies,
            // the maximum decoded CAB output, and a small safety margin before
            // creating any temporary files. This is deliberately conservative.
            var plannedBundleBytes = 0L
            candidates.forEach { candidate ->
                val entry = zip.getEntry(candidate.path)
                if (pathCounts[candidate.path] == 1 && entry != null &&
                    !entry.isDirectory && entry.size in 1..limits.maxSingleBundleBytes
                ) {
                    val next = try {
                        Math.addExact(plannedBundleBytes, entry.size)
                    } catch (_: ArithmeticException) {
                        Long.MAX_VALUE
                    }
                    if (next <= limits.maxTotalBundleBytes) {
                        plannedBundleBytes = next
                    }
                }
            }
            val requiredScratchBytes = requiredUnityScratchSpaceBytes(
                plannedBundleBytes,
                if (plannedBundleBytes > 0L) limits.maxTotalExtractedBytes else 0L,
                limits.scratchReserveBytes
            )
            val availableScratchBytes = scratchDirectory.usableSpace
            if (requiredScratchBytes == null || availableScratchBytes < requiredScratchBytes) {
                notes += if (requiredScratchBytes == null) {
                    "تعذر حساب مساحة الفحص المؤقت بأمان بسبب تجاوز عددي؛ لم تُنشأ ملفات مؤقتة."
                } else {
                    "المساحة الحرة غير كافية لفحص UnityFS بأمان؛ المطلوب تقريبًا " +
                        ((requiredScratchBytes + 1_048_575L) / 1_048_576L) + " MB والمتاح " +
                        (availableScratchBytes / 1_048_576L) + " MB. لم تُنشأ ملفات مؤقتة."
                }
                candidates.forEach {
                    results += UnityBundleDeepInspectionItem(
                        it.path,
                        "تخطي: مساحة التخزين المؤقت غير كافية؛ لم تتم قراءة بيانات الحزمة."
                    )
                }
                return@use
            }

            val workDirectory = Files.createTempDirectory(scratchPath, "arabify-unity-deep-").toFile()
            try {
                candidates.forEachIndexed { index, candidate ->
                    val entry = zip.getEntry(candidate.path)
                    if (pathCounts[candidate.path] != 1 || entry == null) {
                        results += UnityBundleDeepInspectionItem(
                            candidate.path,
                            "تخطي: اسم ZIP مكرر أو غير موجود؛ لم تتم قراءة أي بيانات."
                        )
                        onProgress(index + 1, candidates.size)
                        return@forEachIndexed
                    }
                    if (entry.isDirectory || entry.size !in 1..limits.maxSingleBundleBytes) {
                        results += UnityBundleDeepInspectionItem(
                            candidate.path,
                            "تخطي: حجم Bundle مجهول أو يتجاوز الحد الآمن."
                        )
                        onProgress(index + 1, candidates.size)
                        return@forEachIndexed
                    }
                    val nextTotal = try {
                        Math.addExact(totalBundleBytes, entry.size)
                    } catch (_: ArithmeticException) {
                        Long.MAX_VALUE
                    }
                    if (nextTotal > limits.maxTotalBundleBytes) {
                        results += UnityBundleDeepInspectionItem(
                            candidate.path,
                            "تخطي: تجاوز الحجم الإجمالي المسموح لنسخ Bundles."
                        )
                        onProgress(index + 1, candidates.size)
                        return@forEachIndexed
                    }

                    val bundleFile = File(workDirectory, "bundle-" + index + ".bin")
                    val outputDirectory = File(workDirectory, "cab-" + index)
                    try {
                        val copied = zip.getInputStream(entry).use { input ->
                            FileOutputStream(bundleFile).use { output ->
                                BoundedArchiveInput.copy(input, output, limits.maxSingleBundleBytes)
                            }
                        }
                        require(copied == entry.size) {
                            "حجم UnityFS الفعلي لا يطابق حجم ZIP المعلن."
                        }
                        totalBundleBytes = nextTotal

                        val headerPrefix = FileInputStream(bundleFile).use { input ->
                            val buffer = ByteArray(minOf(1024L, bundleFile.length()).toInt())
                            var offset = 0
                            while (offset < buffer.size) {
                                val count = input.read(buffer, offset, buffer.size - offset)
                                if (count < 0) break
                                offset += count
                            }
                            buffer.copyOf(offset)
                        }
                        val header = UnityArchiveHeaderParser.parse(headerPrefix, bundleFile.length())
                        if (header?.signature != "UnityFS") {
                            results += UnityBundleDeepInspectionItem(
                                candidate.path,
                                "غير مدعوم في هذه المرحلة: الحزمة ليست UnityFS صالحة؛ بقي الأصل دون تعديل."
                            )
                            onProgress(index + 1, candidates.size)
                            return@forEachIndexed
                        }

                        val decoded = UnityFsBundleExtractor.extract(
                            bundleFile = bundleFile,
                            scratchDirectory = workDirectory,
                            destinationDirectory = outputDirectory,
                            decodeLimits = UnityFsDecodeLimits(
                                maxCompressedBlockBytes = 64L * 1024L * 1024L,
                                maxDecodedBlockBytes = 64L * 1024L * 1024L,
                                maxTotalDecodedBytes = limits.maxTotalExtractedBytes,
                                maxBlockInfoBytes = 32L * 1024L * 1024L,
                                maxLzmaMemoryBytes = 64L * 1024L * 1024L,
                                maxWorkingSetBytes = 192L * 1024L * 1024L
                            ),
                            extractionLimits = UnityBundleNodeExtractionLimits(
                                maxNodes = limits.maxNodes,
                                maxPathChars = 2_048,
                                maxSingleNodeBytes = limits.maxSingleNodeBytes,
                                maxTotalExtractedBytes = limits.maxTotalExtractedBytes,
                                bufferBytes = 64 * 1024
                            )
                        )
                        if (decoded == null) {
                            results += UnityBundleDeepInspectionItem(
                                candidate.path,
                                "تعذر فك UnityFS أو استخراج CAB ضمن الحدود؛ الحزمة الأصلية محفوظة."
                            )
                            onProgress(index + 1, candidates.size)
                            return@forEachIndexed
                        }

                        bundlesInspected++
                        nodesExtracted += decoded.filesExtracted
                        val details = mutableListOf<String>()
                        var parsedInBundle = 0
                        var objectsInBundle = 0
                        val extractedFiles = mutableListOf<File>()
                        Files.walk(outputDirectory.toPath()).use { paths ->
                            paths.forEach { path ->
                                if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                                    extractedFiles += path.toFile()
                                }
                            }
                        }
                        extractedFiles.take(limits.maxNodes).forEach { extracted ->
                            val size = extracted.length()
                            if (size !in 20..limits.maxSerializedMetadataBytes) return@forEach
                            val prefix = FileInputStream(extracted).use { input ->
                                val buffer = ByteArray(minOf(48L, size).toInt())
                                var offset = 0
                                while (offset < buffer.size) {
                                    val count = input.read(buffer, offset, buffer.size - offset)
                                    if (count < 0) break
                                    offset += count
                                }
                                buffer.copyOf(offset)
                            }
                            if (UnitySerializedFileHeaderParser.parse(prefix, size) == null) {
                                return@forEach
                            }
                            val inspection = FileInputStream(extracted).use { input ->
                                UnitySerializedFileInspector.inspect(input, size)
                            } ?: return@forEach
                            parsedInBundle++
                            val objectCount = inspection.objectTable?.objects?.size ?: 0
                            objectsInBundle += objectCount
                            val relativePath = outputDirectory.toPath()
                                .relativize(extracted.toPath()).toString()
                            details += relativePath + ": SerializedFile v" +
                                inspection.header.formatVersion + ", stage=" +
                                inspection.stage.name + ", types=" +
                                (inspection.metadataPrefix?.typeCount ?: 0) +
                                ", objects=" + objectCount + " (read-only)"
                        }
                        serializedFilesParsed += parsedInBundle
                        objectRecordsIndexed += objectsInBundle
                        if (details.isEmpty()) {
                            details += "تم استخراج " + decoded.filesExtracted +
                                " CAB nodes؛ لم يُعثر على SerializedFile قابل للتحليل ضمن الإصدارات المدعومة."
                        }
                        results += UnityBundleDeepInspectionItem(
                            candidate.path,
                            "تم فك UnityFS واستخراج CAB للقراءة فقط؛ لم يتم تعديل أي أصل.",
                            decoded.filesExtracted,
                            parsedInBundle,
                            objectsInBundle,
                            details.take(12)
                        )
                    } catch (error: Exception) {
                        results += UnityBundleDeepInspectionItem(
                            candidate.path,
                            "فشل الفحص الآمن: " + (error.message ?: "خطأ غير محدد") +
                                "؛ الأصل محفوظ دون تعديل."
                        )
                    } finally {
                        bundleFile.delete()
                        outputDirectory.deleteRecursively()
                    }
                    onProgress(index + 1, candidates.size)
                }
            } finally {
                workDirectory.deleteRecursively()
            }
        }

        return UnityBundleDeepInspectionReport(
            bundlesFound = inventory.bundleFiles,
            bundlesInspected = bundlesInspected,
            nodesExtracted = nodesExtracted,
            serializedFilesParsed = serializedFilesParsed,
            objectRecordsIndexed = objectRecordsIndexed,
            items = results,
            notes = notes
        )
    }

    private fun validLimits(limits: UnityBundleDeepInspectionLimits): Boolean =
        limits.maxBundles in 1..16 &&
            limits.maxSingleBundleBytes in 1..(512L * 1024L * 1024L) &&
            limits.maxTotalBundleBytes >= limits.maxSingleBundleBytes &&
            limits.maxTotalBundleBytes <= 1024L * 1024L * 1024L &&
            limits.maxNodes in 1..100_000 &&
            limits.maxSingleNodeBytes >= 1L &&
            limits.maxTotalExtractedBytes >= limits.maxSingleNodeBytes &&
            limits.maxTotalExtractedBytes <= 1024L * 1024L * 1024L &&
            limits.maxSerializedMetadataBytes in 1..(64L * 1024L * 1024L) &&
            limits.maxZipEntriesToVerify in 1..100_000 &&
            limits.scratchReserveBytes in 0L..(256L * 1024L * 1024L)
}

internal fun requiredUnityScratchSpaceBytes(
    compressedBundleBytes: Long,
    maxExtractedBytes: Long,
    reserveBytes: Long
): Long? {
    if (compressedBundleBytes < 0L || maxExtractedBytes < 0L || reserveBytes < 0L) return null
    return try {
        Math.addExact(Math.addExact(compressedBundleBytes, maxExtractedBytes), reserveBytes)
    } catch (_: ArithmeticException) {
        null
    }
}

internal fun hasEnoughUnityScratchSpace(
    availableBytes: Long,
    compressedBundleBytes: Long,
    maxExtractedBytes: Long,
    reserveBytes: Long
): Boolean {
    val required = requiredUnityScratchSpaceBytes(
        compressedBundleBytes, maxExtractedBytes, reserveBytes
    ) ?: return false
    return availableBytes >= required
}
