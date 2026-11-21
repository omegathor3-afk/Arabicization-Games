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

    @Test fun rejectsExcessiveFileCountBeforeReadingFiles() {
        val root = createTempDir()
        try {
            val entry = TranslationModelFile("missing.bin", "0".repeat(64))
            val result = TranslationModelPackageVerifier.verify(
                root,
                manifest("missing.bin", "0".repeat(64)).copy(files = listOf(entry, entry)),
                TranslationModelVerificationLimits(maxFileCount = 1)
            )
            assertFalse(result.accepted)
            assertEquals("file_count_limit_exceeded", result.reason)
        } finally { root.deleteRecursively() }
    }

    @Test fun rejectsOversizedFilesBeforeHashingTheirContents() {
        val root = createTempDir()
        try {
            File(root, "model.bin").writeBytes(ByteArray(5) { it.toByte() })
            val result = TranslationModelPackageVerifier.verify(
                root,
                manifest("model.bin", "0".repeat(64)),
                TranslationModelVerificationLimits(maxFileBytes = 4, maxTotalBytes = 8)
            )
            assertFalse(result.accepted)
            assertEquals("file_size_limit_exceeded", result.reason)
        } finally { root.deleteRecursively() }
    }

    @Test fun rejectsAggregatePackageSizeOverBudget() {
        val root = createTempDir()
        try {
            val first = byteArrayOf(1, 2, 3)
            val second = byteArrayOf(4, 5, 6)
            File(root, "first.bin").writeBytes(first)
            File(root, "second.bin").writeBytes(second)
            val base = manifest("first.bin", hash(first))
            val result = TranslationModelPackageVerifier.verify(
                root,
                base.copy(files = listOf(
                    TranslationModelFile("first.bin", hash(first)),
                    TranslationModelFile("second.bin", hash(second))
                )),
                TranslationModelVerificationLimits(maxFileBytes = 4, maxTotalBytes = 5)
            )
            assertFalse(result.accepted)
            assertEquals("total_size_limit_exceeded", result.reason)
        } finally { root.deleteRecursively() }
    }

    @Test fun rejectsDuplicateFileEntriesAndAmbiguousPathSegments() {
        val root = createTempDir()
        try {
            val bytes = "model".toByteArray()
            File(root, "model.bin").writeBytes(bytes)
            val entry = TranslationModelFile("model.bin", hash(bytes))
            val duplicate = TranslationModelPackageVerifier.verify(
                root, manifest("model.bin", hash(bytes)).copy(files = listOf(entry, entry))
            )
            assertFalse(duplicate.accepted)
            assertEquals("duplicate_file_path", duplicate.reason)

            listOf("./model.bin", "folder/../model.bin", "C:/model.bin", "folder//model.bin").forEach { path ->
                val unsafe = TranslationModelPackageVerifier.verify(root, manifest(path, hash(bytes)))
                assertFalse("Expected unsafe path to be rejected: $path", unsafe.accepted)
                assertEquals("unsafe_relative_path", unsafe.reason)
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun acceptsFilesExactlyAtConfiguredSizeLimits() {
        val root = createTempDir()
        try {
            val bytes = byteArrayOf(1, 2, 3, 4)
            File(root, "model.bin").writeBytes(bytes)
            val result = TranslationModelPackageVerifier.verify(
                root,
                manifest("model.bin", hash(bytes)),
                TranslationModelVerificationLimits(maxFileBytes = 4, maxTotalBytes = 4)
            )
            assertTrue(result.accepted)
            assertEquals(4L, result.totalBytes)
        } finally { root.deleteRecursively() }
    }

    @Test fun rejectsInvalidVerificationBudgets() {
        val root = createTempDir()
        try {
            val result = TranslationModelPackageVerifier.verify(
                root,
                manifest("model.bin", "0".repeat(64)),
                TranslationModelVerificationLimits(maxFileCount = 0)
            )
            assertFalse(result.accepted)
            assertEquals("verification_limits_invalid", result.reason)
        } finally { root.deleteRecursively() }
    }


}
