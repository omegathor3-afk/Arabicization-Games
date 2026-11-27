package com.omega.arabify

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BundledModelPackageStagerTest {
    private data class Fixture(
        val manifestJson: ByteArray,
        val manifest: TranslationModelManifest,
        val assets: Map<String, ByteArray>,
    )

    private fun fixture(modelBytes: ByteArray): Fixture {
        val assets = linkedMapOf(
            "config.yml" to "models: []\n".toByteArray(),
            "weights/model.bin" to modelBytes,
        )
        val entries = assets.map { (path, bytes) ->
            TranslationModelFile(path, hash(bytes))
        }
        return Fixture(
            manifestJson = """{"packageId":"fixture-en-ar"}""".toByteArray(),
            manifest = TranslationModelManifest(
                packageId = "fixture-en-ar",
                engine = "bergamot",
                engineVersion = "pinned-test",
                engineLicense = "MPL-2.0",
                modelVersion = "model-test",
                modelLicense = "MPL-2.0",
                tokenizerLicense = "Apache-2.0",
                sourceUrl = "https://example.org/model",
                sourceLanguage = "en",
                targetLanguage = "ar",
                files = entries,
            ),
            assets = assets,
        )
    }

    private fun hash(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun stage(
        root: File,
        fixture: Fixture,
        limits: TranslationModelVerificationLimits = TranslationModelVerificationLimits(),
        openAsset: (String) -> ByteArray = { path ->
            fixture.assets[path] ?: throw IOException("missing fixture asset: $path")
        },
    ): ModelPackageVerification = BundledModelPackageStager.stageOrReuse(
        root = root,
        manifestJson = fixture.manifestJson,
        manifest = fixture.manifest,
        openAsset = { path -> ByteArrayInputStream(openAsset(path)) },
        limits = limits,
    )

    @Test
    fun stagesNestedFilesAndReusesOnlyACompleteVerifiedPackage() {
        val parent = createTempDir()
        try {
            val root = File(parent, "en-ar")
            val fixture = fixture("verified model".toByteArray())
            var opened = 0

            val first = stage(root, fixture) { path ->
                opened++
                fixture.assets[path] ?: throw IOException("missing $path")
            }
            assertTrue(first.accepted)
            assertEquals(2, first.verifiedFiles)
            assertTrue(File(root, "manifest.json").isFile)
            assertArrayEquals(fixture.assets.getValue("config.yml"), File(root, "config.yml").readBytes())
            assertArrayEquals(fixture.assets.getValue("weights/model.bin"), File(root, "weights/model.bin").readBytes())
            assertEquals(2, opened)

            val second = BundledModelPackageStager.stageOrReuse(
                root, fixture.manifestJson, fixture.manifest,
                openAsset = { throw AssertionError("verified package should be reused without copying") },
            )
            assertTrue(second.accepted)
            assertEquals(2, opened)
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test
    fun corruptExistingModelIsRebuiltFromTheBundledAssets() {
        val parent = createTempDir()
        try {
            val root = File(parent, "en-ar")
            val fixture = fixture("original model bytes".toByteArray())
            assertTrue(stage(root, fixture).accepted)

            File(root, "weights/model.bin").writeText("corrupt")

            val repaired = stage(root, fixture)
            assertTrue(repaired.accepted)
            assertArrayEquals(
                fixture.assets.getValue("weights/model.bin"),
                File(root, "weights/model.bin").readBytes(),
            )
            assertFalse(File(parent, ".en-ar.staging").exists())
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test
    fun tamperedStoredManifestIsRebuiltFromTheApkManifest() {
        val parent = createTempDir()
        try {
            val root = File(parent, "en-ar")
            val fixture = fixture("model bytes".toByteArray())
            var opened = 0

            assertTrue(stage(root, fixture) { path ->
                opened++
                fixture.assets.getValue(path)
            }.accepted)
            File(root, "manifest.json").writeText("""{"packageId":"tampered"}""")

            val repaired = stage(root, fixture) { path ->
                opened++
                fixture.assets.getValue(path)
            }

            assertTrue(repaired.accepted)
            assertEquals(4, opened)
            assertArrayEquals(fixture.manifestJson, File(root, "manifest.json").readBytes())
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test
    fun missingStoredManifestIsRebuiltFromTheApkManifest() {
        val parent = createTempDir()
        try {
            val root = File(parent, "en-ar")
            val fixture = fixture("model bytes".toByteArray())
            var opened = 0

            assertTrue(stage(root, fixture) { path ->
                opened++
                fixture.assets.getValue(path)
            }.accepted)
            assertTrue(File(root, "manifest.json").delete())

            val repaired = stage(root, fixture) { path ->
                opened++
                fixture.assets.getValue(path)
            }

            assertTrue(repaired.accepted)
            assertEquals(4, opened)
            assertArrayEquals(fixture.manifestJson, File(root, "manifest.json").readBytes())
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test
    fun refusesSymlinkedPackageRootWithoutModifyingItsTarget() {
        val parent = createTempDir()
        try {
            val target = File(parent, "real-package")
            val fixture = fixture("verified model".toByteArray())
            assertTrue(stage(target, fixture).accepted)

            val alias = File(parent, "alias")
            Files.createSymbolicLink(alias.toPath(), target.toPath())

            expectFailure<IOException> {
                stage(alias, fixture) { throw AssertionError("symlink roots must be rejected before asset access") }
            }

            assertTrue(TranslationModelPackageVerifier.verify(target, fixture.manifest).accepted)
            assertArrayEquals(
                fixture.assets.getValue("weights/model.bin"),
                File(target, "weights/model.bin").readBytes(),
            )
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test
    fun corruptNativeConfigIsRebuiltBecauseItIsPartOfTheManifest() {
        val parent = createTempDir()
        try {
            val root = File(parent, "en-ar")
            val fixture = fixture("model bytes".toByteArray())
            assertTrue(stage(root, fixture).accepted)

            File(root, "config.yml").writeText("models: []")

            val repaired = stage(root, fixture)
            assertTrue(repaired.accepted)
            assertArrayEquals(fixture.assets.getValue("config.yml"), File(root, "config.yml").readBytes())
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test
    fun failedReplacementKeepsThePreviouslyInstalledPackageIntact() {
        val parent = createTempDir()
        try {
            val root = File(parent, "en-ar")
            val oldFixture = fixture("old verified model".toByteArray())
            assertTrue(stage(root, oldFixture).accepted)

            val newFixture = fixture("new model version".toByteArray())
            var opened = 0
            expectFailure<IOException> {
                stage(root, newFixture) { path ->
                    opened++
                    if (path == "weights/model.bin") throw IOException("simulated interrupted asset read")
                    newFixture.assets.getValue(path)
                }
            }

            assertEquals(2, opened)
            assertArrayEquals(
                oldFixture.assets.getValue("weights/model.bin"),
                File(root, "weights/model.bin").readBytes(),
            )
            assertTrue(TranslationModelPackageVerifier.verify(root, oldFixture.manifest).accepted)
            assertFalse(File(parent, ".en-ar.staging").exists())
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test
    fun restoresAValidBackupLeftByAnInterruptedDirectorySwap() {
        val parent = createTempDir()
        try {
            val fixture = fixture("backup model".toByteArray())
            val backup = File(parent, ".en-ar.backup")
            assertTrue(backup.mkdirs())
            File(backup, "manifest.json").writeBytes(fixture.manifestJson)
            for ((path, bytes) in fixture.assets) {
                val destination = File(backup, path)
                assertTrue(destination.parentFile!!.isDirectory || destination.parentFile!!.mkdirs())
                destination.writeBytes(bytes)
            }

            val root = File(parent, "en-ar")
            val recovered = BundledModelPackageStager.stageOrReuse(
                root, fixture.manifestJson, fixture.manifest,
                openAsset = { throw AssertionError("valid backup should be restored without downloading/copying") },
            )

            assertTrue(recovered.accepted)
            assertTrue(root.isDirectory)
            assertFalse(backup.exists())
            assertArrayEquals(fixture.assets.getValue("weights/model.bin"), File(root, "weights/model.bin").readBytes())
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test
    fun rejectsPathTraversalBeforeOpeningAnyAsset() {
        val parent = createTempDir()
        try {
            val root = File(parent, "en-ar")
            val fixture = fixture("model".toByteArray())
            val traversal = fixture.manifest.copy(
                files = fixture.manifest.files + TranslationModelFile("../outside.bin", "0".repeat(64)),
            )
            var opened = false

            expectFailure<IOException> {
                BundledModelPackageStager.stageOrReuse(
                    root, fixture.manifestJson, traversal,
                    openAsset = { opened = true; ByteArrayInputStream(byteArrayOf(1)) },
                )
            }

            assertFalse(opened)
            assertFalse(File(parent, "outside.bin").exists())
            assertFalse(root.exists())
            assertFalse(File(parent, ".en-ar.staging").exists())
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test
    fun rejectsUnmanifestedConfigBeforeOpeningAnyAsset() {
        val parent = createTempDir()
        try {
            val fixture = fixture("model".toByteArray())
            val withoutConfig = fixture.manifest.copy(
                files = fixture.manifest.files.filterNot { it.relativePath == "config.yml" },
            )
            var opened = false

            expectFailure<IllegalArgumentException> {
                BundledModelPackageStager.stageOrReuse(
                    File(parent, "en-ar"), fixture.manifestJson, withoutConfig,
                    openAsset = { opened = true; ByteArrayInputStream(byteArrayOf(1)) },
                )
            }

            assertFalse(opened)
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test
    fun aggregateBudgetFailureCleansStagingWithoutPublishingAPartialPackage() {
        val parent = createTempDir()
        try {
            val root = File(parent, "en-ar")
            val fixture = fixture("12345678".toByteArray())
            expectFailure<IOException> {
                stage(
                    root,
                    fixture,
                    limits = TranslationModelVerificationLimits(
                        maxFileCount = 8,
                        maxFileBytes = 16,
                        maxTotalBytes = 16,
                    ),
                )
            }

            assertFalse(root.exists())
            assertFalse(File(parent, ".en-ar.staging").exists())
        } finally {
            parent.deleteRecursively()
        }
    }

    private inline fun <reified T : Throwable> expectFailure(block: () -> Unit): T {
        try {
            block()
        } catch (failure: Throwable) {
            if (failure is T) return failure
            throw AssertionError("Expected ${T::class.java.simpleName}, got ${failure::class.java.simpleName}", failure)
        }
        throw AssertionError("Expected ${T::class.java.simpleName}")
    }
}
