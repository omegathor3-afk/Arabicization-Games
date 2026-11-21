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
    val maxTotalDecodedBytes: Long = 256L * 1024L * 1024L,
    val maxAggregateExtractedBytes: Long = 256L * 1024L * 1024L,
    val maxSerializedMetadataBytes: Long = 64L * 1024L * 1024L,
    val maxZipEntriesToVerify: Int = 100_000,
    val scratchReserveBytes: Long = 16L * 1024L * 1024L,
    val maxObjectRecordsPerFile: Int = 100_000,
    val maxTotalObjectRecords: Int = 250_000,
    val maxTextAssetsPerFile: Int = 100,
    val maxTextAssetObjectBytes: Long = 1024L * 1024L,
    val maxTotalTextAssetBytes: Long = 8L * 1024L * 1024L
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
    val notes: List<String>,
    val objectRecordsBudgetExhausted: Boolean = false,
    val bytesExtracted: Long = 0L,
    val textAssetsReadOnly: Int = 0,
    val textAssetUtf8Candidates: Int = 0
) {
    val summary: String
        get() = "UnityFS: " + bundlesInspected + "/" + bundlesFound +
            " • CAB nodes: " + nodesExtracted +
            " • SerializedFiles: " + serializedFilesParsed +
            " • object records indexed: " + objectRecordsIndexed +
            " • TextAssets read-only: " + textAssetsReadOnly +
            " • UTF-8 text candidates: " + textAssetUtf8Candidates +
            " • CAB bytes: " + bytesExtracted +
            (if (objectRecordsBudgetExhausted) " • تم بلوغ حد فهرسة الكائنات" else "") +
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
        var totalExtractedBytes = 0L
        var remainingAggregateExtractedBytes = limits.maxAggregateExtractedBytes
        var bundlesInspected = 0
        var nodesExtracted = 0
        var serializedFilesParsed = 0
        var objectRecordsIndexed = 0
        var remainingObjectRecords = limits.maxTotalObjectRecords
        var remainingTextAssetBytes = limits.maxTotalTextAssetBytes
        var textAssetsReadOnly = 0
        var textAssetUtf8Candidates = 0
        var objectRecordsBudgetExhausted = false

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

            // Preflight the peak temporary working set: one compressed bundle
            // copy, its decoded-storage intermediate, the CAB staging tree, and
            // the safety reserve. These temporary files are removed per candidate.
            var maxPlannedBundleCopyBytes = 0L
            candidates.forEach { candidate ->
                val entry = zip.getEntry(candidate.path)
                if (pathCounts[candidate.path] == 1 && entry != null &&
                    !entry.isDirectory && entry.size in 1..limits.maxSingleBundleBytes
                ) {
                    maxPlannedBundleCopyBytes = maxOf(maxPlannedBundleCopyBytes, entry.size)
                }
            }
            val requiredScratchBytes = requiredUnityScratchSpaceBytes(
                maxPlannedBundleCopyBytes,
                if (maxPlannedBundleCopyBytes > 0L) limits.maxTotalDecodedBytes else 0L,
                if (maxPlannedBundleCopyBytes > 0L) limits.maxTotalExtractedBytes else 0L,
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

                    if (remainingAggregateExtractedBytes <= 0L) {
                        results += UnityBundleDeepInspectionItem(
                            candidate.path,
                            "تخطي: تم بلوغ الحد الإجمالي لبيانات CAB المستخرجة؛ لم تُقرأ الحزمة."
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
                                maxTotalDecodedBytes = limits.maxTotalDecodedBytes,
                                maxBlockInfoBytes = 32L * 1024L * 1024L,
                                maxLzmaMemoryBytes = 64L * 1024L * 1024L,
                                maxWorkingSetBytes = 192L * 1024L * 1024L
                            ),
                            extractionLimits = UnityBundleNodeExtractionLimits(
                                maxNodes = limits.maxNodes,
                                maxPathChars = 2_048,
                                maxSingleNodeBytes = minOf(
                                    limits.maxSingleNodeBytes,
                                    limits.maxTotalExtractedBytes,
                                    remainingAggregateExtractedBytes
                                ),
                                maxTotalExtractedBytes = minOf(
                                    limits.maxTotalExtractedBytes,
                                    remainingAggregateExtractedBytes
                                ),
                                bufferBytes = 64 * 1024
                            )
                        )
                        if (decoded == null) {
                            results += UnityBundleDeepInspectionItem(
                                candidate.path,
                                "تعذر فك UnityFS أو استخراج CAB ضمن الحدود؛ المتبقي من ميزانية CAB الإجمالية " +
                                    remainingAggregateExtractedBytes + " bytes؛ الحزمة الأصلية محفوظة."
                            )
                            onProgress(index + 1, candidates.size)
                            return@forEachIndexed
                        }

                        require(decoded.bytesExtracted in 0L..remainingAggregateExtractedBytes) {
                            "UnityFS extraction exceeded the aggregate CAB output budget"
                        }
                        totalExtractedBytes = Math.addExact(totalExtractedBytes, decoded.bytesExtracted)
                        remainingAggregateExtractedBytes -= decoded.bytesExtracted
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
                            val relativePath = outputDirectory.toPath()
                                .relativize(extracted.toPath()).toString()
                            if (remainingObjectRecords <= 0) {
                                objectRecordsBudgetExhausted = true
                                details += relativePath +
                                    ": SerializedFile detected; object index skipped (aggregate budget exhausted)"
                                return@forEach
                            }
                            val allowedForFile = minOf(
                                limits.maxObjectRecordsPerFile,
                                remainingObjectRecords
                            )
                            val inspection = FileInputStream(extracted).use { input ->
                                UnitySerializedFileInspector.inspect(
                                    input, size, allowedForFile
                                )
                            } ?: return@forEach
                            parsedInBundle++
                            val objectCount = inspection.objectTable?.objects?.size ?: 0
                            objectsInBundle += objectCount
                            val objectSummary = if (inspection.objectTableLimitExceeded) {
                                val declared = inspection.declaredObjectCount
                                // Prefer the per-file diagnosis when that limit alone
                                // already rejects the complete table. Do not consume the
                                // aggregate budget for records that were never eligible.
                                val aggregateLimitIsActive =
                                    declared != null &&
                                        declared <= limits.maxObjectRecordsPerFile
                                if (aggregateLimitIsActive &&
                                    declared != null && declared > remainingObjectRecords
                                ) {
                                    val remaining = remainingObjectRecords
                                    remainingObjectRecords = 0
                                    objectRecordsBudgetExhausted = true
                                    "objects=aggregate-limit-exceeded (declared=" +
                                        declared + ", remaining=" + remaining + ")"
                                } else {
                                    "objects=per-file-limit-exceeded (declared=" +
                                        declared + ", max=" +
                                        limits.maxObjectRecordsPerFile + ")"
                                }
                            } else if (inspection.objectTable == null) {
                                // A failed/unsupported object-table parse is not an empty table.
                                // Keep the aggregate budget untouched and report the uncertainty.
                                "objects=unavailable (declared=" +
                                    (inspection.declaredObjectCount?.toString() ?: "unknown") + ")"
                            } else {
                                remainingObjectRecords -= objectCount
                                "objects=" + objectCount
                            }
                            var textAssetsInFile = 0
                            var textCandidatesInFile = 0
                            val typeTable = inspection.typeTable
                            val objectTable = inspection.objectTable
                            if (typeTable != null && objectTable != null &&
                                remainingTextAssetBytes > 0L
                            ) {
                                objectTable.objects.asSequence()
                                    .filter { UnityTextAssetReader.isTextAssetObject(
                                        inspection.header, typeTable, it
                                    ) }
                                    .take(limits.maxTextAssetsPerFile)
                                    .forEach textAssetLoop@ { textObject ->
                                        val objectBudget = minOf(
                                            limits.maxTextAssetObjectBytes,
                                            remainingTextAssetBytes
                                        )
                                        if (textObject.byteSize !in 1L..objectBudget) {
                                            return@textAssetLoop
                                        }
                                        // Charge the aggregate I/O budget before attempting to
                                        // decode; malformed TextAssets must not be retried for free.
                                        remainingTextAssetBytes -= textObject.byteSize
                                        val textAsset = UnityTextAssetReader.read(
                                            extracted,
                                            inspection.header,
                                            typeTable,
                                            textObject,
                                            maxObjectBytes = objectBudget
                                        ) ?: return@textAssetLoop
                                        textAssetsInFile++
                                        if (textAsset.script.isNotBlank()) {
                                            textCandidatesInFile++
                                        }
                                    }
                            }
                            textAssetsReadOnly += textAssetsInFile
                            textAssetUtf8Candidates += textCandidatesInFile
                            val textAssetSummary = if (textAssetsInFile > 0) {
                                ", TextAssets read-only=" + textAssetsInFile +
                                    ", non-empty UTF-8 scripts=" + textCandidatesInFile
                            } else {
                                ""
                            }
                            val trailingSummary = inspection.trailingMetadata?.let {
                                ", scripts=" + it.scriptReferences.size +
                                    ", externals=" + it.externals.size +
                                    ", referenceTypes=" + it.referenceTypes.size +
                                    ", sharedTypeTrees=" + it.sharedSubtreeHandlers.size +
                                    ", unknownTailBytes=" + it.unknownTailBytes
                            } ?: ", trailingMetadata=not-parsed"
                            details += relativePath + ": SerializedFile v" +
                                inspection.header.formatVersion + ", stage=" +
                                inspection.stage.name + ", types=" +
                                (inspection.metadataPrefix?.typeCount ?: 0) +
                                ", " + objectSummary + textAssetSummary + trailingSummary + " (read-only)"
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

        if (objectRecordsBudgetExhausted) {
            notes += "تم بلوغ الحد الإجمالي " + limits.maxTotalObjectRecords +
                " لسجلات الكائنات؛ لم تُفهرس جداول الكائنات المتبقية، وبقيت جميع الأصول للقراءة فقط."
        }
        return UnityBundleDeepInspectionReport(
            bundlesFound = inventory.bundleFiles,
            bundlesInspected = bundlesInspected,
            nodesExtracted = nodesExtracted,
            serializedFilesParsed = serializedFilesParsed,
            objectRecordsIndexed = objectRecordsIndexed,
            items = results,
            notes = notes,
            objectRecordsBudgetExhausted = objectRecordsBudgetExhausted,
            bytesExtracted = totalExtractedBytes,
            textAssetsReadOnly = textAssetsReadOnly,
            textAssetUtf8Candidates = textAssetUtf8Candidates
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
            limits.maxTotalDecodedBytes in 1L..(1024L * 1024L * 1024L) &&
            limits.maxAggregateExtractedBytes in 1L..(1024L * 1024L * 1024L) &&
            limits.maxSerializedMetadataBytes in 1..(64L * 1024L * 1024L) &&
            limits.maxZipEntriesToVerify in 1..100_000 &&
            limits.scratchReserveBytes in 0L..(256L * 1024L * 1024L) &&
            limits.maxObjectRecordsPerFile in 1..100_000 &&
            limits.maxTotalObjectRecords in 1..1_000_000 &&
            limits.maxTextAssetsPerFile in 1..1_000 &&
            limits.maxTextAssetObjectBytes in 1L..(4L * 1024L * 1024L) &&
            limits.maxTotalTextAssetBytes in 0L..(64L * 1024L * 1024L)
}

internal fun requiredUnityScratchSpaceBytes(
    compressedBundleBytes: Long,
    decodedStorageBytes: Long,
    extractedCabBytes: Long,
    reserveBytes: Long
): Long? {
    if (compressedBundleBytes < 0L || decodedStorageBytes < 0L ||
        extractedCabBytes < 0L || reserveBytes < 0L
    ) return null
    return try {
        Math.addExact(
            Math.addExact(
                Math.addExact(compressedBundleBytes, decodedStorageBytes),
                extractedCabBytes
            ),
            reserveBytes
        )
    } catch (_: ArithmeticException) {
        null
    }
}

internal fun hasEnoughUnityScratchSpace(
    availableBytes: Long,
    compressedBundleBytes: Long,
    decodedStorageBytes: Long,
    extractedCabBytes: Long,
    reserveBytes: Long
): Boolean {
    val required = requiredUnityScratchSpaceBytes(
        compressedBundleBytes, decodedStorageBytes, extractedCabBytes, reserveBytes
    ) ?: return false
    return availableBytes >= required
}
