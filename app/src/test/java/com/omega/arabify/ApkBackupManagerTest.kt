package com.omega.arabify

import org.junit.Assert.*
import org.junit.Assume.assumeNoException
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile

class ApkBackupManagerTest {
    @Test fun backupContainsManifestAndHashesAndCanBeRestored() {
        val root = createTempDir(prefix = "arabify-backup-test")
        try {
            val base = File(root, "base-source.apk").apply { writeBytes("base-apk-content".toByteArray()) }
            val split = File(root, "split-source.apk").apply { writeBytes("split-apk-content".toByteArray()) }
            val archive = File(root, "game.arabify-backup")
            ApkBackupManager.createBackup("com.example.game", "Example Game", "1.2", listOf(base, split), archive)
            ZipFile(archive).use { zip ->
                assertNotNull(zip.getEntry("manifest.json"))
                assertNotNull(zip.getEntry("base.apk"))
                assertNotNull(zip.getEntry("splits/split-01.apk"))
            }
            val restored = ApkBackupManager.extractAndVerify(archive, File(root, "restore"))
            assertEquals("com.example.game", restored.packageName)
            assertEquals("Example Game", restored.appLabel)
            assertEquals("1.2", restored.versionName)
            assertEquals(2, restored.apks.size)
            assertEquals(ApkBackupManager.sha256(base), restored.apks[0].sha256)
            assertEquals(ApkBackupManager.sha256(split), restored.apks[1].sha256)
        } finally { root.deleteRecursively() }
    }

