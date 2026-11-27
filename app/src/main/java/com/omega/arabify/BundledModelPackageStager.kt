package com.omega.arabify

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * Installs a bundled model without ever exposing a partially copied package.
 *
 * The APK is the source of truth. Existing staged bytes are reused only after
 * the complete manifest has been verified. A corrupt or interrupted staging
 * directory is rebuilt beside the destination, verified, and then promoted
 * with same-filesystem renames. A previous directory is retained until the
 * replacement has passed verification.
 */
internal object BundledModelPackageStager {
    private val windowsDrivePrefix = Regex("^[A-Za-z]:")

    fun stageOrReuse(
        root: File,
        manifestJson: ByteArray,
        manifest: TranslationModelManifest,
        openAsset: (String) -> InputStream,
        limits: TranslationModelVerificationLimits = TranslationModelVerificationLimits(),
    ): ModelPackageVerification {
        require(manifestJson.isNotEmpty() && manifestJson.size <= MAX_MANIFEST_BYTES) {
            "bundled_model_manifest_size_invalid"
        }
        require(
            limits.maxFileCount in 1..MAX_FILE_COUNT_LIMIT &&
                limits.maxFileBytes > 0L &&
                limits.maxTotalBytes > 0L &&
                limits.maxFileBytes <= limits.maxTotalBytes
        ) { "bundled_model_limits_invalid" }
        require(manifest.files.isNotEmpty() && manifest.files.size <= limits.maxFileCount) {
            "bundled_model_file_count_invalid"
        }

        val normalizedPaths = manifest.files.map { entry ->
            normalizeRelativePath(entry.relativePath)
                ?: throw IOException("bundled_model_unsafe_path")
        }
        require(normalizedPaths.distinct().size == normalizedPaths.size) {
            "bundled_model_duplicate_path"
        }
        require("config.yml" in normalizedPaths) {
            "bundled_model_config_not_hash_manifested"
        }

        val parent = root.absoluteFile.parentFile ?: throw IOException("bundled_model_parent_missing")
        if (!parent.isDirectory && !parent.mkdirs()) {
            throw IOException("bundled_model_parent_create_failed")
        }
        val canonicalParent = parent.canonicalFile
        val rootName = root.name
        require(rootName.isNotBlank() && rootName != "." && rootName != ".." && File(rootName).name == rootName) {
            "bundled_model_root_name_invalid"
        }

        val destination = File(canonicalParent, rootName)
        val staging = File(canonicalParent, ".$rootName.staging")
        val backup = File(canonicalParent, ".$rootName.backup")

        fun verify(directory: File): ModelPackageVerification? =
            if (directory.isDirectory) {
                TranslationModelPackageVerifier.verify(directory, manifest, limits)
            } else {
                null
            }

        val current = verify(destination)
        if (current?.accepted == true) {
            staging.deleteRecursively()
            backup.deleteRecursively()
            return current
        }

        // Recover a completed previous directory swap after process death.
        val previous = verify(backup)
        if (previous?.accepted == true) {
            if (destination.exists() && !destination.deleteRecursively()) {
                throw IOException("bundled_model_corrupt_destination_cleanup_failed")
            }
            if (!backup.renameTo(destination)) {
                throw IOException("bundled_model_backup_restore_failed")
            }
            staging.deleteRecursively()
            return verify(destination) ?: previous
        }

        if (staging.exists() && !staging.deleteRecursively()) {
            throw IOException("bundled_model_staging_cleanup_failed")
        }
        if (!staging.mkdirs()) {
            throw IOException("bundled_model_staging_create_failed")
        }

        try {
            writeSynced(File(staging, MANIFEST_FILE), manifestJson)
            var totalBytes = 0L

            for ((entry, relativePath) in manifest.files.zip(normalizedPaths)) {
                val target = resolveContainedFile(staging, relativePath)
                val targetParent = target.parentFile ?: throw IOException("bundled_model_file_parent_missing")
                if (!targetParent.isDirectory && !targetParent.mkdirs()) {
                    throw IOException("bundled_model_file_parent_create_failed")
                }

                val remainingTotal = limits.maxTotalBytes - totalBytes
                if (remainingTotal <= 0L) throw IOException("bundled_model_total_size_limit_exceeded")
                val copied = openAsset(relativePath).use { input ->
                    copyBounded(
                        input = input,
                        destination = target,
                        maxFileBytes = limits.maxFileBytes,
                        maxRemainingTotalBytes = remainingTotal,
                    )
                }
                if (copied <= 0L) throw IOException("bundled_model_file_empty")
                totalBytes = Math.addExact(totalBytes, copied)
            }

            val staged = TranslationModelPackageVerifier.verify(staging, manifest, limits)
            if (!staged.accepted) {
                throw IOException("bundled_model_verification_failed:${staged.reason}")
            }

            if (backup.exists() && !backup.deleteRecursively()) {
                throw IOException("bundled_model_stale_backup_cleanup_failed")
            }

            val movedOldDirectory = if (destination.exists()) {
                if (!destination.renameTo(backup)) {
                    throw IOException("bundled_model_old_directory_backup_failed")
                }
                true
            } else {
                false
            }

            if (!staging.renameTo(destination)) {
                if (movedOldDirectory && backup.exists() && !backup.renameTo(destination)) {
                    throw IOException("bundled_model_promotion_and_restore_failed")
                }
                throw IOException("bundled_model_promotion_failed")
            }

            val promoted = verify(destination)
            if (promoted?.accepted != true) {
                val failed = File(canonicalParent, ".$rootName.failed")
                failed.deleteRecursively()
                val movedFailedDirectory = destination.renameTo(failed)
                if (movedOldDirectory && backup.exists()) backup.renameTo(destination)
                if (movedFailedDirectory) failed.deleteRecursively()
                throw IOException("bundled_model_post_promotion_verification_failed")
            }

            // If cleanup is interrupted, the next call verifies the new root
            // first and removes this stale backup without touching the model.
            backup.deleteRecursively()
            return promoted
        } catch (failure: Throwable) {
            if (staging.exists()) staging.deleteRecursively()
            if (!destination.exists() && backup.exists()) backup.renameTo(destination)
            throw failure
        } finally {
            if (staging.exists()) staging.deleteRecursively()
        }
    }

