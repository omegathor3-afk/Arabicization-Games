package com.omega.arabify

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

internal data class UnityFsRewriteReport(
    val nodePath: String,
    val oldNodeBytes: Long,
    val newNodeBytes: Long,
    val deltaBytes: Long,
    val shiftedNodeCount: Int,
    val blockCount: Int,
    val newFileBytes: Long
)

/**
 * Bounded UnityFS writer: replaces exactly one directory node (for example a
 * patched SerializedFile) inside a UnityFS bundle and rebuilds the container.
 *
 * Safety properties:
 * - The decompressed block stream is rebuilt with only the target node's byte
 *   range changed; every other node's bytes are re-compared after writing.
 * - Storage blocks are re-chunked and compressed with the same LZ4 block format
 *   the decoder reads (raw lz4-java blocks); blocks that do not compress are
 *   stored uncompressed, which Unity accepts.
 * - The block info keeps its original codec only for NONE/LZ4; LZMA block-info
 *   bundles are refused rather than recompressed with an untested encoder.
 * - The rebuilt bundle is written to a sibling temporary file, decoded and
 *   byte-verified there, then atomically published. A failed write/verification
 *   never deletes or truncates an existing destination.
 *
 * The 16-byte block-info hash slot is written as truncated SHA-256 of the
 * decompressed stream. Unity uses this field as a cache hint; neither the
 * engine loader nor our own parser validates it, and no engine-side
 * integrity check depends on it.
 */
internal object UnityFsBundleRewriter {
    private const val BLOCK_CHUNK_BYTES = 4 * 1024 * 1024
    private const val MAX_NODE_BYTES = 256L * 1024L * 1024L

