package com.omega.arabify

import java.io.File
import java.security.MessageDigest
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile

internal enum class UnityAssetKind {
    STREAMING_TEXT,
    SERIALIZED_FILE,
    UNITY_ARCHIVE,
    EXTERNAL_BINARY_STREAM,
    MANAGED_ASSEMBLY,
    UNKNOWN_BINARY
}

internal enum class UnityAssetSupport {
    TEXT_CANDIDATE_READ_ONLY,
    HEADER_PARSED_READ_ONLY,
    BUNDLE_HEADER_ONLY_READ_ONLY,
    PRESERVE_AS_EXTERNAL_DATA,
    PRESERVE_UNSUPPORTED
}

internal data class UnitySerializedHeader(
    val metadataSize: Long,
    val fileSize: Long,
    val formatVersion: Int,
    val dataOffset: Long,
    val endian: String?,
    val headerSize: Int
)

/**
 * Conservative header validator only. It does not parse TypeTrees, object tables,
 * or serialized object payloads and must never be used as authorization to edit.
 */
internal object UnitySerializedFileHeaderParser {
    fun parse(prefix: ByteArray, actualLength: Long): UnitySerializedHeader? {
        if (prefix.size < 16 || actualLength < 16) return null
        val initial = ByteBuffer.wrap(prefix).order(ByteOrder.BIG_ENDIAN)
        val legacyMetadata = initial.int.toLong() and 0xffffffffL
        val legacyFileSize = initial.int.toLong() and 0xffffffffL
        val version = initial.int
        val legacyDataOffset = initial.int.toLong() and 0xffffffffL
        if (version !in 5..100) return null

        if (version >= 22) {
            if (prefix.size < 48) return null
            val extended = ByteBuffer.wrap(prefix).order(ByteOrder.BIG_ENDIAN)
            val endianMarker = prefix[16].toInt() and 0xff
            if (endianMarker !in 0..1) return null
            val metadataSize = extended.getInt(20).toLong() and 0xffffffffL
            val fileSize = extended.getLong(24)
            val dataOffset = extended.getLong(32)
            if (!validSizes(metadataSize, fileSize, dataOffset, actualLength, 48)) return null
            return UnitySerializedHeader(
                metadataSize, fileSize, version, dataOffset,
                if (endianMarker == 0) "little" else "big", 48
            )
        }

        val headerSize = if (version >= 9) 20 else 16
        if (prefix.size < headerSize) return null
        if (!validSizes(legacyMetadata, legacyFileSize, legacyDataOffset, actualLength, headerSize)) return null
        val endian = if (version >= 9) {
            when (prefix[16].toInt() and 0xff) {
                0 -> "little"
                1 -> "big"
                else -> return null
            }
        } else null
        return UnitySerializedHeader(
            legacyMetadata, legacyFileSize, version, legacyDataOffset, endian, headerSize
        )
    }

    private fun validSizes(
        metadataSize: Long,
        fileSize: Long,
        dataOffset: Long,
        actualLength: Long,
        headerSize: Int
    ): Boolean {
        return metadataSize > 0 &&
            fileSize >= headerSize &&
            fileSize <= actualLength &&
            dataOffset >= headerSize &&
            dataOffset < fileSize &&
            metadataSize <= fileSize - headerSize
    }

    /** Read-only round-trip contract: unknown serialized bytes are returned unchanged. */
    fun preserveUnmodified(original: ByteArray): ByteArray = original.copyOf()
}

internal object UnityArchiveHeaderParser {
    private val signatures = listOf("UnityFS", "UnityRaw", "UnityWeb")

    fun detect(prefix: ByteArray): String? {
        if (prefix.isEmpty()) return null
        val text = prefix.take(16).takeWhile { it.toInt() != 0 }
            .map { (it.toInt() and 0xff).toChar() }.joinToString("")
        return signatures.firstOrNull { text.startsWith(it) }
    }
}

internal data class UnityAssetInventoryItem(
    val path: String,
    val sizeBytes: Long,
    val kind: UnityAssetKind,
    val support: UnityAssetSupport,
    val detail: String
)

