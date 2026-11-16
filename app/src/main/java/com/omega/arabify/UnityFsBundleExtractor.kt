package com.omega.arabify

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes

internal data class UnityBundleNodeExtractionLimits(
    val maxNodes: Int = 100_000,
    val maxPathChars: Int = 4_096,
    val maxSingleNodeBytes: Long = 256L * 1024L * 1024L,
    val maxTotalExtractedBytes: Long = 512L * 1024L * 1024L,
    val bufferBytes: Int = 64 * 1024
)

internal data class UnityBundleNodeExtractionResult(
    val outputDirectory: File,
    val filesExtracted: Int,
    val bytesExtracted: Long
)

/**
 * Extracts already-decoded UnityFS node ranges into a new directory.
 *
 * This is a read-only operation with respect to the source bundle/storage file.
 * It refuses an existing destination, stages the complete tree beside the target,
 * and publishes it with a directory rename only after every node has been copied.
 */
internal object UnityBundleNodeExtractor {
    fun extract(
        decodedStorage: File,
        nodes: List<UnityBundleNode>,
        totalUncompressedBytes: Long,
        destinationDirectory: File,
        limits: UnityBundleNodeExtractionLimits = UnityBundleNodeExtractionLimits()
    ): UnityBundleNodeExtractionResult {
        require(validLimits(limits)) { "Invalid UnityFS extraction limits" }
        require(nodes.isNotEmpty() && nodes.size <= limits.maxNodes) {
            "UnityFS node count exceeds the configured extraction limit"
        }
        require(totalUncompressedBytes >= 0L &&
            totalUncompressedBytes <= limits.maxTotalExtractedBytes
        ) { "UnityFS decoded storage exceeds the extraction limit" }
        require(UnityBundleNodeSafety.validateNodeLayout(nodes, totalUncompressedBytes)) {
            "UnityFS node paths or byte ranges are unsafe"
        }

        var aggregateBytes = 0L
        val safePaths = nodes.map { node ->
            val safePath = UnityBundleNodeSafety.safeRelativePath(node.path)
                ?: error("UnityFS node contains an unsafe relative path")
            require(safePath.length <= limits.maxPathChars) {
                "UnityFS node path exceeds the configured limit"
            }
            require(node.size <= limits.maxSingleNodeBytes) {
                "UnityFS node exceeds the per-file extraction limit"
            }
            aggregateBytes = Math.addExact(aggregateBytes, node.size)
            require(aggregateBytes <= limits.maxTotalExtractedBytes) {
                "UnityFS nodes exceed the aggregate extraction limit"
            }
            safePath
        }

        val storagePath = decodedStorage.toPath().toAbsolutePath().normalize()
        require(Files.isRegularFile(storagePath, LinkOption.NOFOLLOW_LINKS) &&
            !Files.isSymbolicLink(storagePath)
        ) { "Decoded UnityFS storage must be a regular file" }
        require(Files.size(storagePath) == totalUncompressedBytes) {
            "Decoded UnityFS storage length does not match its declared size"
        }

        val requestedDestination = destinationDirectory.toPath().toAbsolutePath().normalize()
        val parent = requestedDestination.parent
            ?: error("UnityFS extraction destination must have a parent directory")
        val destinationName = requestedDestination.fileName
            ?: error("UnityFS extraction destination name is invalid")
        require(Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS) &&
            !Files.isSymbolicLink(parent)
        ) { "UnityFS extraction parent must be an existing non-symlink directory" }

        // Resolve the parent once so the staging and final paths share one trusted root.
        val canonicalParent = parent.toRealPath()
        val destination = canonicalParent.resolve(destinationName.toString())
        require(!Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            "UnityFS extraction destination already exists"
        }

        val staging = Files.createTempDirectory(canonicalParent, ".arabify-unityfs-")
        var published = false
        try {
            RandomAccessFile(storagePath.toFile(), "r").use { input ->
                nodes.forEachIndexed { index, node ->
                    val segments = safePaths[index].split('/')
                    var directory = staging
                    for (segment in segments.dropLast(1)) {
                        val child = directory.resolve(segment).normalize()
                        require(child.startsWith(staging)) {
                            "UnityFS node escaped its staging directory"
                        }
                        if (Files.exists(child, LinkOption.NOFOLLOW_LINKS)) {
                            require(!Files.isSymbolicLink(child) &&
                                Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)
                            ) { "UnityFS node path conflicts with an existing file or link" }
                        } else {
                            Files.createDirectory(child)
                        }
                        directory = child
                    }

                    val target = directory.resolve(segments.last()).normalize()
                    require(target.startsWith(staging) &&
                        !Files.exists(target, LinkOption.NOFOLLOW_LINKS)
                    ) { "UnityFS node path collides with another extracted entry" }

                    val temporaryFile = Files.createTempFile(directory, ".arabify-node-", ".part")
                    try {
                        input.seek(node.offset)
                        Files.newOutputStream(
                            temporaryFile,
                            StandardOpenOption.WRITE,
                            StandardOpenOption.TRUNCATE_EXISTING
                        ).use { output ->
                            copyExactly(input, output, node.size, limits.bufferBytes)
                        }
                        require(Files.size(temporaryFile) == node.size) {
                            "Extracted UnityFS node size does not match its metadata"
                        }
                        moveWithoutReplacement(temporaryFile, target)
                    } finally {
                        Files.deleteIfExists(temporaryFile)
                    }
                }
            }

