package com.omega.arabify

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class UnityBundleDeepInspectorTest {
    private fun makeUnityFs(payload: ByteArray): ByteArray {
        val nodePath = "CAB-fixture/data.bin"
        val infoBytes = ByteArrayOutputStream()
        DataOutputStream(infoBytes).use { data ->
            data.write(ByteArray(16))
            data.writeInt(1)
            data.writeInt(payload.size)
            data.writeInt(payload.size)
            data.writeShort(0)
            data.writeInt(1)
            data.writeLong(0L)
            data.writeLong(payload.size.toLong())
            data.writeInt(0)
            data.write(nodePath.toByteArray(Charsets.UTF_8))
            data.writeByte(0)
        }
        val info = infoBytes.toByteArray()

        fun header(declaredSize: Long): ByteArray {
            val bytes = ByteArrayOutputStream()
            DataOutputStream(bytes).use { data ->
                data.write("UnityFS".toByteArray(Charsets.US_ASCII))
                data.writeByte(0)
                data.writeInt(6)
                data.write("5.x".toByteArray(Charsets.US_ASCII))
                data.writeByte(0)
                data.write("2021.3.0f1".toByteArray(Charsets.US_ASCII))
                data.writeByte(0)
                data.writeLong(declaredSize)
                data.writeInt(info.size)
                data.writeInt(info.size)
                data.writeInt(0)
            }
            return bytes.toByteArray()
        }

        val totalSize = header(0L).size.toLong() + info.size + payload.size
        return header(totalSize) + info + payload
    }

    private fun createApk(bundle: ByteArray): File {
        val apk = File.createTempFile("arabify-unity-deep-", ".apk")
        ZipOutputStream(FileOutputStream(apk)).use { zip ->
            zip.putNextEntry(ZipEntry("assets/bin/Data/StreamingAssets/fixture.bundle"))
            zip.write(bundle)
            zip.closeEntry()
        }
        return apk
    }

    @Test
    fun extractsUnityFsCabNodesReadOnlyAndCleansTemporaryFiles() {
        val bundle = makeUnityFs("fixture payload — العربية".toByteArray(Charsets.UTF_8))
        val apk = createApk(bundle)
        val scratch = kotlin.io.path.createTempDirectory("arabify-deep-inspector-").toFile()
        try {
            val original = apk.readBytes()
            val inventory = UnityAssetInventoryScanner.inspect(apk)

            val report = UnityBundleDeepInspector.inspect(apk, inventory, scratch)

            assertEquals(1, report.bundlesFound)
            assertEquals(1, report.bundlesInspected)
            assertEquals(1, report.nodesExtracted)
            assertEquals(0, report.serializedFilesParsed)
            assertTrue(report.items.single().status.contains("استخراج CAB"))
            assertArrayEquals(original, apk.readBytes())
            assertTrue(scratch.listFiles()!!.isEmpty())
        } finally {
            apk.delete()
            scratch.deleteRecursively()
        }
    }

    @Test
    fun skipsBundleThatExceedsConfiguredCopyBudget() {
        val apk = createApk(makeUnityFs(ByteArray(128) { it.toByte() }))
        val scratch = kotlin.io.path.createTempDirectory("arabify-deep-budget-").toFile()
        try {
            val inventory = UnityAssetInventoryScanner.inspect(apk)
            val report = UnityBundleDeepInspector.inspect(
                apk,
                inventory,
                scratch,
                UnityBundleDeepInspectionLimits(
                    maxSingleBundleBytes = 16,
                    maxTotalBundleBytes = 16
                )
            )

            assertEquals(0, report.bundlesInspected)
            assertTrue(report.items.single().status.contains("تخطي"))
            assertTrue(scratch.listFiles()!!.isEmpty())
        } finally {
            apk.delete()
            scratch.deleteRecursively()
        }
    }
}