    private fun copyBounded(
        input: InputStream,
        destination: File,
        maxFileBytes: Long,
        maxRemainingTotalBytes: Long,
    ): Long {
        var copied = 0L
        FileOutputStream(destination).use { raw ->
            BufferedOutputStream(raw).use { output ->
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    val amount = count.toLong()
                    if (amount > maxFileBytes - copied) {
                        throw IOException("bundled_model_file_size_limit_exceeded")
                    }
                    if (amount > maxRemainingTotalBytes - copied) {
                        throw IOException("bundled_model_total_size_limit_exceeded")
                    }
                    output.write(buffer, 0, count)
                    copied = Math.addExact(copied, amount)
                }
                output.flush()
                raw.fd.sync()
            }
        }
        return copied
    }

    private fun writeSynced(destination: File, bytes: ByteArray) {
        FileOutputStream(destination).use { output ->
            output.write(bytes)
            output.fd.sync()
        }
    }

    private fun resolveContainedFile(root: File, relativePath: String): File {
        val canonicalRoot = root.canonicalFile
        val destination = File(canonicalRoot, relativePath).canonicalFile
        if (!destination.toPath().startsWith(canonicalRoot.toPath())) {
            throw IOException("bundled_model_path_escape")
        }
        return destination
    }

    private fun normalizeRelativePath(raw: String): String? {
        if (raw.isBlank() || raw.length > MAX_RELATIVE_PATH_LENGTH || '\u0000' in raw) return null
        val normalized = raw.replace('\\', '/')
        if (
            normalized.startsWith('/') ||
            normalized.startsWith("//") ||
            windowsDrivePrefix.containsMatchIn(normalized) ||
            normalized.contains("://")
        ) return null
        val segments = normalized.split('/')
        if (segments.isEmpty() || segments.any { segment ->
                segment.isEmpty() || segment == "." || segment == ".." ||
                    segment.any {
                        it.code < 0x20 || it in charArrayOf(':', '<', '>', '"', '|', '?', '*')
                    }
            }
        ) return null
        return segments.joinToString("/")
    }

    private const val MANIFEST_FILE = "manifest.json"
    private const val MAX_MANIFEST_BYTES = 1024 * 1024
    private const val MAX_RELATIVE_PATH_LENGTH = 4096
    private const val MAX_FILE_COUNT_LIMIT = 4096
    private const val COPY_BUFFER_BYTES = 32 * 1024
}
