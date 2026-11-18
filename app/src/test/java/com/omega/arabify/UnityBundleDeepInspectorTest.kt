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

    private fun createApk(
        bundle: ByteArray,
        precedingTextEntries: Int = 0,
        trailingTextEntries: Int = 0,
        additionalBundles: List<ByteArray> = emptyList()
    ): File {
        val apk = File.createTempFile("arabify-unity-deep-", ".apk")
        ZipOutputStream(FileOutputStream(apk)).use { zip ->
            repeat(precedingTextEntries) { index ->
                zip.putNextEntry(ZipEntry("assets/bin/Data/StreamingAssets/text-$index.json"))
                zip.write("{\"text\":\"sample\"}".toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            zip.putNextEntry(ZipEntry("assets/bin/Data/StreamingAssets/fixture.bundle"))
            zip.write(bundle)
            zip.closeEntry()
            additionalBundles.forEachIndexed { index, additional ->
                zip.putNextEntry(ZipEntry("assets/bin/Data/StreamingAssets/fixture-$index.bundle"))
                zip.write(additional)
                zip.closeEntry()
            }
            repeat(trailingTextEntries) { index ->
                zip.putNextEntry(ZipEntry("assets/bin/Data/StreamingAssets/trailing-$index.txt"))
                zip.write("sample".toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return apk
    }

    private fun serializedFileBytes(): ByteArray {
        val metadata = java.nio.ByteBuffer.allocate(128)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
        metadata.put("2022.3.0f1".toByteArray(Charsets.UTF_8))
        metadata.put(0)
        metadata.putInt(13)
        metadata.put(0)
        metadata.putInt(1)
        metadata.putInt(1)
        metadata.put(0)
        metadata.putShort(0)
        metadata.put(ByteArray(16) { 0x44 })
        metadata.putInt(2)
        while ((48L + metadata.position()) % 4L != 0L) metadata.put(0x7f)
        fun objectRecord(pathId: Long, offset: Long, size: Int) {
            metadata.putLong(pathId)
            metadata.putLong(offset)
            metadata.putInt(size)
            metadata.putInt(0)
        }
        objectRecord(11L, 0L, 16)
        objectRecord(22L, 16L, 32)
        val meta = metadata.array().copyOf(metadata.position())
        val header = java.nio.ByteBuffer.allocate(48)
            .order(java.nio.ByteOrder.BIG_ENDIAN)
            .apply {
                putInt(meta.size)
                putInt(4096)
                putInt(22)
                putInt(2048)
                put(0)
                put(ByteArray(3))
                putInt(meta.size)
                putLong(4096L)
                putLong(2048L)
                putLong(0L)
            }.array()
        return header + meta + ByteArray(4096 - header.size - meta.size)
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
    fun deepInspectionFindsBundleBeyondVisibleInventoryPreviewLimit() {
        val apk = createApk(
            makeUnityFs("late bundle payload".toByteArray(Charsets.UTF_8)),
            precedingTextEntries = 100
        )
        val scratch = kotlin.io.path.createTempDirectory("arabify-deep-late-bundle-").toFile()
        try {
            val inventory = UnityAssetInventoryScanner.inspect(apk)

            assertEquals(100, inventory.items.size)
            assertEquals(1, inventory.bundleFiles)
            assertEquals("assets/bin/Data/StreamingAssets/fixture.bundle", inventory.bundleCandidates.single().path)

            val report = UnityBundleDeepInspector.inspect(apk, inventory, scratch)

            assertEquals(1, report.bundlesFound)
            assertEquals(1, report.bundlesInspected)
            assertEquals(1, report.nodesExtracted)
            assertTrue(report.notes.isEmpty())
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

    @Test
    fun refusesDeepInspectionWhenInventoryWasTruncated() {
        val apk = createApk(
            makeUnityFs("partial inventory".toByteArray(Charsets.UTF_8)),
            trailingTextEntries = 2
        )
        val scratch = kotlin.io.path.createTempDirectory("arabify-deep-partial-").toFile()
        try {
            val inventory = UnityAssetInventoryScanner.inspect(apk, maxEntriesToScan = 1)
            assertTrue(inventory.scanTruncated)
            assertEquals(1, inventory.bundleCandidates.size)

            val report = UnityBundleDeepInspector.inspect(apk, inventory, scratch)

            assertEquals(0, report.bundlesInspected)
            assertTrue(report.notes.any { it.contains("جرد APK جزئي") })
            assertTrue(report.items.single().status.contains("تخطي"))
            assertTrue(scratch.listFiles()!!.isEmpty())
        } finally {
            apk.delete()
            scratch.deleteRecursively()
        }
    }

    @Test
    fun boundsZipEntryUniquenessVerificationBeforeCreatingScratchFiles() {
        val apk = createApk(
            makeUnityFs("bounded verification".toByteArray(Charsets.UTF_8)),
            precedingTextEntries = 5
        )
        val scratch = kotlin.io.path.createTempDirectory("arabify-deep-zip-budget-").toFile()
        try {
            val inventory = UnityAssetInventoryScanner.inspect(apk)
            val report = UnityBundleDeepInspector.inspect(
                apk,
                inventory,
                scratch,
                UnityBundleDeepInspectionLimits(maxZipEntriesToVerify = 3)
            )

            assertEquals(0, report.bundlesInspected)
            assertTrue(report.notes.any { it.contains("حد 3 إدخال ZIP") })
            assertTrue(report.items.single().status.contains("تخطي"))
            assertTrue(scratch.listFiles()!!.isEmpty())
        } finally {
            apk.delete()
            scratch.deleteRecursively()
        }
    }

    @Test
    fun enforcesAggregateObjectRecordBudgetAcrossSerializedFiles() {
        val serialized = serializedFileBytes()
        val apk = createApk(
            makeUnityFs(serialized),
            additionalBundles = listOf(makeUnityFs(serialized))
        )
        val scratch = kotlin.io.path.createTempDirectory("arabify-deep-object-total-").toFile()
        try {
            val original = apk.readBytes()
            val inventory = UnityAssetInventoryScanner.inspect(apk)
            val report = UnityBundleDeepInspector.inspect(
                apk,
                inventory,
                scratch,
                UnityBundleDeepInspectionLimits(maxTotalObjectRecords = 3)
            )

            assertEquals(2, report.bundlesInspected)
            assertEquals(2, report.serializedFilesParsed)
            // Object tables are atomic: the second file declares two records but
            // only one aggregate slot remains, so neither record is indexed.
            assertEquals(2, report.objectRecordsIndexed)
            assertTrue(report.objectRecordsBudgetExhausted)
            assertTrue(report.notes.any { it.contains("الحد الإجمالي 3") })
            assertTrue(report.items.flatMap { it.details }
                .any { it.contains("aggregate-limit-exceeded (declared=2, remaining=1)") })
            assertArrayEquals(original, apk.readBytes())
            assertTrue(scratch.listFiles()!!.isEmpty())
        } finally {
            apk.delete()
            scratch.deleteRecursively()
        }
    }

    @Test
    fun perFileObjectLimitDoesNotConsumeAggregateBudget() {
        val serialized = serializedFileBytes()
        val apk = createApk(
            makeUnityFs(serialized),
            additionalBundles = listOf(makeUnityFs(serialized))
        )
        val scratch = kotlin.io.path.createTempDirectory("arabify-deep-object-per-file-").toFile()
        try {
            val inventory = UnityAssetInventoryScanner.inspect(apk)
            val report = UnityBundleDeepInspector.inspect(
                apk,
                inventory,
                scratch,
                UnityBundleDeepInspectionLimits(
                    maxObjectRecordsPerFile = 1,
                    maxTotalObjectRecords = 10
                )
            )

            assertEquals(2, report.serializedFilesParsed)
            assertEquals(0, report.objectRecordsIndexed)
            assertTrue(!report.objectRecordsBudgetExhausted)
            assertTrue(report.items.flatMap { it.details }
                .count { it.contains("per-file-limit-exceeded") } == 2)
            assertTrue(scratch.listFiles()!!.isEmpty())
        } finally {
            apk.delete()
            scratch.deleteRecursively()
        }
    }

    @Test
    fun scratchSpaceBudgetUsesOverflowSafeArithmeticAndRequiresFullReserve() {
        assertEquals(180L, requiredUnityScratchSpaceBytes(100L, 50L, 30L))
        assertTrue(hasEnoughUnityScratchSpace(180L, 100L, 50L, 30L))
        assertTrue(!hasEnoughUnityScratchSpace(179L, 100L, 50L, 30L))
        assertEquals(null, requiredUnityScratchSpaceBytes(Long.MAX_VALUE, 1L, 0L))
        assertTrue(!hasEnoughUnityScratchSpace(Long.MAX_VALUE, Long.MAX_VALUE, 1L, 0L))
    }

}
