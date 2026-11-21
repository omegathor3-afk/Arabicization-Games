package com.omega.arabify

import java.io.File
import java.security.MessageDigest

/** Metadata for one independently licensed local translation package. */
internal data class TranslationModelFile(
    val relativePath: String,
    val sha256: String
)

internal data class TranslationModelManifest(
    val packageId: String,
    val engine: String,
    val engineVersion: String,
    val engineLicense: String,
    val modelVersion: String,
    val modelLicense: String,
    val tokenizerLicense: String,
    val sourceUrl: String,
    val sourceLanguage: String,
    val targetLanguage: String,
    val files: List<TranslationModelFile>
)

internal data class ModelPackageVerification(
    val accepted: Boolean,
    val reason: String? = null,
    val verifiedFiles: Int = 0,
    val totalBytes: Long = 0
)

internal data class TranslationModelVerificationLimits(
    val maxFileCount: Int = 128,
    val maxFileBytes: Long = 256L * 1024L * 1024L,
    val maxTotalBytes: Long = 512L * 1024L * 1024L
)

/**
 * Fail-closed integrity gate. It does not load native code or assert that a
 * model's license is legally sufficient; it ensures that provenance fields
 * are present, paths are contained, resource use is bounded, and installed
 * bytes match the manifest before activation.
 */
internal object TranslationModelPackageVerifier {
    private val sha256Pattern = Regex("^[a-fA-F0-9]{64}$")
    private val windowsDrivePrefix = Regex("^[A-Za-z]:")

    fun verify(
        root: File,
        manifest: TranslationModelManifest,
        limits: TranslationModelVerificationLimits = TranslationModelVerificationLimits()
    ): ModelPackageVerification {
        if (!validLimits(limits)) return rejected("verification_limits_invalid")
        if (!root.exists() || !root.isDirectory) return rejected("package_directory_missing")
        if (manifest.packageId.isBlank() || manifest.engine.isBlank() || manifest.engineVersion.isBlank()) {
            return rejected("engine_metadata_missing")
        }
        if (manifest.engineLicense.isBlank() || manifest.modelLicense.isBlank() ||
            manifest.tokenizerLicense.isBlank()) return rejected("license_metadata_missing")
        if (!manifest.sourceUrl.startsWith("https://")) return rejected("source_url_invalid")
        if (manifest.sourceLanguage.isBlank() || manifest.targetLanguage.isBlank()) {
            return rejected("language_pair_missing")
        }
        if (manifest.files.isEmpty()) return rejected("package_file_list_empty")
        if (manifest.files.size > limits.maxFileCount) return rejected("file_count_limit_exceeded")

        val canonicalRoot = root.canonicalFile
        val seenFiles = HashSet<String>(manifest.files.size)
        var bytes = 0L
        var verified = 0
        for (entry in manifest.files) {
            if (!sha256Pattern.matches(entry.sha256)) return rejected("sha256_invalid")
            val normalized = normalizeRelativePath(entry.relativePath)
                ?: return rejected("unsafe_relative_path")
            val file = File(canonicalRoot, normalized).canonicalFile
            if (!file.toPath().startsWith(canonicalRoot.toPath())) return rejected("path_escape")
            if (!seenFiles.add(file.path)) return rejected("duplicate_file_path")
            if (!file.isFile) return rejected("required_file_missing")

            val declaredSize = file.length()
            if (declaredSize > limits.maxFileBytes) return rejected("file_size_limit_exceeded")
            if (declaredSize > limits.maxTotalBytes - bytes) return rejected("total_size_limit_exceeded")

            val digest = digestBounded(file, limits.maxFileBytes)
                ?: return rejected("file_size_limit_exceeded")
            if (digest.bytes != declaredSize) return rejected("file_changed_during_verification")
            if (digest.bytes > limits.maxTotalBytes - bytes) return rejected("total_size_limit_exceeded")
            if (!digest.sha256.equals(entry.sha256, ignoreCase = true)) return rejected("sha256_mismatch")

            bytes = Math.addExact(bytes, digest.bytes)
            verified++
        }
        return ModelPackageVerification(true, verifiedFiles = verified, totalBytes = bytes)
    }

    private data class FileDigest(val sha256: String, val bytes: Long)

    private fun digestBounded(file: File, maxBytes: Long): FileDigest? {
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count.toLong() > maxBytes - total) return null
                total = Math.addExact(total, count.toLong())
                digest.update(buffer, 0, count)
            }
        }
        return FileDigest(
            digest.digest().joinToString("") { "%02x".format(it) },
            total
        )
    }

    private fun normalizeRelativePath(raw: String): String? {
        if (raw.isBlank() || raw.length > MAX_RELATIVE_PATH_LENGTH || '\u0000' in raw) return null
        val normalized = raw.replace('\\', '/')
        if (normalized.startsWith('/') || normalized.startsWith("//") ||
            windowsDrivePrefix.containsMatchIn(normalized) || normalized.contains("://")
        ) return null
        val segments = normalized.split('/')
        if (segments.isEmpty() || segments.any { segment ->
                segment.isEmpty() || segment == "." || segment == ".." ||
                    segment.any { it.code < 0x20 || it in charArrayOf(':', '<', '>', '"', '|', '?', '*') }
            }
        ) return null
        return segments.joinToString("/")
    }

    private fun validLimits(limits: TranslationModelVerificationLimits): Boolean =
        limits.maxFileCount in 1..MAX_FILE_COUNT_LIMIT &&
            limits.maxFileBytes > 0L &&
            limits.maxTotalBytes > 0L &&
            limits.maxFileBytes <= limits.maxTotalBytes

    private fun rejected(reason: String) = ModelPackageVerification(false, reason)

    private const val MAX_RELATIVE_PATH_LENGTH = 4096
    private const val MAX_FILE_COUNT_LIMIT = 4096
}
