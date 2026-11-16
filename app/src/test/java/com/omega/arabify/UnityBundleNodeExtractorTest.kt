package com.omega.arabify

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class UnityBundleNodeExtractorTest {
    private fun temporaryDirectory(): File = Files.createTempDirectory("arabify-cab-test-").toFile()

    private fun rejected(action: () -> Unit) {
        try {
            action()
            throw AssertionError("Unsafe UnityFS extraction should have been rejected")
        } catch (_: IllegalArgumentException) {
            // Expected validation failure.
        }
    }

    @Test
    fun extractsNestedNodesAndPreservesDecodedStorage() {
        val parent = temporaryDirectory()
        try {
            val storage = File(parent, "decoded.bin")
            val original = "helloWORLD".toByteArray()
            storage.writeBytes(original)
            val destination = File(parent, "cab-output")
            val result = UnityBundleNodeExtractor.extract(
                storage,
                listOf(
                    UnityBundleNode(0, 5, 0, "CAB-1/text/hello.txt"),
                    UnityBundleNode(5, 5, 0, "CAB-1/data.bin")
                ),
                original.size.toLong(),
                destination
            )

            assertEquals(2, result.filesExtracted)
            assertEquals(10L, result.bytesExtracted)
            assertArrayEquals("hello".toByteArray(), File(destination, "CAB-1/text/hello.txt").readBytes())
            assertArrayEquals("WORLD".toByteArray(), File(destination, "CAB-1/data.bin").readBytes())
            assertArrayEquals(original, storage.readBytes())
            assertTrue(parent.listFiles()!!.none { it.name.startsWith(".arabify-unityfs-") })
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test
    fun rejectsTraversalDuplicateOverlapAndOutOfRangeNodesBeforePublishing() {
        val parent = temporaryDirectory()
        try {
            val storage = File(parent, "decoded.bin").apply { writeBytes(ByteArray(8) { it.toByte() }) }
            val unsafeSets = listOf(
                listOf(UnityBundleNode(0, 1, 0, "../outside")),
                listOf(UnityBundleNode(0, 2, 0, "CAB/a"), UnityBundleNode(2, 2, 0, "cab/A")),
                listOf(UnityBundleNode(0, 5, 0, "CAB/a"), UnityBundleNode(4, 2, 0, "CAB/b")),
                listOf(UnityBundleNode(7, 2, 0, "CAB/out-of-range"))
            )
            unsafeSets.forEachIndexed { index, nodes ->
                val destination = File(parent, "rejected-$index")
                rejected {
                    UnityBundleNodeExtractor.extract(storage, nodes, 8, destination)
                }
                assertFalse(destination.exists())
            }
            assertFalse(File(parent.parentFile, "outside").exists())
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test
    fun enforcesPerFileAggregateAndNodeCountLimits() {
        val parent = temporaryDirectory()
        try {
            val storage = File(parent, "decoded.bin").apply { writeBytes(ByteArray(10)) }
            val nodes = listOf(
                UnityBundleNode(0, 5, 0, "CAB/a.bin"),
                UnityBundleNode(5, 5, 0, "CAB/b.bin")
            )
            rejected {
                UnityBundleNodeExtractor.extract(
                    storage, nodes, 10, File(parent, "per-file"),
                    UnityBundleNodeExtractionLimits(maxSingleNodeBytes = 4, maxTotalExtractedBytes = 10)
                )
            }
            rejected {
                UnityBundleNodeExtractor.extract(
                    storage, nodes, 10, File(parent, "aggregate"),
                    UnityBundleNodeExtractionLimits(maxSingleNodeBytes = 10, maxTotalExtractedBytes = 9)
                )
            }
            rejected {
                UnityBundleNodeExtractor.extract(
                    storage, nodes, 10, File(parent, "node-count"),
                    UnityBundleNodeExtractionLimits(maxNodes = 1, maxSingleNodeBytes = 10, maxTotalExtractedBytes = 10)
                )
            }
            assertTrue(parent.listFiles()!!.none { it.name.startsWith(".arabify-unityfs-") })
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test
    fun neverOverwritesAnExistingDestinationOrFollowsDestinationSymlink() {
        val parent = temporaryDirectory()
        try {
            val storage = File(parent, "decoded.bin").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val existing = File(parent, "existing").apply { mkdirs() }
            val marker = File(existing, "keep.txt").apply { writeText("original") }
            rejected {
                UnityBundleNodeExtractor.extract(
                    storage, listOf(UnityBundleNode(0, 3, 0, "CAB/data.bin")),
                    3, existing
                )
            }
            assertEquals("original", marker.readText())

            val target = File(parent, "symlink-target").apply { mkdirs() }
            val targetMarker = File(target, "keep.txt").apply { writeText("safe") }
            val link = File(parent, "linked-output")
            Files.createSymbolicLink(link.toPath(), target.toPath())
            rejected {
                UnityBundleNodeExtractor.extract(
                    storage, listOf(UnityBundleNode(0, 3, 0, "CAB/data.bin")),
                    3, link
                )
            }
            assertEquals("safe", targetMarker.readText())
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test
    fun cleansStagingDirectoryWhenFileAndDirectoryPathsConflict() {
        val parent = temporaryDirectory()
        try {
            val storage = File(parent, "decoded.bin").apply { writeBytes(ByteArray(4)) }
            val destination = File(parent, "conflict-output")
            rejected {
                UnityBundleNodeExtractor.extract(
                    storage,
                    listOf(
                        UnityBundleNode(0, 1, 0, "CAB/item"),
                        UnityBundleNode(1, 1, 0, "CAB/item/child")
                    ),
                    4,
                    destination
                )
            }
            assertFalse(destination.exists())
            assertTrue(parent.listFiles()!!.none { it.name.startsWith(".arabify-unityfs-") })
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test
    fun rejectsStorageLengthMismatchAndInvalidLimits() {
        val parent = temporaryDirectory()
        try {
            val storage = File(parent, "decoded.bin").apply { writeBytes(byteArrayOf(1, 2)) }
            rejected {
                UnityBundleNodeExtractor.extract(
                    storage, listOf(UnityBundleNode(0, 2, 0, "CAB/data.bin")),
                    3, File(parent, "wrong-size")
                )
            }
            rejected {
                UnityBundleNodeExtractor.extract(
                    storage, listOf(UnityBundleNode(0, 2, 0, "CAB/data.bin")),
                    2, File(parent, "wrong-buffer"),
                    UnityBundleNodeExtractionLimits(bufferBytes = 1024)
                )
            }
        } finally {
            parent.deleteRecursively()
        }
    }
}
