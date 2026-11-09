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

internal data class UnityArchiveHeader(
    val signature: String,
    val formatVersion: Int,
    val playerVersion: String,
    val engineVersion: String,
    val declaredSize: Long,
    val compressedBlocksInfoSize: Long,
    val uncompressedBlocksInfoSize: Long,
    val flags: Long,
    val headerSize: Int
) {
    val blocksInfoAtEnd: Boolean get() = (flags and 0x80L) != 0L
}

/**
 * Bounded UnityFS header parser. It deliberately does not decompress block info or
 * extract payloads; those operations require independent limits and validation.
 */
internal object UnityArchiveHeaderParser {
    private val signatures = listOf("UnityFS", "UnityRaw", "UnityWeb")
    private const val MAX_VERSION_STRING_BYTES = 256
    private const val MAX_BLOCK_INFO_BYTES = 64L * 1024L * 1024L

    fun detect(prefix: ByteArray): String? {
        if (prefix.isEmpty()) return null
        val text = prefix.take(16).takeWhile { it.toInt() != 0 }
            .map { (it.toInt() and 0xff).toChar() }.joinToString("")
        return signatures.firstOrNull { text.startsWith(it) }
    }

    fun parse(prefix: ByteArray, actualLength: Long): UnityArchiveHeader? {
        if (prefix.isEmpty() || actualLength <= 0L) return null
        return runCatching {
            val data = ByteBuffer.wrap(prefix).order(ByteOrder.BIG_ENDIAN)
            fun readCString(): String? {
                val start = data.position()
                var terminated = false
                while (data.hasRemaining() && data.position() - start <= MAX_VERSION_STRING_BYTES) {
                    if (data.get().toInt() == 0) {
                        terminated = true
                        break
                    }
                }
                if (!terminated) return null
                val end = data.position() - 1
                return String(prefix, start, end - start, Charsets.UTF_8)
            }

            val signature = readCString() ?: return null
            if (signature !in signatures || data.remaining() < 4) return null
            val version = data.int
            if (version !in 6..8) return null
            val playerVersion = readCString() ?: return null
            val engineVersion = readCString() ?: return null
            if (data.remaining() < 20) return null

            val declaredSize = data.long
            val compressedInfo = data.int.toLong() and 0xffffffffL
            val uncompressedInfo = data.int.toLong() and 0xffffffffL
            val flags = data.int.toLong() and 0xffffffffL
            val headerSize = data.position()

            if (declaredSize < headerSize || declaredSize > actualLength) return null
            if (compressedInfo == 0L || uncompressedInfo == 0L) return null
            if (compressedInfo > MAX_BLOCK_INFO_BYTES || uncompressedInfo > MAX_BLOCK_INFO_BYTES) return null
            if (compressedInfo > declaredSize || uncompressedInfo > declaredSize) return null

            UnityArchiveHeader(
                signature, version, playerVersion, engineVersion, declaredSize,
                compressedInfo, uncompressedInfo, flags, headerSize
            )
        }.getOrNull()
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
    private const val MAX_PREFIX_BYTES = 512

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
                    UnityArchiveHeaderParser.detect(prefix) != null -> {
                        val archiveHeader = UnityArchiveHeaderParser.parse(prefix, size)
                        UnityAssetInventoryItem(
                            path, size, UnityAssetKind.UNITY_ARCHIVE,
                            UnityAssetSupport.BUNDLE_HEADER_ONLY_READ_ONLY,
                            if (archiveHeader != null) {
                                "${archiveHeader.signature} v${archiveHeader.formatVersion}; Unity ${archiveHeader.engineVersion}; player ${archiveHeader.playerVersion}; blockInfo=${archiveHeader.compressedBlocksInfoSize}/${archiveHeader.uncompressedBlocksInfoSize} bytes; header parsed only, payload untouched"
                            } else {
                                "توقيع ${UnityArchiveHeaderParser.detect(prefix)}؛ ترويسة Unity Archive غير مكتملة أو خارج النطاق؛ المحتويات محفوظة دون فك"
                            }
                        )
                    }
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
        val normalized = path.replace('\\', '/').lowercase()
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


internal data class UnityStorageBlock(
    val uncompressedSize: Long,
    val compressedSize: Long,
    val flags: Int
)

internal data class UnityBundleNode(
    val offset: Long,
    val size: Long,
    val flags: Long,
    val path: String
)

internal data class UnityBlockInfo(
    val hashHex: String,
    val blocks: List<UnityStorageBlock>,
    val nodes: List<UnityBundleNode>,
    val totalUncompressedBytes: Long
)

/**
 * Parses the DECOMPRESSED UnityFS block-info buffer only.
 *
 * The caller must decompress the block-info region using a separately bounded
 * decoder. This parser never opens/extracts node payloads and never writes files.
 */
internal object UnityBlockInfoParser {
    private const val HASH_BYTES = 16
    private const val MAX_BLOCKS = 100_000
    private const val MAX_NODES = 100_000
    private const val MAX_PATH_BYTES = 4_096
    private const val MAX_INFO_BYTES = 64 * 1024 * 1024

    fun parse(
        decompressedInfo: ByteArray,
        expectedUncompressedDataBytes: Long? = null
    ): UnityBlockInfo? {
        if (decompressedInfo.size < HASH_BYTES + 8 ||
            decompressedInfo.size > MAX_INFO_BYTES
        ) return null

        return runCatching {
            val input = ByteBuffer.wrap(decompressedInfo).order(ByteOrder.BIG_ENDIAN)
            val hash = ByteArray(HASH_BYTES)
            input.get(hash)

            fun readU32(): Long = input.int.toLong() and 0xffffffffL

            val blockCount = readU32()
            require(blockCount in 1..MAX_BLOCKS.toLong())
            require(blockCount <= input.remaining().toLong() / 10L)

            val blocks = ArrayList<UnityStorageBlock>(blockCount.toInt())
            var totalUncompressed = 0L
            repeat(blockCount.toInt()) {
                val uncompressed = readU32()
                val compressed = readU32()
                val flags = input.short.toInt() and 0xffff
                require(uncompressed > 0L && compressed > 0L)
                totalUncompressed = Math.addExact(totalUncompressed, uncompressed)
                blocks += UnityStorageBlock(uncompressed, compressed, flags)
            }

            if (expectedUncompressedDataBytes != null) {
                require(expectedUncompressedDataBytes >= 0L)
                require(totalUncompressed == expectedUncompressedDataBytes)
            }

            require(input.remaining() >= 4)
            val nodeCount = readU32()
            require(nodeCount in 1..MAX_NODES.toLong())
            // Every node needs 20 fixed bytes and at least a one-byte NUL path.
            require(nodeCount <= input.remaining().toLong() / 21L)

            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            val nodes = ArrayList<UnityBundleNode>(nodeCount.toInt())
            repeat(nodeCount.toInt()) {
                val offset = input.long
                val size = input.long
                val flags = readU32()
                require(offset >= 0L && size > 0L)
                require(offset <= totalUncompressed && size <= totalUncompressed - offset)

                val pathStart = input.position()
                var terminator = -1
                while (input.hasRemaining() &&
                    input.position() - pathStart <= MAX_PATH_BYTES
                ) {
                    if (input.get().toInt() == 0) {
                        terminator = input.position() - 1
                        break
                    }
                }
                require(terminator >= pathStart)
                val pathLength = terminator - pathStart
                require(pathLength in 1..MAX_PATH_BYTES)
                val pathBytes = ByteArray(pathLength)
                val oldPosition = input.position()
                input.position(pathStart)
                input.get(pathBytes)
                input.position(oldPosition)
                val path = decoder.reset().decode(ByteBuffer.wrap(pathBytes)).toString()
                require(path.isNotBlank())
                nodes += UnityBundleNode(offset, size, flags, path)
            }
            require(!input.hasRemaining())

            UnityBlockInfo(
                hash.joinToString("") { "%02x".format(it.toInt() and 0xff) },
                blocks,
                nodes,
                totalUncompressed
            )
        }.getOrNull()
    }
}


internal enum class UnityCompressionCodec {
    NONE,
    LZ4_BLOCK,
    LZMA_ALONE
}

/** UnityFS StorageBlock compression type is stored in the low six flag bits. */
internal fun unityCompressionCodecFromFlags(flags: Long): UnityCompressionCodec? {
    if (flags !in 0L..0xffffffffL) return null
    return when (flags and 0x3fL) {
        0L -> UnityCompressionCodec.NONE
        1L -> UnityCompressionCodec.LZMA_ALONE
        2L, 3L -> UnityCompressionCodec.LZ4_BLOCK // LZ4 and LZ4HC share the block decoder.
        else -> null
    }
}

/**
 * Bounded UnityFS block decoder.
 *
 * LZ4 uses the raw block format used by UnityFS (not an LZ4 frame).
 * LZMA expects an LZMA-alone stream. Output is allocated only after the caller's
 * hard cap and the expected output size have been validated. This class decodes
 * one block at a time; callers must not accumulate decoded blocks without a
 * separate aggregate budget.
 */
internal object UnityFsBlockDecompressor {
    private const val DEFAULT_MAX_COMPRESSED_BYTES = 64L * 1024L * 1024L
    private const val DEFAULT_MAX_OUTPUT_BYTES = 64L * 1024L * 1024L
    private const val DEFAULT_MAX_LZMA_MEMORY_BYTES = 64L * 1024L * 1024L

    fun decompress(
        codec: UnityCompressionCodec,
        compressed: ByteArray,
        expectedOutputBytes: Long,
        maxOutputBytes: Long = DEFAULT_MAX_OUTPUT_BYTES,
        maxCompressedBytes: Long = DEFAULT_MAX_COMPRESSED_BYTES,
        maxLzmaMemoryBytes: Long = DEFAULT_MAX_LZMA_MEMORY_BYTES
    ): ByteArray {
        require(compressed.isNotEmpty()) { "Compressed UnityFS block is empty" }
        require(maxOutputBytes in 1..Int.MAX_VALUE.toLong()) { "Invalid output budget" }
        require(maxCompressedBytes > 0L && maxLzmaMemoryBytes >= 1024L) { "Invalid decoder budget; LZMA memory budget must be at least 1 KiB" }
        require(compressed.size.toLong() <= maxCompressedBytes) { "Compressed block exceeds budget" }
        require(expectedOutputBytes in 1..maxOutputBytes) { "Uncompressed block exceeds budget" }
        val expected = expectedOutputBytes.toInt()

        return when (codec) {
            UnityCompressionCodec.NONE -> {
                require(compressed.size == expected) { "Uncompressed UnityFS block size mismatch" }
                compressed.copyOf()
            }
            UnityCompressionCodec.LZ4_BLOCK -> {
                val output = ByteArray(expected)
                val written = net.jpountz.lz4.LZ4Factory.fastestInstance()
                    .safeDecompressor()
                    .decompress(compressed, 0, compressed.size, output, 0, output.size)
                require(written == expected) { "LZ4 output size does not match UnityFS block metadata" }
                output
            }
            UnityCompressionCodec.LZMA_ALONE -> {
                val memoryLimitKiB = (maxLzmaMemoryBytes / 1024L)
                    .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                val output = ByteArray(expected)
                val input = java.io.ByteArrayInputStream(compressed)
                org.tukaani.xz.LZMAInputStream(input, memoryLimitKiB).use { decoder ->
                    var offset = 0
                    while (offset < expected) {
                        val count = decoder.read(output, offset, expected - offset)
                        require(count >= 0) { "LZMA stream ended before expected block size" }
                        require(count > 0) { "LZMA decoder made no progress" }
                        offset += count
                    }
                    require(decoder.read() == -1) { "LZMA stream expands beyond declared block size" }
                }
                output
            }
        }
    }
}
