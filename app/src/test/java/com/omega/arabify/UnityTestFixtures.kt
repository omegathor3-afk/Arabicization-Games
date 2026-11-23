package com.omega.arabify

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * Shared hand-built Unity binary fixtures for integration tests. Both builders
 * follow the same recipes proven in the unit tests: SerializedFile v17
 * little-endian with a full TextAsset type tree, and UnityFS v7 with an
 * LZ4-compressed block info and mixed raw/LZ4 storage blocks.
 */
internal object UnityTestFixtures {

    data class TextAssetSpec(val pathId: Long, val name: String, val script: String)
    data class NodeSpec(val path: String, val flags: Long, val data: ByteArray)

    private fun le(v: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()
    private fun leShort(v: Int) = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v.toShort()).array()
    private fun leLong(v: Long) = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v).array()
    private fun be32(v: Long) = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(v.toInt()).array()
    private fun be64(v: Long) = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(v).array()

    private val lz4 = net.jpountz.lz4.LZ4Factory.fastestInstance().fastCompressor()

    private fun lz4Compress(raw: ByteArray): ByteArray {
        val max = lz4.maxCompressedLength(raw.size)
        val buffer = ByteArray(max)
        val written = lz4.compress(raw, 0, raw.size, buffer, 0, max)
        return buffer.copyOf(written)
    }

    // ---- SerializedFile v17 (little-endian) ----

    private val stringBuffer: ByteArray
    private val stringOffsets = mutableMapOf<String, Int>()

    init {
        val names = listOf(
            "", "TextAsset", "string", "m_Name", "m_Script", "Array", "int", "size", "char", "data"
        )
        val out = ByteArrayOutputStream()
        names.forEach { name ->
            stringOffsets[name] = out.size()
            out.write(name.toByteArray(Charsets.UTF_8))
            out.write(0)
        }
        stringBuffer = out.toByteArray()
    }

    private fun typeNode(level: Int, flags: Int, type: String, name: String, byteSize: Int, index: Int, meta: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(leShort(0))
        out.write(level)
        out.write(flags)
        out.write(le(stringOffsets.getValue(type)))
        out.write(le(stringOffsets.getValue(name)))
        out.write(le(byteSize))
        out.write(le(index))
        out.write(le(meta))
        return out.toByteArray()
    }

    private fun typeTreeBlob(): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(le(9))
        out.write(le(stringBuffer.size))
        out.write(typeNode(0, 0, "TextAsset", "", 1, 0, 0))
        out.write(typeNode(1, 0, "string", "m_Name", -1, 1, 0))
        out.write(typeNode(2, 1, "Array", "Array", -1, 2, 0x4000))
        out.write(typeNode(3, 0, "int", "size", 4, 3, 0))
        out.write(typeNode(3, 0, "char", "data", 1, 4, 0))
        out.write(typeNode(1, 0, "string", "m_Script", -1, 5, 0))
        out.write(typeNode(2, 1, "Array", "Array", -1, 6, 0x4000))
        out.write(typeNode(3, 0, "int", "size", 4, 7, 0))
        out.write(typeNode(3, 0, "char", "data", 1, 8, 0))
        out.write(stringBuffer)
        return out.toByteArray()
    }

    private fun objectPayload(name: String, script: String, absoluteStart: Long): ByteArray {
        val out = ByteArrayOutputStream()
        fun field(text: String) {
            val bytes = text.toByteArray(Charsets.UTF_8)
            out.write(le(bytes.size))
            out.write(bytes)
            val pad = ((4L - ((absoluteStart + out.size()) % 4L)) % 4L).toInt()
            repeat(pad) { out.write(0) }
        }
        field(name)
        field(script)
        return out.toByteArray()
    }

    /** Builds a SerializedFile v17 with one TextAsset per spec, type tree included. */
    fun serializedFileV17(objects: List<TextAssetSpec>): ByteArray {
        val metadata = ByteArrayOutputStream()
        metadata.write("2019.4.40f1".toByteArray(Charsets.US_ASCII))
        metadata.write(0)
        metadata.write(le(13))
        metadata.write(1)
        metadata.write(le(1))
        metadata.write(le(49)) // classId: TextAsset
        metadata.write(0)
        metadata.write(leShort(-1))
        metadata.write(ByteArray(16))
        metadata.write(typeTreeBlob())

        val headerSize = 20
        fun buildTable(byteStarts: List<Long>, byteSizes: List<Long>): ByteArray {
            val table = ByteArrayOutputStream()
            table.write(le(byteStarts.size))
            byteStarts.forEachIndexed { index, byteStart ->
                val pad = ((4L - ((headerSize + metadata.size() + table.size()) % 4L)) % 4L).toInt()
                repeat(pad) { table.write(0) }
                table.write(leLong(objects[index].pathId))
                table.write(le(byteStart.toInt()))
                table.write(le(byteSizes[index].toInt()))
                table.write(le(0))
            }
            return table.toByteArray()
        }
        val zeroes = objects.map { 0L }
        val provisionalTable = buildTable(zeroes, zeroes)
        // Trailing metadata (script refs + externals + empty userInformation)
        // then zero padding: real SerializedFiles align dataOffset to 4.
        val trailingBytes = 9
        val rawDataOffset = headerSize + metadata.size() + provisionalTable.size.toLong() + trailingBytes
        val dataOffset = (rawDataOffset + 3L) / 4L * 4L

        val payloads = mutableListOf<ByteArray>()
        var cursor = dataOffset
        objects.forEach { spec ->
            val payload = objectPayload(spec.name, spec.script, cursor)
            payloads.add(payload)
            cursor += payload.size
        }
        val starts = mutableListOf<Long>()
        var offset = 0L
        payloads.forEach { starts.add(offset); offset += it.size }
        val tableBytes = buildTable(starts, payloads.map { it.size.toLong() })
        require(tableBytes.size == provisionalTable.size) { "fixture table size drifted" }
        metadata.write(tableBytes)
        metadata.write(le(0)) // script references
        metadata.write(le(0)) // externals
        metadata.write(0) // userInformation: empty cstring
        repeat((dataOffset - headerSize - metadata.size()).toInt()) { metadata.write(0) }

        val metadataBytes = metadata.toByteArray()
        val fileSize = dataOffset + offset
        val out = ByteArrayOutputStream()
        out.write(be32(metadataBytes.size.toLong()))
        out.write(be32(fileSize))
        out.write(be32(17))
        out.write(be32(dataOffset))
        out.write(0)
        out.write(byteArrayOf(0, 0, 0))
        out.write(metadataBytes)
        payloads.forEach { out.write(it) }
        return out.toByteArray()
    }

    /** 32-byte TypeTree node (format >= 19): same fields plus a u64 refTypeHash. */
    private fun typeNode32(level: Int, flags: Int, type: String, name: String, byteSize: Int, index: Int, meta: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(leShort(0))
        out.write(level)
        out.write(flags)
        out.write(le(stringOffsets.getValue(type)))
        out.write(le(stringOffsets.getValue(name)))
        out.write(le(byteSize))
        out.write(le(index))
        out.write(le(meta))
        out.write(leLong(0))
        return out.toByteArray()
    }

    private fun typeTreeBlob32(): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(le(9))
        out.write(le(stringBuffer.size))
        out.write(typeNode32(0, 0, "TextAsset", "", 1, 0, 0))
        out.write(typeNode32(1, 0, "string", "m_Name", -1, 1, 0))
        out.write(typeNode32(2, 1, "Array", "Array", -1, 2, 0x4000))
        out.write(typeNode32(3, 0, "int", "size", 4, 3, 0))
        out.write(typeNode32(3, 0, "char", "data", 1, 4, 0))
        out.write(typeNode32(1, 0, "string", "m_Script", -1, 5, 0))
        out.write(typeNode32(2, 1, "Array", "Array", -1, 6, 0x4000))
        out.write(typeNode32(3, 0, "int", "size", 4, 7, 0))
        out.write(typeNode32(3, 0, "char", "data", 1, 8, 0))
        out.write(stringBuffer)
        return out.toByteArray()
    }

    /**
     * Builds a SerializedFile v22 (little-endian): 48-byte header with 64-bit
     * fileSize/dataOffset, 32-byte TypeTree nodes, i64 object byteStart fields,
     * and the v20+ reference-type count in the trailing metadata.
     */
    fun serializedFileV22(objects: List<TextAssetSpec>): ByteArray {
        val metadata = ByteArrayOutputStream()
        metadata.write("2021.3.45f1".toByteArray(Charsets.US_ASCII))
        metadata.write(0)
        metadata.write(le(13))
        metadata.write(1)
        metadata.write(le(1))
        metadata.write(le(49)) // classId: TextAsset
        metadata.write(0) // isStripped
        metadata.write(leShort(-1)) // scriptTypeIndex
        metadata.write(ByteArray(16)) // oldTypeHash
        metadata.write(typeTreeBlob32())
        metadata.write(le(0)) // v21+ type dependencies

        val headerSize = 48
        fun buildTable(byteStarts: List<Long>, byteSizes: List<Long>): ByteArray {
            val table = ByteArrayOutputStream()
            table.write(le(byteStarts.size))
            byteStarts.forEachIndexed { index, byteStart ->
                val pad = ((4L - ((headerSize + metadata.size() + table.size()) % 4L)) % 4L).toInt()
                repeat(pad) { table.write(0) }
                table.write(leLong(objects[index].pathId))
                table.write(leLong(byteStart))
                table.write(le(byteSizes[index].toInt()))
                table.write(le(0)) // typeId
            }
            return table.toByteArray()
        }
        val zeroes = objects.map { 0L }
        val provisionalTable = buildTable(zeroes, zeroes)
        // Trailing metadata (script refs + externals + v20 ref types + empty
        // userInformation cstring) lives inside the metadata region, so it is
        // part of dataOffset's base.
        val trailingBytes = 13
        val rawDataOffset = headerSize + metadata.size() + provisionalTable.size.toLong() + trailingBytes
        val dataOffset = (rawDataOffset + 3L) / 4L * 4L

        val payloads = mutableListOf<ByteArray>()
        var cursor = dataOffset
        objects.forEach { spec ->
            val payload = objectPayload(spec.name, spec.script, cursor)
            payloads.add(payload)
            cursor += payload.size
        }
        val starts = mutableListOf<Long>()
        var offset = 0L
        payloads.forEach { starts.add(offset); offset += it.size }
        val tableBytes = buildTable(starts, payloads.map { it.size.toLong() })
        require(tableBytes.size == provisionalTable.size) { "fixture table size drifted" }
        metadata.write(tableBytes)
        metadata.write(le(0)) // script references
        metadata.write(le(0)) // externals
        metadata.write(le(0)) // v20+ reference types
        metadata.write(0) // userInformation: empty cstring
        repeat((dataOffset - headerSize - metadata.size()).toInt()) { metadata.write(0) }

        val metadataBytes = metadata.toByteArray()
        val fileSize = dataOffset + offset
        val out = ByteArrayOutputStream()
        out.write(be32(0)) // legacy u32 metadataSize
        out.write(be32(0)) // legacy u32 fileSize
        out.write(be32(22))
        out.write(be32(0)) // legacy u32 dataOffset
        out.write(0) // little endian
        out.write(byteArrayOf(0, 0, 0))
        out.write(be32(metadataBytes.size.toLong()))
        out.write(be64(fileSize))
        out.write(be64(dataOffset))
        out.write(be64(0))
        out.write(metadataBytes)
        payloads.forEach { out.write(it) }
        return out.toByteArray()
    }

    // ---- UnityFS v7 ----

    /** Builds a UnityFS v7 bundle: LZ4 block info, raw + LZ4 storage blocks. */
    fun unityFs(nodes: List<NodeSpec>): ByteArray {
        val stream = ByteArrayOutputStream()
        nodes.forEach { stream.write(it.data) }
        val streamBytes = stream.toByteArray()

        val split = streamBytes.size / 2
        val chunk1 = streamBytes.copyOfRange(0, split)
        val chunk2 = streamBytes.copyOfRange(split, streamBytes.size)
        val chunk2Compressed = lz4Compress(chunk2)
        val blocks = listOf(
            Triple(chunk1.size.toLong(), chunk1.size.toLong(), 0),
            Triple(chunk2.size.toLong(), chunk2Compressed.size.toLong(), 2)
        )

        val info = ByteArrayOutputStream()
        info.write(MessageDigest.getInstance("SHA-256").digest(streamBytes).copyOf(16))
        info.write(be32(blocks.size.toLong()))
        blocks.forEach { (uncompressed, compressed, flags) ->
            info.write(be32(uncompressed))
            info.write(be32(compressed))
            info.write(ByteBuffer.allocate(2).order(ByteOrder.BIG_ENDIAN).putShort(flags.toShort()).array())
        }
        info.write(be32(nodes.size.toLong()))
        var nodeOffset = 0L
        nodes.forEach { node ->
            info.write(be64(nodeOffset))
            info.write(be64(node.data.size.toLong()))
            info.write(be32(node.flags))
            info.write(node.path.toByteArray(Charsets.UTF_8))
            info.write(0)
            nodeOffset += node.data.size
        }
        val infoBytes = info.toByteArray()
        val compressedInfo = lz4Compress(infoBytes)

        val header = ByteArrayOutputStream()
        header.write("UnityFS".toByteArray(Charsets.US_ASCII))
        header.write(0)
        header.write(be32(7))
        header.write("5.x".toByteArray(Charsets.US_ASCII))
        header.write(0)
        header.write("2019.4.40f1".toByteArray(Charsets.US_ASCII))
        header.write(0)
        val headerSize = header.size() + 20

        val dataRegion = ByteArrayOutputStream()
        dataRegion.write(chunk1)
        dataRegion.write(chunk2Compressed)

        val alignedHeaderEnd = (headerSize + 15) / 16 * 16
        val declaredSize = alignedHeaderEnd + compressedInfo.size + dataRegion.size()

        header.write(be64(declaredSize.toLong()))
        header.write(be32(compressedInfo.size.toLong()))
        header.write(be32(infoBytes.size.toLong()))
        header.write(be32(2L))

        val out = ByteArrayOutputStream()
        out.write(header.toByteArray())
        repeat(alignedHeaderEnd - header.size()) { out.write(0) }
        out.write(compressedInfo)
        out.write(dataRegion.toByteArray())
        return out.toByteArray()
    }
}
