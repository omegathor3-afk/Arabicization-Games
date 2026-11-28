package com.omega.arabify

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UnityTextAssetReaderTest {
    private data class Fixture(
        val file: File,
        val header: UnitySerializedHeader,
        val typeTable: UnitySerializedTypeTable,
        val objectInfo: UnitySerializedObjectInfo
    )

    private fun tree(extraPathName: Boolean = false): UnityTypeTree {
        val nodes = mutableListOf(
            node(0, "TextAsset", "Base", -1, 0)
        )
        fun addString(name: String, startIndex: Int) {
            nodes += node(1, "string", name, -1, startIndex)
            nodes += node(2, "Array", "Array", -1, startIndex + 1, typeFlags = 1, metaFlags = 0x4000)
            nodes += node(3, "int", "size", 4, startIndex + 2)
            nodes += node(3, "char", "data", 1, startIndex + 3)
        }
        addString("m_Name", 1)
        addString("m_Script", 5)
        if (extraPathName) addString("m_PathName", 9)
        return UnityTypeTree(nodes, stringBufferBytes = 0, unresolvedCommonStringReferences = 0)
    }

    private fun node(
        level: Int,
        typeName: String,
        fieldName: String,
        byteSize: Int,
        index: Int,
        typeFlags: Int = 0,
        metaFlags: Int = 0
    ) = UnityTypeTreeNode(
        version = 1,
        level = level,
        typeFlags = typeFlags,
        typeStringOffset = 0,
        nameStringOffset = 0,
        byteSize = byteSize,
        index = index,
        metaFlags = metaFlags,
        refTypeHash = null,
        typeName = typeName,
        fieldName = fieldName
    )

    private fun payload(
        vararg strings: ByteArray,
        byteOrder: ByteOrder = ByteOrder.LITTLE_ENDIAN
    ): ByteArray {
        val output = ByteArrayOutputStream()
        strings.forEach { bytes ->
            val length = ByteBuffer.allocate(4).order(byteOrder)
                .putInt(bytes.size).array()
            output.write(length)
            output.write(bytes)
            while ((128L + output.size()) % 4L != 0L) output.write(0)
        }
        return output.toByteArray()
    }

    private fun fixture(
        name: ByteArray = "GameStrings".toByteArray(Charsets.UTF_8),
        script: ByteArray = "{\"start\":\"Hello\"}".toByteArray(Charsets.UTF_8),
        includePathName: Boolean = false,
        classId: Int = 49,
        unresolved: Int = 0,
        endian: String = "little"
    ): Fixture {
        val strings = if (includePathName) {
            arrayOf(name, script, "Assets/Strings.txt".toByteArray(Charsets.UTF_8))
        } else {
            arrayOf(name, script)
        }
        val payload = payload(
            *strings,
            byteOrder = if (endian == "little") ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
        )
        val dataOffset = 128L
        val fileBytes = ByteArray(dataOffset.toInt()) + payload
        val file = File.createTempFile("arabify-textasset-", ".assets")
        file.writeBytes(fileBytes)

        val typeTree = tree(includePathName).copy(unresolvedCommonStringReferences = unresolved)
        val record = UnitySerializedTypeRecord(
            classId = classId,
            isStrippedType = null,
            scriptTypeIndex = -1,
            scriptId = null,
            oldTypeHash = null,
            typeTree = typeTree,
            typeTreeInfoHandler = null,
            typeDependencies = emptyList(),
            referenceClassName = null,
            referenceNamespace = null,
            referenceAssemblyName = null
        )
        val header = UnitySerializedHeader(
            metadataSize = 64L,
            fileSize = fileBytes.size.toLong(),
            formatVersion = 22,
            dataOffset = dataOffset,
            endian = endian,
            headerSize = 48
        )
        val typeTable = UnitySerializedTypeTable(listOf(record), bytesConsumed = 0)
        val objectInfo = UnitySerializedObjectInfo(
            pathId = 7L,
            byteStart = 0L,
            absoluteDataOffset = dataOffset,
            byteSize = payload.size.toLong(),
            typeId = 0,
            classId = null,
            destroyed = null,
            scriptTypeIndex = null,
            stripped = null
        )
        return Fixture(file, header, typeTable, objectInfo)
    }

    @Test fun readsNameAndUtf8ScriptWithoutChangingSourceBytes() {
        val fixture = fixture()
        try {
            val original = fixture.file.readBytes()
            val result = UnityTextAssetReader.read(
                fixture.file, fixture.header, fixture.typeTable, fixture.objectInfo
            )
            assertEquals("GameStrings", result?.name)
            assertEquals("{\"start\":\"Hello\"}", result?.script)
            assertNull(result?.pathName)
            assertTrue(original.contentEquals(fixture.file.readBytes()))
        } finally {
            fixture.file.delete()
        }
    }

    @Test fun readsBigEndianPayloadUsingSerializedFileEndianness() {
        val fixture = fixture(endian = "big")
        try {
            val result = UnityTextAssetReader.read(
                fixture.file, fixture.header, fixture.typeTable, fixture.objectInfo
            )
            assertEquals("GameStrings", result?.name)
            assertEquals("{\"start\":\"Hello\"}", result?.script)
        } finally {
            fixture.file.delete()
        }
    }

    @Test fun readsOptionalPathNameWhenTheTypeTreeDeclaresIt() {
        val fixture = fixture(includePathName = true)
        try {
            val result = UnityTextAssetReader.read(
                fixture.file, fixture.header, fixture.typeTable, fixture.objectInfo
            )
            assertEquals("Assets/Strings.txt", result?.pathName)
        } finally {
            fixture.file.delete()
        }
    }

    @Test fun rejectsBinaryAndMalformedUtf8Payloads() {
        val invalidUtf8 = fixture(script = byteArrayOf(0xc3.toByte(), 0x28))
        try {
            assertNull(UnityTextAssetReader.read(
                invalidUtf8.file, invalidUtf8.header, invalidUtf8.typeTable, invalidUtf8.objectInfo
            ))
        } finally {
            invalidUtf8.file.delete()
        }

        val binary = fixture(script = byteArrayOf(0x41, 0x00, 0x42))
        try {
            assertNull(UnityTextAssetReader.read(
                binary.file, binary.header, binary.typeTable, binary.objectInfo
            ))
        } finally {
            binary.file.delete()
        }
    }

    @Test fun rejectsUnknownClassIdsMissingTypeTreesAndUnresolvedTypeNames() {
        val wrongClass = fixture(classId = 114)
        try {
            assertFalse(UnityTextAssetReader.isTextAssetObject(
                wrongClass.header, wrongClass.typeTable, wrongClass.objectInfo
            ))
            assertNull(UnityTextAssetReader.read(
                wrongClass.file, wrongClass.header, wrongClass.typeTable, wrongClass.objectInfo
            ))
        } finally {
            wrongClass.file.delete()
        }

        val unresolved = fixture(unresolved = 1)
        try {
            assertNull(UnityTextAssetReader.read(
                unresolved.file, unresolved.header, unresolved.typeTable, unresolved.objectInfo
            ))
        } finally {
            unresolved.file.delete()
        }

        val noTree = fixture()
        try {
            val record = noTree.typeTable.records.single().copy(typeTree = null)
            val table = noTree.typeTable.copy(records = listOf(record))
            assertNull(UnityTextAssetReader.read(
                noTree.file, noTree.header, table, noTree.objectInfo
            ))
        } finally {
            noTree.file.delete()
        }
    }

    @Test fun rejectsOversizedObjectsAndOutOfRangeOffsetsBeforeReading() {
        val fixture = fixture()
        try {
            assertNull(UnityTextAssetReader.read(
                fixture.file, fixture.header, fixture.typeTable, fixture.objectInfo,
                maxObjectBytes = 4L
            ))
            val outside = fixture.objectInfo.copy(
                absoluteDataOffset = fixture.header.fileSize - 1L,
                byteStart = fixture.header.fileSize - fixture.header.dataOffset - 1L
            )
            assertNull(UnityTextAssetReader.read(
                fixture.file, fixture.header, fixture.typeTable, outside
            ))
        } finally {
            fixture.file.delete()
        }
    }

    @Test fun rejectsUnsupportedSerializedHeaderVersionsAndMetadataOverlap() {
        val fixture = fixture()
        try {
            assertNull(UnityTextAssetReader.read(
                fixture.file,
                fixture.header.copy(formatVersion = 8),
                fixture.typeTable,
                fixture.objectInfo
            ))
            assertNull(UnityTextAssetReader.read(
                fixture.file,
                fixture.header.copy(formatVersion = 101),
                fixture.typeTable,
                fixture.objectInfo
            ))
            assertNull(UnityTextAssetReader.read(
                fixture.file,
                fixture.header.copy(metadataSize = 0L),
                fixture.typeTable,
                fixture.objectInfo
            ))

            val overlappingDataOffset =
                fixture.header.headerSize.toLong() + fixture.header.metadataSize - 1L
            assertNull(UnityTextAssetReader.read(
                fixture.file,
                fixture.header.copy(dataOffset = overlappingDataOffset),
                fixture.typeTable,
                fixture.objectInfo.copy(absoluteDataOffset = overlappingDataOffset)
            ))
        } finally {
            fixture.file.delete()
        }
    }

    @Test fun rejectsUnexpectedTypeTreeByteSizesForStringNodes() {
        val fixture = fixture()
        try {
            val originalNodes = fixture.typeTable.records.single().typeTree!!.nodes

            val invalidStringNodes = originalNodes.toMutableList()
            invalidStringNodes[1] = invalidStringNodes[1].copy(byteSize = 0)
            val invalidStringRecord = fixture.typeTable.records.single().copy(
                typeTree = UnityTypeTree(invalidStringNodes, 0, 0)
            )
            assertNull(UnityTextAssetReader.read(
                fixture.file,
                fixture.header,
                fixture.typeTable.copy(records = listOf(invalidStringRecord)),
                fixture.objectInfo
            ))

            val invalidArrayNodes = originalNodes.toMutableList()
            invalidArrayNodes[2] = invalidArrayNodes[2].copy(byteSize = 4)
            val invalidArrayRecord = fixture.typeTable.records.single().copy(
                typeTree = UnityTypeTree(invalidArrayNodes, 0, 0)
            )
            assertNull(UnityTextAssetReader.read(
                fixture.file,
                fixture.header,
                fixture.typeTable.copy(records = listOf(invalidArrayRecord)),
                fixture.objectInfo
            ))
        } finally {
            fixture.file.delete()
        }
    }

    @Test fun rejectsUnrecognizedTypeTreeFieldsAndWrongRootTypes() {
        val fixture = fixture()
        try {
            val unknownNodes = fixture.typeTable.records.single().typeTree!!.nodes.toMutableList()
            unknownNodes[1] = unknownNodes[1].copy(fieldName = "m_Unexpected")
            val unknownRecord = fixture.typeTable.records.single().copy(
                typeTree = UnityTypeTree(unknownNodes, 0, 0)
            )
            val unknownTable = fixture.typeTable.copy(records = listOf(unknownRecord))
            assertNull(UnityTextAssetReader.read(
                fixture.file, fixture.header, unknownTable, fixture.objectInfo
            ))

            val wrongRootNodes = fixture.typeTable.records.single().typeTree!!.nodes.toMutableList()
            wrongRootNodes[0] = wrongRootNodes[0].copy(typeName = "MonoBehaviour")
            val wrongRootRecord = fixture.typeTable.records.single().copy(
                typeTree = UnityTypeTree(wrongRootNodes, 0, 0)
            )
            val wrongRootTable = fixture.typeTable.copy(records = listOf(wrongRootRecord))
            assertNull(UnityTextAssetReader.read(
                fixture.file, fixture.header, wrongRootTable, fixture.objectInfo
            ))
        } finally {
            fixture.file.delete()
        }
    }
}
