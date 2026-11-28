package com.omega.arabify

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import java.nio.file.Files

internal data class UnityTextAssetContent(
    val name: String,
    val script: String,
    val pathName: String?
)

/**
 * Strict read-only reader for Unity's built-in TextAsset (class ID 49).
 *
 * It accepts only a complete, resolved TypeTree with the known string layout.
 * Unknown fields, stripped TypeTrees, binary/non-UTF-8 payloads and oversized
 * objects are rejected. No source bytes are ever written.
 */
internal object UnityTextAssetReader {
    private const val TEXT_ASSET_CLASS_ID = 49
    private const val ALIGN_BYTES_FLAG = 0x4000
    private const val MAX_OBJECT_BYTES = 4L * 1024L * 1024L
    private const val MAX_STRING_BYTES = 4 * 1024 * 1024

    internal data class StringField(
        val name: String,
        val alignBytes: Boolean
    )

    fun isTextAssetObject(
        header: UnitySerializedHeader,
        typeTable: UnitySerializedTypeTable,
        objectInfo: UnitySerializedObjectInfo
    ): Boolean = findTypeRecord(header, typeTable, objectInfo)?.classId == TEXT_ASSET_CLASS_ID

    fun read(
        file: File,
        header: UnitySerializedHeader,
        typeTable: UnitySerializedTypeTable,
        objectInfo: UnitySerializedObjectInfo,
        maxObjectBytes: Long = 1024L * 1024L,
        maxStringBytes: Int = 1024 * 1024
    ): UnityTextAssetContent? {
        return runCatching {
            require(file.isFile && !Files.isSymbolicLink(file.toPath()))
            require(maxObjectBytes in 1L..MAX_OBJECT_BYTES)
            require(maxStringBytes in 0..MAX_STRING_BYTES)
            require(header.endian == "little" || header.endian == "big")
            require(header.formatVersion in 9..100)
            require(header.headerSize >= 16)
            require(header.metadataSize > 0L)
            require(header.fileSize == file.length())
            require(header.dataOffset >= header.headerSize.toLong())
            require(header.dataOffset <= header.fileSize)
            require(header.metadataSize <= header.fileSize - header.headerSize.toLong())
            require(header.dataOffset >= header.headerSize.toLong() + header.metadataSize)

            val typeRecord = findTypeRecord(header, typeTable, objectInfo)
                ?: error("TextAsset type record is unavailable")
            require(typeRecord.classId == TEXT_ASSET_CLASS_ID)
            val tree = typeRecord.typeTree ?: error("TextAsset TypeTree is unavailable")
            require(tree.unresolvedCommonStringReferences == 0)

            val fields = parseStringFields(tree)
                ?: error("TextAsset TypeTree does not match the supported string schema")
            require(objectInfo.byteSize in 1L..maxObjectBytes)
            require(objectInfo.byteSize <= Int.MAX_VALUE.toLong())
            require(objectInfo.byteStart >= 0L)
            val absoluteOffset = Math.addExact(header.dataOffset, objectInfo.byteStart)
            require(absoluteOffset == objectInfo.absoluteDataOffset)
            require(absoluteOffset <= header.fileSize)
            require(objectInfo.byteSize <= header.fileSize - absoluteOffset)

            val payload = ByteArray(objectInfo.byteSize.toInt())
            RandomAccessFile(file, "r").use { source ->
                require(source.length() == header.fileSize)
                source.seek(absoluteOffset)
                source.readFully(payload)
            }

            val order = if (header.endian == "little") {
                ByteOrder.LITTLE_ENDIAN
            } else {
                ByteOrder.BIG_ENDIAN
            }
            val input = ByteBuffer.wrap(payload).order(order)
            val values = LinkedHashMap<String, String>(fields.size)
            fields.forEach { field ->
                require(input.remaining() >= 4)
                val length = input.int
                require(length in 0..maxStringBytes)
                require(length <= input.remaining())
                val bytes = ByteArray(length)
                input.get(bytes)
                val value = decodeUtf8(bytes)
                    ?: error("TextAsset string is not valid UTF-8")
                require(isPlainText(value)) { "TextAsset payload contains binary/control data" }
                values[field.name] = value

                if (field.alignBytes) {
                    val absolutePosition = Math.addExact(
                        absoluteOffset,
                        input.position().toLong()
                    )
                    val remainder = (absolutePosition % 4L).toInt()
                    val padding = if (remainder == 0) 0 else 4 - remainder
                    require(padding <= input.remaining())
                    input.position(input.position() + padding)
                }
            }
            require(!input.hasRemaining()) { "TextAsset payload has unparsed trailing bytes" }

            UnityTextAssetContent(
                name = values["m_Name"] ?: error("TextAsset name is missing"),
                script = values["m_Script"] ?: error("TextAsset script is missing"),
                pathName = values["m_PathName"]
            )
        }.getOrNull()
    }

    internal fun findTypeRecord(
        header: UnitySerializedHeader,
        typeTable: UnitySerializedTypeTable,
        objectInfo: UnitySerializedObjectInfo
    ): UnitySerializedTypeRecord? {
        return if (header.formatVersion >= 16) {
            typeTable.records.getOrNull(objectInfo.typeId)
        } else {
            typeTable.records.singleOrNull { it.classId == objectInfo.classId }
        }
    }

    internal fun parseStringFields(tree: UnityTypeTree): List<StringField>? {
        val nodes = tree.nodes
        if (nodes.size < 7) return null
        val root = nodes.first()
        if (root.level != 0 || root.typeName != "TextAsset") return null

        val fields = mutableListOf<StringField>()
        var index = 1
        while (index < nodes.size) {
            val field = nodes[index]
            if (field.level != 1 || field.typeName != "string" || field.byteSize != -1) return null
            val end = index + 1
            var subtreeEnd = end
            while (subtreeEnd < nodes.size && nodes[subtreeEnd].level > field.level) {
                subtreeEnd++
            }
            val children = nodes.subList(end, subtreeEnd)
            if (children.size != 3) return null
            val array = children[0]
            val size = children[1]
            val data = children[2]
            if (array.level != 2 || array.typeName != "Array" ||
                array.fieldName != "Array" || array.byteSize != -1 || array.typeFlags != 1 ||
                (array.metaFlags and ALIGN_BYTES_FLAG) == 0
            ) return null
            if (size.level != 3 || size.typeName != "int" ||
                size.fieldName != "size" || size.byteSize != 4
            ) return null
            if (data.level != 3 || data.typeName != "char" ||
                data.fieldName != "data" || data.byteSize != 1
            ) return null
            val fieldName = field.fieldName ?: return null
            if (fieldName !in setOf("m_Name", "m_Script", "m_PathName")) return null
            fields += StringField(fieldName, alignBytes = true)
            index = subtreeEnd
        }

        val names = fields.map { it.name }
        if (names != listOf("m_Name", "m_Script") &&
            names != listOf("m_Name", "m_Script", "m_PathName")
        ) return null
        return fields
    }

    private fun decodeUtf8(bytes: ByteArray): String? = runCatching {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    }.getOrNull()

    private fun isPlainText(value: String): Boolean = value.none { character ->
        character == '\u0000' ||
            (character.isISOControl() && character !in setOf('\n', '\r', '\t'))
    }
}
