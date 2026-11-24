package com.omega.arabify

import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class UnityTextAssetSerializedPatcherTest {

 private data class Fixture(
  val file: File,
  val header: UnitySerializedHeader,
  val typeTable: UnitySerializedTypeTable,
  val objectTable: UnitySerializedObjectTable
 )

 private fun buildFixture(): Fixture {
  val bytes = UnityTestFixtures.serializedFileV17(
   listOf(
    UnityTestFixtures.TextAssetSpec(1L, "menu", "Start Game"),
    UnityTestFixtures.TextAssetSpec(2L, "dialog", "The gate is locked. Find the key.")
   )
  )
  val file = File.createTempFile("arabify-fixture", ".assets")
  file.writeBytes(bytes)
  val inspection = file.inputStream().use { UnitySerializedFileInspector.inspect(it, file.length()) }
  assertNotNull(inspection)
  assertNotNull(inspection!!.typeTable)
  assertNotNull(inspection.objectTable)
  return Fixture(file, inspection.header, inspection.typeTable!!, inspection.objectTable!!)
 }


 @Test fun patchLongerScriptShiftsFollowingObjectsAndVerifies() {
  val fixture = buildFixture()
  val output = File.createTempFile("arabify-patched", ".assets")
  val reviewed = "ابدأ اللعبة الآن واستمتع بالمغامرة العربية الكاملة"
  val report = UnityTextAssetSerializedPatcher.patch(
   fixture.file, output, fixture.header, fixture.typeTable, fixture.objectTable,
   pathId = 1L, reviewedScript = reviewed
  )
  assertEquals(0L, report.deltaBytes % 4L)
  assertEquals(1, report.shiftedObjectCount)
  assertTrue(report.newObjectBytes > report.oldObjectBytes)

  val inspection = output.inputStream().use { UnitySerializedFileInspector.inspect(it, output.length()) }!!
  assertEquals(fixture.header.fileSize + report.deltaBytes, inspection.header.fileSize)
  val patchedTarget = inspection.objectTable!!.objects.single { it.pathId == 1L }
  val content = UnityTextAssetReader.read(output, inspection.header, inspection.typeTable!!, patchedTarget)
  assertNotNull(content)
  assertEquals("menu", content!!.name)
  assertEquals(reviewed, content.script)

  val other = inspection.objectTable.objects.single { it.pathId == 2L }
  val otherContent = UnityTextAssetReader.read(output, inspection.header, inspection.typeTable, other)
  assertEquals("The gate is locked. Find the key.", otherContent!!.script)
  fixture.file.delete()
  output.delete()
 }

 @Test fun patchShorterScriptKeepsAlignmentAndContent() {
  val fixture = buildFixture()
  val output = File.createTempFile("arabify-patched", ".assets")
  val reviewed = "ابدأ"
  val report = UnityTextAssetSerializedPatcher.patch(
   fixture.file, output, fixture.header, fixture.typeTable, fixture.objectTable,
   pathId = 1L, reviewedScript = reviewed
  )
  assertEquals(0L, report.deltaBytes % 4L)
  val inspection = output.inputStream().use { UnitySerializedFileInspector.inspect(it, output.length()) }!!
  val target = inspection.objectTable!!.objects.single { it.pathId == 1L }
  val content = UnityTextAssetReader.read(output, inspection.header, inspection.typeTable!!, target)
  assertEquals(reviewed, content!!.script)
  val other = inspection.objectTable.objects.single { it.pathId == 2L }
  val otherContent = UnityTextAssetReader.read(output, inspection.header, inspection.typeTable, other)
  assertEquals("The gate is locked. Find the key.", otherContent!!.script)
  fixture.file.delete()
  output.delete()
 }

 @Test fun patchSecondObjectDoesNotShiftFirst() {
  val fixture = buildFixture()
  val output = File.createTempFile("arabify-patched", ".assets")
  val report = UnityTextAssetSerializedPatcher.patch(
   fixture.file, output, fixture.header, fixture.typeTable, fixture.objectTable,
   pathId = 2L, reviewedScript = "البوابة مقفلة. اعثر على المفتاح."
  )
  assertEquals(0, report.shiftedObjectCount)
  val inspection = output.inputStream().use { UnitySerializedFileInspector.inspect(it, output.length()) }!!
  val first = inspection.objectTable!!.objects.single { it.pathId == 1L }
  val firstContent = UnityTextAssetReader.read(output, inspection.header, inspection.typeTable!!, first)
  assertEquals("Start Game", firstContent!!.script)
  fixture.file.delete()
  output.delete()
 }

 @Test fun rejectsUnknownPathId() {
  val fixture = buildFixture()
  val output = File.createTempFile("arabify-patched", ".assets")
  assertThrows(IllegalStateException::class.java) {
   UnityTextAssetSerializedPatcher.patch(
    fixture.file, output, fixture.header, fixture.typeTable, fixture.objectTable,
    pathId = 99L, reviewedScript = "نص"
   )
  }
  assertEquals(0L, output.length())
  fixture.file.delete()
  output.delete()
 }

 @Test fun rejectsControlCharactersInReviewedText() {
  val fixture = buildFixture()
  val output = File.createTempFile("arabify-patched", ".assets")
  assertThrows(IllegalArgumentException::class.java) {
   UnityTextAssetSerializedPatcher.patch(
    fixture.file, output, fixture.header, fixture.typeTable, fixture.objectTable,
    pathId = 1L, reviewedScript = "نص \u0001 خطير"
   )
  }
  assertFalse(output.length() > 0L)
  fixture.file.delete()
  output.delete()
 }

 @Test fun rejectsOutputPathThatAliasesInputThroughDotSegments() {
  val fixture = buildFixture()
  val original = fixture.file.readBytes()
  val alias = File(fixture.file.parentFile, "missing-parent/../${fixture.file.name}")
  assertThrows(IllegalArgumentException::class.java) {
   UnityTextAssetSerializedPatcher.patch(
    fixture.file, alias, fixture.header, fixture.typeTable, fixture.objectTable,
    pathId = 1L, reviewedScript = "نص بديل"
   )
  }
  assertTrue("The original SerializedFile must remain byte-identical", fixture.file.readBytes().contentEquals(original))
  fixture.file.delete()
 }

 @Test fun failedAtomicReplacementKeepsExistingDestinationAndCleansTemporaryOutput() {
  val fixture = buildFixture()
  val parent = File.createTempFile("arabify-atomic-parent", ".tmp")
  assertTrue(parent.delete())
  assertTrue(parent.mkdirs())
  val outputDirectory = File(parent, "existing-output")
  assertTrue(outputDirectory.mkdir())
  val sentinel = File(outputDirectory, "keep.txt")
  sentinel.writeText("do not replace")
  val original = fixture.file.readBytes()

  assertThrows(IOException::class.java) {
   UnityTextAssetSerializedPatcher.patch(
    fixture.file, outputDirectory, fixture.header, fixture.typeTable, fixture.objectTable,
    pathId = 1L, reviewedScript = "نص عربي بديل"
   )
  }

  assertEquals("do not replace", sentinel.readText())
  assertTrue("The source file must remain byte-identical", fixture.file.readBytes().contentEquals(original))
  assertFalse("Temporary patch files must be cleaned up",
   parent.listFiles().orEmpty().any { it.name.startsWith(".arabify-serialized-") })
  fixture.file.delete()
  parent.deleteRecursively()
 }

 @Test fun fixtureRoundTripsThroughExistingReader() {
  val fixture = buildFixture()
  val first = fixture.objectTable.objects.single { it.pathId == 1L }
  val content = UnityTextAssetReader.read(fixture.file, fixture.header, fixture.typeTable, first)
  assertNotNull(content)
  assertEquals("menu", content!!.name)
  assertEquals("Start Game", content.script)
  fixture.file.delete()
 }
}