            require(!Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                "UnityFS extraction destination appeared during staging"
            }
            moveWithoutReplacement(staging, destination)
            published = true
            return UnityBundleNodeExtractionResult(
                outputDirectory = destination.toFile(),
                filesExtracted = nodes.size,
                bytesExtracted = aggregateBytes
            )
        } finally {
            if (!published) deleteTree(staging)
        }
    }

    private fun copyExactly(
        input: RandomAccessFile,
        output: OutputStream,
        byteCount: Long,
        bufferBytes: Int
    ) {
        var remaining = byteCount
        val buffer = ByteArray(bufferBytes)
        while (remaining > 0L) {
            val requested = minOf(buffer.size.toLong(), remaining).toInt()
            val read = input.read(buffer, 0, requested)
            require(read > 0) { "Decoded UnityFS storage ended inside a node range" }
            output.write(buffer, 0, read)
            remaining -= read.toLong()
        }
    }

    private fun moveWithoutReplacement(source: Path, target: Path) {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source, target)
        }
    }

    private fun deleteTree(root: Path) {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.deleteIfExists(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, error: IOException?): FileVisitResult {
                if (error != null) throw error
                Files.deleteIfExists(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }

    private fun validLimits(limits: UnityBundleNodeExtractionLimits): Boolean =
        limits.maxNodes in 1..100_000 &&
            limits.maxPathChars in 1..4_096 &&
            limits.maxSingleNodeBytes >= 0L &&
            limits.maxTotalExtractedBytes >= 0L &&
            limits.maxSingleNodeBytes <= limits.maxTotalExtractedBytes &&
            limits.bufferBytes in 4_096..(1024 * 1024)
}

internal data class UnityFsBundleExtractionReport(
    val engineVersion: String,
    val formatVersion: Int,
    val filesExtracted: Int,
    val bytesExtracted: Long,
    val outputDirectory: File
)

/**
 * Bounded UnityFS -> decoded storage -> CAB-node extraction pipeline.
 * The decoded intermediate is created only in the caller's private scratch folder
 * and is deleted on success or failure. The source bundle is never modified.
 */
internal object UnityFsBundleExtractor {
    fun extract(
        bundleFile: File,
        scratchDirectory: File,
        destinationDirectory: File,
        decodeLimits: UnityFsDecodeLimits = UnityFsDecodeLimits(),
        extractionLimits: UnityBundleNodeExtractionLimits = UnityBundleNodeExtractionLimits()
    ): UnityFsBundleExtractionReport? {
        val scratchPath = scratchDirectory.toPath().toAbsolutePath().normalize()
        if (!Files.isDirectory(scratchPath, LinkOption.NOFOLLOW_LINKS) ||
            Files.isSymbolicLink(scratchPath)
        ) return null

        val decodedStorage = try {
            Files.createTempFile(scratchPath, "arabify-unityfs-", ".decoded").toFile()
        } catch (_: IOException) {
            return null
        }

        return try {
            val decoded = FileOutputStream(decodedStorage).use { output ->
                UnityFsBundleDecoder.decode(bundleFile, output, decodeLimits)
            } ?: return null

            val extracted = UnityBundleNodeExtractor.extract(
                decodedStorage = decodedStorage,
                nodes = decoded.blockInfo.nodes,
                totalUncompressedBytes = decoded.decodedBytes,
                destinationDirectory = destinationDirectory,
                limits = extractionLimits
            )
            UnityFsBundleExtractionReport(
                engineVersion = decoded.header.engineVersion,
                formatVersion = decoded.header.formatVersion,
                filesExtracted = extracted.filesExtracted,
                bytesExtracted = extracted.bytesExtracted,
                outputDirectory = extracted.outputDirectory
            )
        } catch (_: Exception) {
            null
        } finally {
            Files.deleteIfExists(decodedStorage.toPath())
        }
    }
}
