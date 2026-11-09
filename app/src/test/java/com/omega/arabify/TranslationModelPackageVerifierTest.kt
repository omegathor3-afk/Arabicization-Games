package com.omega.arabify

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

class TranslationModelPackageVerifierTest {
    private fun hash(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun manifest(path: String, digest: String) = TranslationModelManifest(
        packageId = "en-ar-test",
        engine = "bergamot",
        engineVersion = "pinned-test-revision",
        engineLicense = "MPL-2.0",
        modelVersion = "model-test-revision",
        modelLicense = "MPL-2.0",
        tokenizerLicense = "Apache-2.0",
        sourceUrl = "https://example.org/model",
        sourceLanguage = "en",
        targetLanguage = "ar",
        files = listOf(TranslationModelFile(path, digest))
    )

    @Test fun acceptsMatchingPackageAndReportsBytes() {
        val root = createTempDir()
        try {
            val bytes = "model-bytes".toByteArray()
            File(root, "model.bin").writeBytes(bytes)
            val result = TranslationModelPackageVerifier.verify(root, manifest("model.bin", hash(bytes)))
            assertTrue(result.accepted)
            assertEquals(1, result.verifiedFiles)
            assertEquals(bytes.size.toLong(), result.totalBytes)
        } finally { root.deleteRecursively() }
    }

    @Test fun rejectsTamperedModel() {
        val root = createTempDir()
        try {
            File(root, "model.bin").writeText("tampered")
            val result = TranslationModelPackageVerifier.verify(root, manifest("model.bin", hash("original".toByteArray())))
            assertFalse(result.accepted)
            assertEquals("sha256_mismatch", result.reason)
        } finally { root.deleteRecursively() }
    }

    @Test fun rejectsPathTraversal() {
        val root = createTempDir()
        try {
            val result = TranslationModelPackageVerifier.verify(root, manifest("../outside.bin", "0".repeat(64)))
            assertFalse(result.accepted)
            assertEquals("unsafe_relative_path", result.reason)
        } finally { root.deleteRecursively() }
    }

    @Test fun rejectsMissingLicenseMetadata() {
        val root = createTempDir()
        try {
            val valid = manifest("model.bin", "0".repeat(64)).copy(modelLicense = "")
            val result = TranslationModelPackageVerifier.verify(root, valid)
            assertFalse(result.accepted)
            assertEquals("license_metadata_missing", result.reason)
        } finally { root.deleteRecursively() }
    }

    @Test fun rejectsMissingRequiredFiles() {
        val root = createTempDir()
        try {
            val result = TranslationModelPackageVerifier.verify(root, manifest("missing.bin", "0".repeat(64)))
            assertFalse(result.accepted)
            assertEquals("required_file_missing", result.reason)
        } finally { root.deleteRecursively() }
    }
}