    @Test fun restoreRejectsTamperedApk() {
        val root = createTempDir(prefix = "arabify-tamper-test")
        try {
            val base = File(root, "base.apk").apply { writeBytes("original".toByteArray()) }
            val archive = File(root, "game.zip")
            ApkBackupManager.createBackup("com.example.game", "Game", null, listOf(base), archive)
            val tampered = File(root, "tampered.zip")
            // Rebuild a ZIP with the original manifest but modified APK bytes.
            ZipFile(archive).use { source ->
                java.util.zip.ZipOutputStream(tampered.outputStream()).use { target ->
                    val entries = source.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        target.putNextEntry(java.util.zip.ZipEntry(entry.name))
                        if (entry.name == "base.apk") target.write("modified".toByteArray())
                        else source.getInputStream(entry).use { it.copyTo(target) }
                        target.closeEntry()
                    }
                }
            }
            try {
                ApkBackupManager.extractAndVerify(tampered, File(root, "bad-restore"))
                fail("Expected SHA-256 mismatch")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message.orEmpty().contains("SHA-256"))
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun restoreRejectsUnlistedZipEntriesAndCleansPartialExtraction() {
        val root = createTempDir(prefix = "arabify-extra-entry-test")
        try {
            val base = File(root, "base.apk").apply { writeBytes("valid-apk".toByteArray()) }
            val archive = File(root, "game.zip")
            ApkBackupManager.createBackup("com.example.game", "Game", null, listOf(base), archive)
            val withExtra = File(root, "with-extra.zip")
            ZipFile(archive).use { source ->
                java.util.zip.ZipOutputStream(withExtra.outputStream()).use { target ->
                    val entries = source.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        target.putNextEntry(java.util.zip.ZipEntry(entry.name))
                        source.getInputStream(entry).use { it.copyTo(target) }
                        target.closeEntry()
                    }
                    target.putNextEntry(java.util.zip.ZipEntry("unlisted/payload.bin"))
                    target.write(byteArrayOf(1, 2, 3))
                    target.closeEntry()
                }
            }
            val destination = File(root, "restore")
            try {
                ApkBackupManager.extractAndVerify(withExtra, destination)
                fail("Expected unlisted ZIP entry rejection")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message.orEmpty().contains("غير موثقة"))
            }
            assertFalse("Partial extraction directory must be removed", destination.exists())
        } finally { root.deleteRecursively() }
    }

    @Test fun restoreRejectsNonEmptyDestinationWithoutDeletingExistingFiles() {
        val root = createTempDir(prefix = "arabify-destination-test")
        try {
            val base = File(root, "base.apk").apply { writeBytes("valid-apk".toByteArray()) }
            val archive = File(root, "game.zip")
            ApkBackupManager.createBackup("com.example.game", "Game", null, listOf(base), archive)
            val destination = File(root, "restore").apply { mkdirs() }
            val keep = File(destination, "keep.txt").apply { writeText("keep") }
            try {
                ApkBackupManager.extractAndVerify(archive, destination)
                fail("Expected non-empty destination rejection")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message.orEmpty().contains("غير فارغ"))
            }
            assertEquals("keep", keep.readText())
        } finally { root.deleteRecursively() }
    }

    @Test fun createBackupDoesNotDeleteAnUnrelatedVerificationNamedDirectory() {
        val root = createTempDir(prefix = "arabify-backup-collision-test")
        try {
            val base = File(root, "base.apk").apply { writeBytes("base-apk-content".toByteArray()) }
            val archive = File(root, "game.backup")
            val unrelated = File(root, "verify-game").apply { mkdirs() }
            val keep = File(unrelated, "keep.txt").apply { writeText("preserve") }

            ApkBackupManager.createBackup("com.example.game", "Game", null, listOf(base), archive)

            assertTrue(archive.isFile)
            assertEquals("preserve", keep.readText())
        } finally { root.deleteRecursively() }
    }

    @Test fun createBackupRefusesToOverwriteItsSourceApk() {
        val root = createTempDir(prefix = "arabify-backup-source-test")
        try {
            val source = File(root, "game.apk").apply { writeText("original-apk-bytes") }
            val original = source.readBytes()

            try {
                ApkBackupManager.createBackup("com.example.game", "Game", null, listOf(source), source)
                fail("Expected source/output alias rejection")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message.orEmpty().contains("APK الأصلي"))
            }

            assertArrayEquals(original, source.readBytes())
        } finally { root.deleteRecursively() }
    }

    @Test fun restoreRejectsSymbolicLinkDestinationWithoutWritingThroughIt() {
        val root = createTempDir(prefix = "arabify-symlink-destination-test")
        try {
            val base = File(root, "base.apk").apply { writeText("base-apk-content") }
            val archive = File(root, "game.backup")
            ApkBackupManager.createBackup("com.example.game", "Game", null, listOf(base), archive)

            val target = File(root, "outside").apply { mkdirs() }
            val destination = File(root, "restore-link")
            try {
                Files.createSymbolicLink(destination.toPath(), target.toPath())
            } catch (error: Exception) {
                assumeNoException(error)
            }

            try {
                ApkBackupManager.extractAndVerify(archive, destination)
                fail("Expected symbolic-link destination rejection")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message.orEmpty().contains("رابط رمزي"))
            }

            assertTrue(Files.isSymbolicLink(destination.toPath()))
            assertFalse(File(target, "base.apk").exists())
        } finally { root.deleteRecursively() }
    }

    @Test fun failedRestorePreservesAnExistingEmptyDestinationDirectory() {
        val root = createTempDir(prefix = "arabify-empty-destination-test")
        try {
            val brokenArchive = File(root, "broken.backup").apply { writeText("not a zip archive") }
            val destination = File(root, "restore").apply { mkdirs() }

            try {
                ApkBackupManager.extractAndVerify(brokenArchive, destination)
                fail("Expected invalid archive rejection")
            } catch (_: Exception) {
                // The caller's pre-existing empty directory must remain in place.
            }

            assertTrue(destination.isDirectory)
            assertEquals(0, destination.list()?.size ?: -1)
        } finally { root.deleteRecursively() }
    }


    @Test fun restoreRejectsArchivesWithTooManyZipEntriesBeforeCreatingOutput() {
        val root = createTempDir(prefix = "arabify-entry-count-limit-test")
        try {
            val base = File(root, "base.apk").apply { writeText("valid-apk") }
            val archive = File(root, "game.backup")
            ApkBackupManager.createBackup("com.example.game", "Game", null, listOf(base), archive)
            val oversized = File(root, "too-many-entries.backup")
            ZipFile(archive).use { source ->
                java.util.zip.ZipOutputStream(oversized.outputStream()).use { target ->
                    val entries = source.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        target.putNextEntry(java.util.zip.ZipEntry(entry.name))
                        source.getInputStream(entry).use { it.copyTo(target) }
                        target.closeEntry()
                    }
                    repeat(64) { index ->
                        target.putNextEntry(java.util.zip.ZipEntry("unlisted/extra-$index.bin"))
                        target.closeEntry()
                    }
                }
            }

            val destination = File(root, "restore")
            try {
                ApkBackupManager.extractAndVerify(oversized, destination)
                fail("Expected ZIP entry-count rejection")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message.orEmpty().contains("عدد المدخلات"))
            }
            assertFalse("Rejected archive must not leave an output directory", destination.exists())
        } finally { root.deleteRecursively() }
    }

}