    fun rewriteNode(
        input: File,
        output: File,
        nodePath: String,
        replacement: ByteArray,
        limits: UnityFsDecodeLimits = UnityFsDecodeLimits(),
        maxHeapBytes: Long = Runtime.getRuntime().maxMemory()
    ): UnityFsRewriteReport {
        require(input.isFile) { "ملف UnityFS الأصلي غير موجود." }
        require(input.canonicalFile != output.canonicalFile) {
            "لا يمكن الكتابة فوق حزمة UnityFS الأصلية نفسها، حتى عبر مسار بديل."
        }
        require(replacement.isNotEmpty()) { "بيانات العقدة البديلة فارغة." }
        require(replacement.size.toLong() <= MAX_NODE_BYTES) { "بيانات العقدة البديلة تتجاوز الحد الآمن." }
        require(nodePath.isNotBlank() && nodePath.length <= 4096 && nodePath.none { it.isISOControl() }) {
            "مسار العقدة داخل الحزمة غير صالح."
        }

        // Keep the multi-array rewrite/verification pipeline within a conservative
        // fraction of the Android app heap. This is a safety gate, not a substitute
        // for the still-pending file-backed streaming rewrite.
        val rewriteLimits = UnityFsRewriteMemoryBudget.constrain(limits, maxHeapBytes)
        // Parse header and block info exactly like the decoder does.
        val (header, rawHeader, blockInfo, originalStream) = readBundle(input, rewriteLimits)
        val target = blockInfo.nodes.singleOrNull { it.path == nodePath }
            ?: error("لم يتم العثور على العقدة المطلوبة داخل الحزمة: $nodePath")
        require(target.size <= Int.MAX_VALUE.toLong())
        val delta = replacement.size.toLong() - target.size
        val newStreamSize = Math.addExact(originalStream.size.toLong(), delta)
        require(
            newStreamSize in 1L..rewriteLimits.maxTotalDecodedBytes &&
                newStreamSize <= Int.MAX_VALUE.toLong()
        ) {
            "حجم البيانات المفكوكة بعد إعادة البناء يتجاوز الحد الآمن للذاكرة أو حد الفهرسة."
        }

        // Rebuild the decompressed stream with only the target range replaced.
        val newStream = ByteArray(newStreamSize.toInt())
        System.arraycopy(originalStream, 0, newStream, 0, target.offset.toInt())
        System.arraycopy(replacement, 0, newStream, target.offset.toInt(), replacement.size)
        val tailStart = (target.offset + target.size).toInt()
        System.arraycopy(
            originalStream, tailStart,
            newStream, target.offset.toInt() + replacement.size,
            originalStream.size - tailStart
        )

        // New directory: shift every node after the target by the delta.
        val newNodes = blockInfo.nodes.map { node ->
            when {
                node.path == nodePath -> node.copy(size = replacement.size.toLong())
                node.offset > target.offset -> node.copy(offset = node.offset + delta)
                else -> node
            }
        }
        require(UnityBundleNodeSafety.validateNodeLayout(newNodes, newStream.size.toLong())) {
            "تخطيط العقد الجديد داخل الحزمة غير آمن."
        }

        // Re-chunk and compress storage blocks.
        val lz4Factory = net.jpountz.lz4.LZ4Factory.fastestInstance()
        val compressor = lz4Factory.fastCompressor()
        val newBlocks = mutableListOf<UnityStorageBlock>()
        val compressedBlocks = mutableListOf<ByteArray>()
        var cursor = 0
        while (cursor < newStream.size) {
            val chunkSize = minOf(BLOCK_CHUNK_BYTES, newStream.size - cursor)
            val chunk = newStream.copyOfRange(cursor, cursor + chunkSize)
            val maxCompressed = compressor.maxCompressedLength(chunkSize)
            val compressed = ByteArray(maxCompressed)
            val written = compressor.compress(chunk, 0, chunkSize, compressed, 0, maxCompressed)
            if (written >= chunkSize) {
                newBlocks += UnityStorageBlock(chunkSize.toLong(), chunkSize.toLong(), 0)
                compressedBlocks += chunk
            } else {
                newBlocks += UnityStorageBlock(chunkSize.toLong(), written.toLong(), 2)
                compressedBlocks += compressed.copyOf(written)
            }
            cursor += chunkSize
        }

        // Rebuild the block-info buffer (big-endian, same layout the parser reads).
        // The 16-byte hash slot is a Unity cache hint: the engine loader does not
        // validate it and neither does our strict parser. SHA-256 truncated to the
        // 16-byte field width keeps the slot deterministic without MD5.
        val hash = MessageDigest.getInstance("SHA-256").digest(newStream).copyOf(16)
        val infoRaw = ByteArrayOutputStream()
        fun be32(value: Long) {
            require(value in 1..0xffffffffL)
            infoRaw.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(value.toInt()).array())
        }
        infoRaw.write(hash)
        be32(newBlocks.size.toLong())
        newBlocks.forEach { block ->
            be32(block.uncompressedSize)
            be32(block.compressedSize)
            infoRaw.write(ByteBuffer.allocate(2).order(ByteOrder.BIG_ENDIAN).putShort(block.flags.toShort()).array())
        }
        be32(newNodes.size.toLong())
        newNodes.forEach { node ->
            infoRaw.write(ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(node.offset).array())
            infoRaw.write(ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(node.size).array())
            infoRaw.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(node.flags.toInt()).array())
            infoRaw.write(node.path.toByteArray(Charsets.UTF_8))
            infoRaw.write(0)
        }
        val infoBytes = infoRaw.toByteArray()

        // Compress the block info with the original codec where we support writing it.
        val infoCodec = unityCompressionCodecFromFlags(header.flags)
            ?: error("ترميز بيانات الحزمة غير مدعوم.")
        val compressedInfo = when (infoCodec) {
            UnityCompressionCodec.NONE -> infoBytes
            UnityCompressionCodec.LZ4_BLOCK -> {
                val max = compressor.maxCompressedLength(infoBytes.size)
                val buffer = ByteArray(max)
                val written = compressor.compress(infoBytes, 0, infoBytes.size, buffer, 0, max)
                buffer.copyOf(written)
            }
            UnityCompressionCodec.LZMA_ALONE ->
                error("حزم UnityFS بترميز LZMA لبيانات الفهرس غير مدعومة لإعادة البناء بعد.")
        }

        // Assemble the file in the original layout (block info at start or at end).
        val alignedHeaderEnd = if (UnityFsBundleDecoder.unityFsNeedsHeaderAlignment(header)) {
            UnityFsBundleDecoder.unityFsAlign16(header.headerSize.toLong())
        } else {
            header.headerSize.toLong()
        }
        val fileOut = ByteArrayOutputStream()
        fileOut.write(rawHeader)
        repeat((alignedHeaderEnd - header.headerSize).toInt()) { fileOut.write(0) }
        val dataRegion = ByteArrayOutputStream()
        compressedBlocks.forEach { dataRegion.write(it) }
        val padDataStart = (header.flags and UnityFsBundleDecoder.FLAG_BLOCK_INFO_NEEDS_PADDING_AT_START) != 0L
        if (header.blocksInfoAtEnd) {
            fileOut.write(dataRegion.toByteArray())
            fileOut.write(compressedInfo)
        } else {
            fileOut.write(compressedInfo)
            if (padDataStart) {
                val padding = (16L - (fileOut.size() % 16L)) % 16L
                repeat(padding.toInt()) { fileOut.write(0) }
            }
            fileOut.write(dataRegion.toByteArray())
        }
        val finalBytes = fileOut.toByteArray()

        // Patch declaredSize / info sizes into the header copy (last 20 bytes).
        val patchedHeader = rawHeader.copyOf()
        val headerBuffer = ByteBuffer.wrap(patchedHeader).order(ByteOrder.BIG_ENDIAN)
        headerBuffer.putLong(header.headerSize - 20, finalBytes.size.toLong())
        headerBuffer.putInt(header.headerSize - 12, compressedInfo.size)
        headerBuffer.putInt(header.headerSize - 8, infoBytes.size)
        System.arraycopy(patchedHeader, 0, finalBytes, 0, header.headerSize)

        val outputParent = output.absoluteFile.parentFile
            ?: error("مجلد الملف الناتج غير صالح.")
        require(outputParent.isDirectory || outputParent.mkdirs()) {
            "تعذر تجهيز مجلد الملف الناتج."
        }
        val temporaryOutput = Files.createTempFile(
            outputParent.toPath(), ".arabify-unityfs-", ".tmp"
        ).toFile()
        try {
            temporaryOutput.writeBytes(finalBytes)
            val report = UnityFsRewriteReport(
                nodePath = nodePath,
                oldNodeBytes = target.size,
                newNodeBytes = replacement.size.toLong(),
                deltaBytes = delta,
                shiftedNodeCount = blockInfo.nodes.count { it.offset > target.offset },
                blockCount = newBlocks.size,
                newFileBytes = finalBytes.size.toLong()
            )
            verifyRewrittenBundle(
                temporaryOutput, rewriteLimits, blockInfo, originalStream, nodePath, replacement, newStream
            )
            Files.move(
                temporaryOutput.toPath(),
                output.absoluteFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
            return report
        } finally {
            temporaryOutput.delete()
        }
    }

    private fun readBundle(
        input: File,
        limits: UnityFsDecodeLimits
    ): BundleData {
        val prefix = ByteArray(minOf(input.length(), 1024L).toInt())
        RandomAccessFile(input, "r").use { it.seek(0L); it.readFully(prefix) }
        val header = UnityArchiveHeaderParser.parse(prefix, input.length())
            ?: error("ترويسة UnityFS غير صالحة.")
        require(header.signature == "UnityFS") { "إعادة البناء مدعومة لحزم UnityFS فقط." }
        val rawHeader = prefix.copyOf(header.headerSize)

        val streamOut = ByteArrayOutputStream()
        val result = UnityFsBundleDecoder.decode(input, streamOut, limits)
            ?: error("تعذر فك ترميز حزمة UnityFS الأصلية؛ لن نعيد بناء ما لا نستطيع قراءته.")
        return BundleData(header, rawHeader, result.blockInfo, streamOut.toByteArray())
    }

    private data class BundleData(
        val header: UnityArchiveHeader,
        val rawHeader: ByteArray,
        val blockInfo: UnityBlockInfo,
        val stream: ByteArray
    )

    private fun verifyRewrittenBundle(
        output: File,
        limits: UnityFsDecodeLimits,
        originalInfo: UnityBlockInfo,
        originalStream: ByteArray,
        nodePath: String,
        replacement: ByteArray,
        expectedStream: ByteArray
    ) {
        val streamOut = ByteArrayOutputStream()
        val decoded = UnityFsBundleDecoder.decode(output, streamOut, limits)
            ?: error("فشل التحقق: الحزمة الناتجة لا تُفك بفكّاك UnityFS.")
        val actualStream = streamOut.toByteArray()
        require(actualStream.contentEquals(expectedStream)) {
            "فشل التحقق: البيانات المفكوكة من الحزمة الناتجة لا تطابق المتوقع."
        }
        require(decoded.blockInfo.nodes.size == originalInfo.nodes.size) {
            "فشل التحقق: عدد العقد تغيّر."
        }
        require(UnityBundleNodeSafety.validateNodeLayout(decoded.blockInfo.nodes, actualStream.size.toLong())) {
            "فشل التحقق: تخطيط العقد الناتج غير آمن."
        }
        // Index once: repeatedly calling single { path == ... } for every original
        // node made verification quadratic on bundles with large directory tables.
        val newNodesByPath = HashMap<String, UnityBundleNode>(decoded.blockInfo.nodes.size)
        decoded.blockInfo.nodes.forEach { node ->
            require(newNodesByPath.put(node.path, node) == null) {
                "فشل التحقق: مسار عقدة مكرر في الحزمة الناتجة."
            }
        }
        require(newNodesByPath.size == originalInfo.nodes.size) {
            "فشل التحقق: عدد مسارات العقد الناتجة لا يطابق الأصل."
        }

        val target = newNodesByPath[nodePath]
            ?: error("فشل التحقق: العقدة الهدف غابت من الحزمة الناتجة.")
        require(target.size == replacement.size.toLong()) {
            "فشل التحقق: حجم العقدة الهدف غير صحيح."
        }
        require(byteRangesEqual(actualStream, target.offset, replacement, 0L, replacement.size.toLong())) {
            "فشل التحقق: محتوى العقدة الهدف لا يطابق البيانات البديلة."
        }
        originalInfo.nodes.forEach { oldNode ->
            if (oldNode.path == nodePath) return@forEach
            val newNode = newNodesByPath[oldNode.path]
                ?: error("فشل التحقق: عقدة أصلية غابت من الحزمة الناتجة (" + oldNode.path + ").")
            require(newNode.size == oldNode.size) { "فشل التحقق: حجم عقدة غير مستهدفة تغيّر." }
            require(byteRangesEqual(originalStream, oldNode.offset, actualStream, newNode.offset, oldNode.size)) {
                "فشل التحقق: محتوى عقدة غير مستهدفة تغيّر (" + oldNode.path + ")."
            }
        }
    }

    /**
     * Compares two validated byte ranges without allocating per-node slices.
     * Offsets and lengths remain Long until bounds checks prove Int indexing safe.
     */
    private fun byteRangesEqual(
        left: ByteArray,
        leftOffset: Long,
        right: ByteArray,
        rightOffset: Long,
        length: Long
    ): Boolean {
        if (leftOffset < 0L || rightOffset < 0L || length < 0L) return false
        if (length > left.size.toLong() || length > right.size.toLong()) return false
        if (leftOffset > left.size.toLong() - length ||
            rightOffset > right.size.toLong() - length
        ) return false

        val leftStart = leftOffset.toInt()
        val rightStart = rightOffset.toInt()
        val count = length.toInt()
        for (index in 0 until count) {
            if (left[leftStart + index] != right[rightStart + index]) return false
        }
        return true
    }
}
