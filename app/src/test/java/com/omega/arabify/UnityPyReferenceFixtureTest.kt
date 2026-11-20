package com.omega.arabify

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Properties
import java.util.stream.Collectors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Differential test against the pinned UnityPy atlas_test bundle.
 *
 * UnityPy is used read-only in CI to produce a manifest. The app's Kotlin
 * extractor must reproduce every raw member byte-for-byte and agree on the
 * inner SerializedFile header, type IDs, object count and external references.
 */
class UnityPyReferenceFixtureTest {
    @Test
    fun extractsPinnedUnityPyAtlasTestBundleByteForByteAndMatchesSerializedMetadata() {
        val fixturePath = System.getenv("ARABIFY_UNITYFS_FIXTURE")
        val oraclePath = System.getenv("ARABIFY_UNITYPY_ORACLE")
        assumeTrue(
            "The pinned UnityPy fixture is prepared by the Android CI workflow",
            !fixturePath.isNullOrBlank() && !oraclePath.isNullOrBlank()
        )

        val fixture = File(fixturePath!!)
        assertTrue("UnityPy fixture file is missing", fixture.isFile && fixture.length() > 0L)
        val oracle = Properties().apply {
            File(oraclePath!!).inputStream().use { load(it) }
        }

        val temporaryRoot = Files.createTempDirectory("arabify-unitypy-reference-")
        try {
            val scratch = File(temporaryRoot.toFile(), "scratch").apply {
                assertTrue("Could not create UnityFS scratch directory", mkdirs())
            }
            val output = File(temporaryRoot.toFile(), "output")
            val report = UnityFsBundleExtractor.extract(fixture, scratch, output)
            assertNotNull("Kotlin UnityFS extraction failed", report)
            val extracted = report!!

            assertEquals(oracle.required("bundle.formatVersion").toInt(), extracted.formatVersion)
            assertEquals(oracle.required("bundle.engineVersion"), extracted.engineVersion)
            val memberCount = oracle.required("members.count").toInt()
            assertEquals(memberCount, extracted.filesExtracted)
            assertEquals(oracle.required("members.totalBytes").toLong(), extracted.bytesExtracted)
            assertTrue("Decoded UnityFS scratch files were not cleaned up", scratch.listFiles().orEmpty().isEmpty())

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
            assertEquals("Extracted UnityFS member paths differ from UnityPy", expectedPaths, actualPaths)

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

            val serializedCount = oracle.required("serialized.count").toInt()
            assertTrue("UnityPy fixture did not expose a SerializedFile", serializedCount > 0)
            for (index in 0 until serializedCount) {
                val prefix = "serialized.$index"
                val relativePath = oracle.required("$prefix.path")
                val serializedFile = File(output, relativePath)
                val inspection = serializedFile.inputStream().use {
                    UnitySerializedFileInspector.inspect(it, serializedFile.length())
                }
                assertNotNull("Kotlin SerializedFile inspection failed: $relativePath", inspection)
                val parsed = inspection!!
                assertEquals(
                    oracle.required("$prefix.headerVersion").toInt(),
                    parsed.header.formatVersion
                )
                assertEquals(oracle.required("$prefix.fileSize").toLong(), parsed.header.fileSize)
                assertEquals(oracle.required("$prefix.metadataSize").toLong(), parsed.header.metadataSize)
                assertEquals(oracle.required("$prefix.dataOffset").toLong(), parsed.header.dataOffset)
                assertEquals(oracle.required("$prefix.endian"), parsed.header.endian)

                val metadata = parsed.metadataPrefix
                assertNotNull("SerializedFile metadata prefix missing: $relativePath", metadata)
                assertEquals(oracle.required("$prefix.unityVersion"), metadata!!.unityVersion)
                assertEquals(
                    oracle.required("$prefix.targetPlatform").toInt(),
                    metadata.targetPlatform
                )
                assertEquals(
                    oracle.required("$prefix.typeTreeEnabled").toBoolean(),
                    metadata.typeTreeEnabled
                )
                assertEquals(oracle.required("$prefix.typeCount").toInt(), metadata.typeCount)

                val typeTable = parsed.typeTable
                assertNotNull("SerializedFile type table missing: $relativePath", typeTable)
                val expectedClassIds = (0 until oracle.required("$prefix.classCount").toInt())
                    .map { oracle.required("$prefix.class.$it").toInt() }
                assertEquals(expectedClassIds, typeTable!!.records.map { it.classId })

                val objectTable = parsed.objectTable
                assertNotNull("SerializedFile object table missing: $relativePath", objectTable)
                assertEquals(
                    oracle.required("$prefix.objectCount").toInt(),
                    objectTable!!.objects.size
                )

                val trailing = parsed.trailingMetadata
                assertNotNull("SerializedFile trailing metadata missing: $relativePath", trailing)
                val expectedExternalPaths = (0 until oracle.required("$prefix.externalCount").toInt())
                    .map { oracle.required("$prefix.external.$it") }
                    .sorted()
                assertEquals(
                    expectedExternalPaths,
                    trailing!!.externals.map { it.path }.sorted()
                )
                assertEquals(UnitySerializedInspectionStage.TRAILING_METADATA_PARSED, parsed.stage)
            }
        } finally {
            temporaryRoot.toFile().deleteRecursively()
        }
    }

    private fun Properties.required(key: String): String =
        getProperty(key) ?: error("Missing UnityPy oracle property: $key")

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
}
