package com.omega.arabify

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Base64
import java.util.Properties
import java.util.stream.Collectors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Reads a real Unity Editor-built TextAsset and compares it with UnityPy's
 * independent read-only oracle. The fixture is pinned and downloaded only in CI.
 */
class UnityPyTextAssetFixtureTest {
    @Test
    fun readsRealUnity2019TextAssetAndMatchesUnityPyOracleWithoutChangingSource() {
        val fixturePath = System.getenv("ARABIFY_UNITY_TEXTASSET_FIXTURE")
        val oraclePath = System.getenv("ARABIFY_UNITY_TEXTASSET_ORACLE")
        assumeTrue(
            "The pinned Unity 2019.4 TextAsset fixture is prepared by Android CI",
            !fixturePath.isNullOrBlank() && !oraclePath.isNullOrBlank()
        )

        val fixture = File(fixturePath!!)
        assertTrue("Unity TextAsset fixture is missing", fixture.isFile && fixture.length() > 0L)
        val sourceHash = sha256(fixture)
        assertEquals(
            "Pinned Unity 2019.4 fixture SHA-256 changed",
            "3d5e40d3ba950e2df737c2e5f83a5e7fc3f5311e91b90929b37186c846e16115",
            sourceHash
        )

        val oracle = Properties().apply {
            File(oraclePath!!).inputStream().use { load(it) }
        }
        val temporaryRoot = Files.createTempDirectory("arabify-unity-textasset-reference-")
        try {
            val scratch = File(temporaryRoot.toFile(), "scratch").apply {
                assertTrue("Could not create UnityFS scratch directory", mkdirs())
            }
            val output = File(temporaryRoot.toFile(), "output")
            val report = UnityFsBundleExtractor.extract(fixture, scratch, output)
            assertNotNull("Kotlin UnityFS extraction failed", report)
            val extracted = report!!

            assertEquals(7, extracted.formatVersion)
            assertEquals(oracle.required("bundle.engineVersion"), extracted.engineVersion)
            val memberCount = oracle.required("members.count").toInt()
            assertEquals(memberCount, extracted.filesExtracted)
            assertEquals(oracle.required("members.totalBytes").toLong(), extracted.bytesExtracted)
            assertTrue("UnityFS scratch files were not cleaned up", scratch.listFiles().orEmpty().isEmpty())

            val expectedPaths = (0 until memberCount)
                .map { oracle.required("member.$it.path") }
                .sorted()
            val actualPaths = Files.walk(output.toPath()).use { stream ->
                stream.filter { Files.isRegularFile(it) }
                    .map {
                        output.toPath().relativize(it).toString().replace(File.separatorChar, '/')
                    }
                    .sorted()
                    .collect(Collectors.toList())
            }
            assertEquals("Extracted member paths differ from UnityPy", expectedPaths, actualPaths)

            for (index in 0 until memberCount) {
                val relativePath = oracle.required("member.$index.path")
                val extractedFile = File(output, relativePath)
                assertTrue("Missing extracted member: $relativePath", extractedFile.isFile)
                assertEquals(
                    "Extracted member size differs: $relativePath",
                    oracle.required("member.$index.size").toLong(),
                    extractedFile.length()
                )
                assertEquals(
                    "Extracted member SHA-256 differs: $relativePath",
                    oracle.required("member.$index.sha256"),
                    sha256(extractedFile)
                )
            }

            val serializedPath = oracle.required("serialized.path")
            val serializedFile = File(output, serializedPath)
            assertTrue("SerializedFile was not extracted", serializedFile.isFile)
            val inspection = serializedFile.inputStream().use {
                UnitySerializedFileInspector.inspect(it, serializedFile.length())
            }
            assertNotNull("Kotlin SerializedFile inspection failed", inspection)
            val parsed = inspection!!

            assertEquals(21, parsed.header.formatVersion)
            assertEquals(oracle.required("serialized.fileSize").toLong(), parsed.header.fileSize)
            assertEquals(oracle.required("serialized.metadataSize").toLong(), parsed.header.metadataSize)
            assertEquals(oracle.required("serialized.dataOffset").toLong(), parsed.header.dataOffset)
            assertEquals(oracle.required("serialized.endian"), parsed.header.endian)

            val metadata = parsed.metadataPrefix
            assertNotNull("SerializedFile metadata prefix is missing", metadata)
            assertEquals(oracle.required("serialized.unityVersion"), metadata!!.unityVersion)
            assertEquals(oracle.required("serialized.targetPlatform").toInt(), metadata.targetPlatform)
            assertEquals(oracle.required("serialized.typeTreeEnabled").toBoolean(), metadata.typeTreeEnabled)
            assertEquals(oracle.required("serialized.typeCount").toInt(), metadata.typeCount)

            val typeTable = parsed.typeTable
            assertNotNull("SerializedFile type table is missing", typeTable)
            val expectedClassIds = (0 until oracle.required("serialized.classCount").toInt())
                .map { oracle.required("serialized.class.$it").toInt() }
            assertEquals(expectedClassIds, typeTable!!.records.map { it.classId })

            val objectTable = parsed.objectTable
            assertNotNull("SerializedFile object table is missing", objectTable)
            assertEquals(
                oracle.required("serialized.objectCount").toInt(),
                objectTable!!.objects.size
            )

            val candidates = objectTable.objects.filter {
                UnityTextAssetReader.isTextAssetObject(parsed.header, typeTable, it)
            }
            assertEquals("Unexpected number of real TextAsset objects", 1, candidates.size)
            val objectInfo = candidates.single()
            assertEquals(oracle.required("textAsset.pathId").toLong(), objectInfo.pathId)
            assertEquals(oracle.required("textAsset.typeId").toInt(), objectInfo.typeId)
            assertEquals(oracle.required("textAsset.byteStart").toLong(), objectInfo.byteStart)
            assertEquals(
                oracle.required("textAsset.absoluteDataOffset").toLong(),
                objectInfo.absoluteDataOffset
            )
            assertEquals(oracle.required("textAsset.byteSize").toLong(), objectInfo.byteSize)

            val typeRecord = typeTable.records[objectInfo.typeId]
            assertEquals(49, typeRecord.classId)
            val tree = typeRecord.typeTree
            assertNotNull("Real TextAsset TypeTree was not resolved", tree)
            val expectedNodeCount = oracle.required("textAsset.typeTreeNodeCount").toInt()
            assertEquals(expectedNodeCount, tree!!.nodes.size)
            assertEquals(0, tree.unresolvedCommonStringReferences)
            for (index in 0 until expectedNodeCount) {
                val prefix = "textAsset.typeTree.node.$index"
                val actual = tree.nodes[index]
                assertEquals(oracle.required("$prefix.version").toInt(), actual.version)
                assertEquals(oracle.required("$prefix.level").toInt(), actual.level)
                assertEquals(oracle.required("$prefix.typeFlags").toInt(), actual.typeFlags)
                assertEquals(oracle.required("$prefix.typeName"), actual.typeName)
                assertEquals(oracle.required("$prefix.fieldName"), actual.fieldName)
                assertEquals(oracle.required("$prefix.byteSize").toInt(), actual.byteSize)
                assertEquals(oracle.required("$prefix.index").toInt(), actual.index)
                assertEquals(oracle.required("$prefix.metaFlags").toInt(), actual.metaFlags)
                val expectedRefHash = oracle.required("$prefix.refTypeHash")
                    .takeIf { it.isNotEmpty() }?.toLong()
                assertEquals(expectedRefHash, actual.refTypeHash)
            }

            val content = UnityTextAssetReader.read(
                serializedFile, parsed.header, typeTable, objectInfo
            )
            assertNotNull("Strict Kotlin TextAsset reader rejected the real Unity object", content)
            val text = content!!
            val scriptBytes = text.script.toByteArray(Charsets.UTF_8)
            assertEquals(oracle.required("textAsset.name"), text.name)
            assertEquals(oracle.required("textAsset.pathName"), text.pathName ?: "")
            assertEquals(
                oracle.required("textAsset.scriptBase64"),
                Base64.getEncoder().encodeToString(scriptBytes)
            )
            assertEquals(oracle.required("textAsset.scriptSha256"), sha256(scriptBytes))
            assertEquals(
                "Hello from unity-asset-reader fixtures.\nLine 2: é€😀\n",
                text.script
            )

            assertEquals("Original UnityFS bundle was modified", sourceHash, sha256(fixture))
        } finally {
            temporaryRoot.toFile().deleteRecursively()
        }
    }

    private fun Properties.required(key: String): String =
        getProperty(key) ?: error("Missing UnityPy TextAsset oracle property: $key")

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
