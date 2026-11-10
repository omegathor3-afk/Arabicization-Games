package com.omega.arabify

import org.junit.Assert.*
import org.junit.Test
import java.io.File
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
}