internal data class UnityAssetInventory(
    val totalUnityFiles: Int,
    val streamingTextCandidates: Int,
    val serializedFiles: Int,
    val bundleFiles: Int,
    val externalStreams: Int,
    val preservedUnsupported: Int,
    val items: List<UnityAssetInventoryItem>,
    val notes: List<String>
) {
    val report: String
        get() = "Unity assets: $totalUnityFiles • نصوص StreamingAssets: $streamingTextCandidates • SerializedFiles: $serializedFiles • Bundles: $bundleFiles • ملفات جانبية: $externalStreams • غير مدعوم/محفوظ: $preservedUnsupported"
}

/**
 * Read-only inventory for Unity content embedded in an APK. No Unity asset payload
 * is rewritten. Only a bounded prefix is read for format identification.
 */
internal object UnityAssetInventoryScanner {
    private const val MAX_PREFIX_BYTES = 64

    fun inspect(apk: File): UnityAssetInventory {
        val items = mutableListOf<UnityAssetInventoryItem>()
        val notes = linkedSetOf<String>()
        ZipFile(apk).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory) continue
                val path = entry.name.replace('\\', '/')
                val lower = path.lowercase()
                val inUnityData = lower.startsWith("assets/bin/data/")
                val extension = lower.substringAfterLast('.', "")
                val isStreaming = lower.startsWith("assets/bin/data/streamingassets/")
                val isSerializedName = extension in setOf("assets", "sharedassets") ||
                    lower.endsWith("/globalgamemanagers") ||
                    Regex("(^|/)level[0-9]+$").containsMatchIn(lower)
                val isExternal = extension in setOf("ress", "resource")
                val isManaged = lower.endsWith(".dll") &&
                    lower.startsWith("assets/bin/data/managed/")
                if (!inUnityData && !isExternal && !isSerializedName) continue

                val prefix = try {
                    zip.getInputStream(entry).use { input ->
                        val buffer = ByteArray(MAX_PREFIX_BYTES)
                        var offset = 0
                        while (offset < buffer.size) {
                            val count = input.read(buffer, offset, buffer.size - offset)
                            if (count < 0) break
                            offset += count
                        }
                        buffer.copyOf(offset)
                    }
                } catch (_: Exception) {
                    notes += "تعذر قراءة ترويسة أحد ملفات Unity؛ تم تصنيفه للحفظ دون تعديل."
                    byteArrayOf()
                }

