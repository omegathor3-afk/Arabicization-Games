package com.omega.arabify

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises the patcher against SerializedFile v22 fixtures: 48-byte header
 * with 64-bit fileSize/dataOffset, 32-byte TypeTree nodes and i64 object
 * byteStart fields. Real Unity 2021+ bundles use this format.
 */
class UnityTextAssetSerializedPatcherV22Test {

    private data class Fixture(
        val file: File,
        val header: UnitySerializedHeader,
        val typeTable: UnitySerializedTypeTable,
        val objectTable: UnitySerializedObjectTable
    )

    private fun buildFixture(): Fixture {
        val bytes = UnityTestFixtures.serializedFileV22(
            listOf(
                UnityTestFixtures.TextAssetSpec(1L, "menu", "Start Game"),
                UnityTestFixtures.TextAssetSpec(2L, "dialog", "The gate is locked. Find the key.")
            )
        )
        val file = File.createTempFile("arabify-fixture-v22", ".assets")
        file.writeBytes(bytes)
        val inspection = file.inputStream().use { UnitySerializedFileInspector.inspect(it, file.length()) }
        assertNotNull(inspection)
        assertEquals(22, inspection!!.header.formatVersion)
        assertEquals(48, inspection.header.headerSize)
        assertNotNull(inspection.typeTable)
        assertNotNull(inspection.objectTable)
        assertEquals(2, inspection.objectTable!!.objects.size)
        return Fixture(file, inspection.header, inspection.typeTable!!, inspection.objectTable)
    }

    @Test
    fun v22PatchLongerScriptShiftsFollowingObjectsAndPatches64BitHeader() {
        val fixture = buildFixture()
        val output = File.createTempFile("arabify-patched-v22", ".assets")
        val reviewed = "ابدأ اللعبة الآن واستمتع بالمغامرة العربية الكاملة"
        val report = UnityTextAssetSerializedPatcher.patch(
            fixture.file, output, fixture.header, fixture.typeTable, fixture.objectTable,
            pathId = 1L, reviewedScript = reviewed
        )
        assertEquals(0L, report.deltaBytes % 4L)
        assertEquals(1, report.shiftedObjectCount)

        val inspection = output.inputStream().use {
            UnitySerializedFileInspector.inspect(it, output.length())
        }!!
        // The 64-bit fileSize field in the v22 header was patched.
        assertEquals(fixture.header.fileSize + report.deltaBytes, inspection.header.fileSize)
        assertEquals(output.length(), inspection.header.fileSize)

        val target = inspection.objectTable!!.objects.single { it.pathId == 1L }
        val content = UnityTextAssetReader.read(output, inspection.header, inspection.typeTable!!, target)
        assertNotNull(content)
        assertEquals("menu", content!!.name)
        assertEquals(reviewed, content.script)

        val sibling = inspection.objectTable.objects.single { it.pathId == 2L }
        // The sibling's i64 byteStart moved by exactly delta.
        assertEquals(
            fixture.objectTable.objects.single { it.pathId == 2L }.byteStart + report.deltaBytes,
            sibling.byteStart
        )
        val siblingContent = UnityTextAssetReader.read(output, inspection.header, inspection.typeTable, sibling)
        assertEquals("The gate is locked. Find the key.", siblingContent!!.script)
        fixture.file.delete()
        output.delete()
    }

    @Test
    fun v22PatchShorterScriptKeepsContentAndAlignment() {
        val fixture = buildFixture()
        val output = File.createTempFile("arabify-patched-v22", ".assets")
        val reviewed = "ابدأ"
        val report = UnityTextAssetSerializedPatcher.patch(
            fixture.file, output, fixture.header, fixture.typeTable, fixture.objectTable,
            pathId = 1L, reviewedScript = reviewed
        )
        assertEquals(0L, report.deltaBytes % 4L)
        val inspection = output.inputStream().use {
            UnitySerializedFileInspector.inspect(it, output.length())
        }!!
        assertEquals(fixture.header.fileSize + report.deltaBytes, inspection.header.fileSize)
        val target = inspection.objectTable!!.objects.single { it.pathId == 1L }
        val content = UnityTextAssetReader.read(output, inspection.header, inspection.typeTable!!, target)
        assertEquals(reviewed, content!!.script)
        fixture.file.delete()
        output.delete()
    }

    @Test
    fun v22HeaderDataOffsetIsReadFrom64BitField() {
        val fixture = buildFixture()
        assertTrue(fixture.header.dataOffset >= 48)
        assertTrue(fixture.header.dataOffset < fixture.header.fileSize)
        assertEquals(fixture.file.length(), fixture.header.fileSize)
        fixture.file.delete()
    }
}
