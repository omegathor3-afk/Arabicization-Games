package com.omega.arabify

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
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

 private fun le(v: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()
 private fun leShort(v: Int) = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v.toShort()).array()
 private fun leLong(v: Long) = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v).array()
 private fun be(v: Int) = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(v).array()

 private val stringBuffer: ByteArray
 private val stringOffsets = mutableMapOf<String, Int>()

 init {
  val names = listOf("", "TextAsset", "string", "m_Name", "m_Script", "Array", "int", "size", "char", "data")
  val out = ByteArrayOutputStream()
  names.forEach { name ->
   stringOffsets[name] = out.size()
   out.write(name.toByteArray(Charsets.UTF_8))
   out.write(0)
  }
  stringBuffer = out.toByteArray()
 }

 private fun off(name: String) = stringOffsets.getValue(name)

 private fun node(level: Int, flags: Int, type: String, name: String, byteSize: Int, index: Int, meta: Int): ByteArray {
  val out = ByteArrayOutputStream()
  out.write(leShort(0))
  out.write(level)
  out.write(flags)
  out.write(le(off(type)))
  out.write(le(off(name)))
  out.write(le(byteSize))
  out.write(le(index))
  out.write(le(meta))
  return out.toByteArray()
 }

 private fun typeTreeBlob(): ByteArray {
  val out = ByteArrayOutputStream()
  out.write(le(9))
  out.write(le(stringBuffer.size))
  out.write(node(0, 0, "TextAsset", "", 1, 0, 0))
  out.write(node(1, 0, "string", "m_Name", -1, 1, 0))
  out.write(node(2, 1, "Array", "Array", -1, 2, 0x4000))
  out.write(node(3, 0, "int", "size", 4, 3, 0))
  out.write(node(3, 0, "char", "data", 1, 4, 0))
  out.write(node(1, 0, "string", "m_Script", -1, 5, 0))
  out.write(node(2, 1, "Array", "Array", -1, 6, 0x4000))
  out.write(node(3, 0, "int", "size", 4, 7, 0))
  out.write(node(3, 0, "char", "data", 1, 8, 0))
  out.write(stringBuffer)
  return out.toByteArray()
 }

 private fun objectPayload(name: String, script: String, absoluteStart: Long): ByteArray {
  val out = ByteArrayOutputStream()
  val nameBytes = name.toByteArray(Charsets.UTF_8)
  out.write(le(nameBytes.size))
  out.write(nameBytes)
  var pad = ((4L - ((absoluteStart + out.size()) % 4L)) % 4L).toInt()
  repeat(pad) { out.write(0) }
  val scriptBytes = script.toByteArray(Charsets.UTF_8)
  out.write(le(scriptBytes.size))
  out.write(scriptBytes)
  pad = ((4L - ((absoluteStart + out.size()) % 4L)) % 4L).toInt()
  repeat(pad) { out.write(0) }
  return out.toByteArray()
 }

 /** Builds a minimal SerializedFile v17 (little-endian) with two TextAsset objects. */
 private fun buildFixture(): Fixture {
  val metadata = ByteArrayOutputStream()
  metadata.write("2019.4.40f1".toByteArray(Charsets.US_ASCII))
  metadata.write(0)
  metadata.write(le(13)) // targetPlatform: Android
  metadata.write(1) // typeTree enabled
  metadata.write(le(1)) // typeCount
  metadata.write(le(49)) // classId: TextAsset
  metadata.write(0) // isStripped
  metadata.write(leShort(-1)) // scriptTypeIndex
  metadata.write(ByteArray(16)) // oldTypeHash
  metadata.write(typeTreeBlob())

  // The object table's size is independent of the payload contents (fixed field
  // widths), so build it once with placeholder values to learn dataOffset, then
  // build payloads, then rebuild the table with real values.
  val headerSize = 20
  fun buildTable(byteStarts: List<Long>, byteSizes: List<Long>): ByteArray {
   val table = ByteArrayOutputStream()
   table.write(le(byteStarts.size))
   byteStarts.forEachIndexed { index, byteStart ->
    val pad = ((4L - ((headerSize + metadata.size() + table.size()) % 4L)) % 4L).toInt()
    repeat(pad) { table.write(0) }
    table.write(leLong((index + 1).toLong()))
    table.write(le(byteStart.toInt()))
    table.write(le(byteSizes[index].toInt()))
    table.write(le(0))
   }
   return table.toByteArray()
  }
  val provisionalTable = buildTable(listOf(0L, 0L), listOf(0L, 0L))
  val dataOffset = headerSize + metadata.size() + provisionalTable.size.toLong()
  val firstPayload = objectPayload("menu", "Start Game", dataOffset)
  val payloads = listOf(
   firstPayload,
   objectPayload("dialog", "The gate is locked. Find the key.", dataOffset + firstPayload.size)
  )
  val tableBytes = buildTable(
   listOf(0L, firstPayload.size.toLong()),
   payloads.map { it.size.toLong() }
  )
  require(tableBytes.size == provisionalTable.size) { "fixture table size drifted" }
  metadata.write(tableBytes)

  val metadataBytes = metadata.toByteArray()
  val fileSize = dataOffset + payloads.sumOf { it.size }.toLong()
  val file = File.createTempFile("arabify-fixture", ".assets")
  file.outputStream().use { out ->
   out.write(be(metadataBytes.size))
   out.write(be(fileSize.toInt()))
   out.write(be(17))
   out.write(be(dataOffset.toInt()))
   out.write(0) // little endian
   out.write(byteArrayOf(0, 0, 0))
   out.write(metadataBytes)
   payloads.forEach { out.write(it) }
  }

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