                val size = entry.size.coerceAtLeast(0)
                val item = when {
                    isExternal -> UnityAssetInventoryItem(
                        path, size, UnityAssetKind.EXTERNAL_BINARY_STREAM,
                        UnityAssetSupport.PRESERVE_AS_EXTERNAL_DATA,
                        "بيانات ثنائية جانبية؛ قد ترتبط بكائنات داخل SerializedFile"
                    )
                    isManaged -> UnityAssetInventoryItem(
                        path, size, UnityAssetKind.MANAGED_ASSEMBLY,
                        UnityAssetSupport.PRESERVE_UNSUPPORTED,
                        "Assembly/DLL محفوظ كما هو؛ لا يوجد تعديل CIL"
                    )
                    isStreaming && extension in setOf("json", "txt", "properties", "strings") ->
                        UnityAssetInventoryItem(
                            path, size, UnityAssetKind.STREAMING_TEXT,
                            UnityAssetSupport.TEXT_CANDIDATE_READ_ONLY,
                            "مرشح نصي؛ يحتاج تحقق الترميز والبنية قبل الترجمة"
                        )
                    isSerializedName -> {
                        val header = UnitySerializedFileHeaderParser.parse(prefix, size)
                        if (header != null) UnityAssetInventoryItem(
                            path, size, UnityAssetKind.SERIALIZED_FILE,
                            UnityAssetSupport.HEADER_PARSED_READ_ONLY,
                            "SerializedFile header v${header.formatVersion}, endian=${header.endian ?: "legacy"}؛ لم يتم تحليل TypeTree أو الكائنات"
                        ) else UnityAssetInventoryItem(
                            path, size, UnityAssetKind.SERIALIZED_FILE,
                            UnityAssetSupport.PRESERVE_UNSUPPORTED,
                            "اسم ملف SerializedFile محتمل لكن الترويسة غير مؤكدة؛ محفوظ دون تعديل"
                        )
                    }
                    UnityArchiveHeaderParser.detect(prefix) != null ->
                        UnityAssetInventoryItem(
                            path, size, UnityAssetKind.UNITY_ARCHIVE,
                            UnityAssetSupport.BUNDLE_HEADER_ONLY_READ_ONLY,
                            "توقيع ${UnityArchiveHeaderParser.detect(prefix)}؛ محتويات Bundle لم تُفك"
                        )
                    else -> UnityAssetInventoryItem(
                        path, size, UnityAssetKind.UNKNOWN_BINARY,
                        UnityAssetSupport.PRESERVE_UNSUPPORTED,
                        "محتوى Unity غير معروف؛ محفوظ دون تعديل"
                    )
                }
                items += item
            }
        }
        if (items.any { it.kind == UnityAssetKind.SERIALIZED_FILE }) {
            notes += "فحص SerializedFile هنا يقرأ الترويسة فقط؛ لا يقرأ TypeTrees أو object table أو مراجع الكائنات."
        }
        if (items.any { it.kind == UnityAssetKind.UNITY_ARCHIVE }) {
            notes += "تم التعرف على توقيع AssetBundle فقط؛ الضغط الداخلي وكتل البيانات وملفات CAB تحتاج محلل Archive مستقل."
        }
        notes += "كل ملفات الموارد الثنائية وDLL تظل للقراءة فقط وتُحفظ كما هي في مسار التعريب الحالي."
        return UnityAssetInventory(
            totalUnityFiles = items.size,
            streamingTextCandidates = items.count { it.kind == UnityAssetKind.STREAMING_TEXT },
            serializedFiles = items.count { it.kind == UnityAssetKind.SERIALIZED_FILE },
            bundleFiles = items.count { it.kind == UnityAssetKind.UNITY_ARCHIVE },
            externalStreams = items.count { it.kind == UnityAssetKind.EXTERNAL_BINARY_STREAM },
            preservedUnsupported = items.count { it.support == UnityAssetSupport.PRESERVE_UNSUPPORTED },
            items = items.take(100),
            notes = notes.toList()
        )
    }
}


internal data class UnityAssetIntegrityReport(
    val filesVerified: Int,
    val totalBytesVerified: Long
)

/**
 * Verifies that Unity binary assets and managed assemblies remain byte-identical
 * after APK repackaging. The original APK is the source backup; no resource payload
 * is modified by the current Mono pipeline.
 */
internal object UnityAssetIntegrityVerifier {
    fun verifyUnchanged(inputApk: File, outputApk: File): UnityAssetIntegrityReport {
        var checked = 0
        var bytes = 0L
        ZipFile(inputApk).use { source ->
            ZipFile(outputApk).use { output ->
                val entries = source.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.isDirectory || !isProtected(entry.name)) continue
                    val outputEntry = output.getEntry(entry.name)
                        ?: error("Unity resource disappeared during APK rebuild: ${entry.name}")
                    val sourceDigest = source.getInputStream(entry).use(::sha256)
                    val outputDigest = output.getInputStream(outputEntry).use(::sha256)
                    require(sourceDigest.contentEquals(outputDigest)) {
                        "Unity resource bytes changed during APK rebuild: ${entry.name}"
                    }
                    checked++
                    bytes += entry.size.coerceAtLeast(0)
                }
            }
        }
        return UnityAssetIntegrityReport(checked, bytes)
    }

    private fun isProtected(path: String): Boolean {
        val normalized = path.replace('\\\\', '/').lowercase()
        val inUnityData = normalized.startsWith("assets/bin/data/")
        val extension = normalized.substringAfterLast('.', "")
        return (inUnityData && (
            extension in setOf("assets", "sharedassets", "ress", "resource", "bundle", "unity3d") ||
                normalized.startsWith("assets/bin/data/managed/") && extension == "dll" ||
                normalized.endsWith("/globalgamemanagers") ||
                Regex("(^|/)level[0-9]+$").containsMatchIn(normalized)
            )) || extension in setOf("ress", "resource")
    }

    private fun sha256(input: java.io.InputStream): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        return digest.digest()
    }
}
