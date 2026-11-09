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

/**
 * Fail-closed integrity gate. It does not load native code or assert that a
 * model's license is legally sufficient; it ensures that provenance fields
 * are present and installed bytes match the manifest before activation.
 */
internal object TranslationModelPackageVerifier {
    private val sha256Pattern = Regex("^[a-fA-F0-9]{64}$")

    fun verify(root: File, manifest: TranslationModelManifest): ModelPackageVerification {
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

        val canonicalRoot = root.canonicalFile
        var bytes = 0L
        var verified = 0
        for (entry in manifest.files) {
            if (!sha256Pattern.matches(entry.sha256)) return rejected("sha256_invalid")
            val relative = entry.relativePath
            if (relative.isBlank() || File(relative).isAbsolute ||
                relative.split('/', '\\\\').any { it == ".." || it.isBlank() }) {
                return rejected("unsafe_relative_path")
            }
            val file = File(canonicalRoot, relative).canonicalFile
            if (!file.toPath().startsWith(canonicalRoot.toPath())) return rejected("path_escape")
            if (!file.isFile) return rejected("required_file_missing")
            val actual = sha256(file)
            if (!actual.equals(entry.sha256, ignoreCase = true)) return rejected("sha256_mismatch")
            bytes += file.length()
            verified++
        }
        return ModelPackageVerification(true, verifiedFiles = verified, totalBytes = bytes)
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun rejected(reason: String) = ModelPackageVerification(false, reason)
}
