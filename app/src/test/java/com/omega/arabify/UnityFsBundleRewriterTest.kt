package com.omega.arabify

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class UnityFsBundleRewriterTest {

 private fun be32(v: Long) = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(v.toInt()).array()
 private fun be64(v: Long) = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(v).array()

 private val compressor = net.jpountz.lz4.LZ4Factory.fastestInstance().fastCompressor()

 private fun lz4(raw: ByteArray): ByteArray {
  val max = compressor.maxCompressedLength(raw.size)
  val buffer = ByteArray(max)
  val written = compressor.compress(raw, 0, raw.size, buffer, 0, max)
  return buffer.copyOf(written)
 }

 private data class FixtureNode(val path: String, val flags: Long, val data: ByteArray)

 /** Builds a minimal UnityFS v7 bundle: LZ4 block info, two storage blocks (raw + LZ4). */
 private fun buildBundle(nodes: List<FixtureNode>, infoCodecFlags: Long = 0x2L): File {
  val stream = ByteArrayOutputStream()
  nodes.forEach { stream.write(it.data) }
  val streamBytes = stream.toByteArray()

  // Two storage blocks: first raw, second LZ4-compressed.
  val split = streamBytes.size / 2
  val chunk1 = streamBytes.copyOfRange(0, split)
  val chunk2 = streamBytes.copyOfRange(split, streamBytes.size)
  val chunk2Compressed = lz4(chunk2)
  val blocks = listOf(
   Triple(chunk1.size.toLong(), chunk1.size.toLong(), 0),
   Triple(chunk2.size.toLong(), chunk2Compressed.size.toLong(), 2)
  )

  val info = ByteArrayOutputStream()
  // Hash slot is a cache hint the parser never validates; any 16 bytes work.
  info.write(MessageDigest.getInstance("SHA-256").digest(streamBytes).copyOf(16))
  info.write(be32(blocks.size.toLong()))
  blocks.forEach { (uncompressed, compressed, flags) ->
   info.write(be32(uncompressed))
   info.write(be32(compressed))
   info.write(ByteBuffer.allocate(2).order(ByteOrder.BIG_ENDIAN).putShort(flags.toShort()).array())
  }
  info.write(be32(nodes.size.toLong()))
  var offset = 0L
  nodes.forEach { node ->
   info.write(be64(offset))
   info.write(be64(node.data.size.toLong()))
   info.write(be32(node.flags))
   info.write(node.path.toByteArray(Charsets.UTF_8))
   info.write(0)
   offset += node.data.size
  }
  val infoBytes = info.toByteArray()
  val compressedInfo = when (infoCodecFlags) {
   0x2L -> lz4(infoBytes)
   else -> infoBytes
  }

  val header = ByteArrayOutputStream()
  header.write("UnityFS".toByteArray(Charsets.US_ASCII))
  header.write(0)
  header.write(be32(7))
  header.write("5.x".toByteArray(Charsets.US_ASCII))
  header.write(0)
  header.write("2019.4.40f1".toByteArray(Charsets.US_ASCII))
  header.write(0)
  val headerSize = header.size() + 20

  val dataRegion = ByteArrayOutputStream()
  dataRegion.write(chunk1)
  dataRegion.write(chunk2Compressed)

  val alignedHeaderEnd = (headerSize + 15) / 16 * 16
  val declaredSize = alignedHeaderEnd + compressedInfo.size + dataRegion.size()

  header.write(be64(declaredSize.toLong()))
  header.write(be32(compressedInfo.size.toLong()))
  header.write(be32(infoBytes.size.toLong()))
  header.write(be32(infoCodecFlags))
  repeat(alignedHeaderEnd - headerSize) { header.write(0) }
  header.write(compressedInfo)
  header.write(dataRegion.toByteArray())

  val file = File.createTempFile("arabify-bundle", ".unity3d")
  file.writeBytes(header.toByteArray())
  return file
 }

 @Test fun rewriteNodeReplacesOnlyTargetAndDecodes() {
  val nodeA = FixtureNode("CAB-0123456789abcdef0123456789abcdef", 4,
   "FAKE-SERIALIZED-FILE-CONTENT-0123456789".toByteArray().let { base -> ByteArray(5000) { base[it % base.size] } })
  val nodeB = FixtureNode("resS", 0, ByteArray(3000) { (it % 251).toByte() })
  val bundle = buildBundle(listOf(nodeA, nodeB))
  val output = File.createTempFile("arabify-bundle-out", ".unity3d")

  val replacement = ByteArray(7000) { (it % 13).toByte() }
  val report = UnityFsBundleRewriter.rewriteNode(
   bundle, output, nodeA.path, replacement
  )
  assertEquals(nodeA.data.size.toLong(), report.oldNodeBytes)
  assertEquals(7000L, report.newNodeBytes)
  assertEquals(2000L, report.deltaBytes)
  assertEquals(1, report.shiftedNodeCount)

  val streamOut = ByteArrayOutputStream()
  val decoded = UnityFsBundleDecoder.decode(output, streamOut)
  assertTrue(decoded != null)
  val stream = streamOut.toByteArray()
  val decodedA = decoded!!.blockInfo.nodes.single { it.path == nodeA.path }
  assertEquals(7000L, decodedA.size)
  assertTrue(stream.copyOfRange(0, 7000).contentEquals(replacement))
  val decodedB = decoded.blockInfo.nodes.single { it.path == "resS" }
  assertEquals(7000L, decodedB.offset)
  assertTrue(stream.copyOfRange(7000, 7000 + 3000).contentEquals(nodeB.data))
  bundle.delete()
  output.delete()
 }

 @Test fun rewriteNodeWithShorterReplacement() {
  val nodeA = FixtureNode("CAB-aaaa", 4, ByteArray(4000) { 7 })
  val nodeB = FixtureNode("data", 0, ByteArray(1000) { 9 })
  val bundle = buildBundle(listOf(nodeA, nodeB))
  val output = File.createTempFile("arabify-bundle-out", ".unity3d")
  val replacement = ByteArray(1500) { 3 }
  val report = UnityFsBundleRewriter.rewriteNode(bundle, output, nodeA.path, replacement)
  assertEquals(-2500L, report.deltaBytes)
  val streamOut = ByteArrayOutputStream()
  val decoded = UnityFsBundleDecoder.decode(output, streamOut)
  assertTrue(decoded != null)
  val stream = streamOut.toByteArray()
  assertTrue(stream.copyOfRange(0, 1500).contentEquals(replacement))
  val decodedB = decoded!!.blockInfo.nodes.single { it.path == "data" }
  assertEquals(1500L, decodedB.offset)
  assertTrue(stream.copyOfRange(1500, 2500).contentEquals(nodeB.data))
  bundle.delete()
  output.delete()
 }

 @Test fun rewriteUnknownNodeFailsAndKeepsOutputEmpty() {
  val nodeA = FixtureNode("CAB-aaaa", 4, ByteArray(100) { 1 })
  val bundle = buildBundle(listOf(nodeA))
  val output = File.createTempFile("arabify-bundle-out", ".unity3d")
  assertThrows(IllegalStateException::class.java) {
   UnityFsBundleRewriter.rewriteNode(bundle, output, "CAB-missing", ByteArray(10) { 2 })
  }
  assertEquals(0L, output.length())
  bundle.delete()
  output.delete()
 }

 @Test fun lzmaBlockInfoBundleIsRefused() {
  // Header flags codec 1 (LZMA) for the block info: the rewriter must refuse
  // rather than emit an encoder format it has not verified.
  val nodeA = FixtureNode("CAB-aaaa", 4, ByteArray(100) { 1 })
  val bundle = buildBundle(listOf(nodeA), infoCodecFlags = 0x0L)
  // Flip the header flags field to codec 1 (LZMA) without actually LZMA-encoding
  // the block info: the rewriter must fail cleanly, not guess.
  val file = File.createTempFile("arabify-bundle-lzma", ".unity3d")
  val headerTail = bundle.readBytes()
  val flagsPos = "UnityFS".length + 1 + 4 + "5.x".length + 1 + "2019.4.40f1".length + 1 + 8 + 4 + 4
  headerTail[flagsPos + 3] = 1
  file.writeBytes(headerTail)
  assertThrows(IllegalStateException::class.java) {
   UnityFsBundleRewriter.rewriteNode(file, File.createTempFile("out", ".unity3d"), "CAB-aaaa", ByteArray(10) { 2 })
  }
  bundle.delete()
  file.delete()
 }

 @Test fun rewriteLargeDirectoryPreservesAllNodesAndShiftedPayloads() {
  val untouchedCount = 4096
  val nodes = ArrayList<FixtureNode>(untouchedCount + 1)
  nodes += FixtureNode("CAB-target", 4, byteArrayOf(1))
  repeat(untouchedCount) { index ->
   val name = "node-" + index.toString().padStart(4, '0') + ".bin"
   nodes += FixtureNode(name, 0, byteArrayOf((index % 251).toByte()))
  }
  val bundle = buildBundle(nodes)
  val output = File.createTempFile("arabify-large-directory-out", ".unity3d")
  val replacement = byteArrayOf(7, 8)

  val report = UnityFsBundleRewriter.rewriteNode(bundle, output, "CAB-target", replacement)

  assertEquals(untouchedCount, report.shiftedNodeCount)
  val streamOut = ByteArrayOutputStream()
  val decoded = UnityFsBundleDecoder.decode(output, streamOut)
  assertTrue(decoded != null)
  assertEquals(untouchedCount + 1, decoded!!.blockInfo.nodes.size)
  val lastNode = decoded.blockInfo.nodes.single { it.path == nodes.last().path }
  assertEquals((untouchedCount + 1).toLong(), lastNode.offset)
  val stream = streamOut.toByteArray()
  assertTrue(
   stream.copyOfRange(lastNode.offset.toInt(), lastNode.offset.toInt() + lastNode.size.toInt())
    .contentEquals(nodes.last().data)
  )

  bundle.delete()
  output.delete()
 }

 @Test fun originalBundleIsNeverModified() {
  val nodeA = FixtureNode("CAB-aaaa", 4, ByteArray(2000) { 5 })
  val bundle = buildBundle(listOf(nodeA))
  val before = bundle.readBytes()
  val output = File.createTempFile("arabify-bundle-out", ".unity3d")
  UnityFsBundleRewriter.rewriteNode(bundle, output, nodeA.path, ByteArray(2500) { 6 })
  assertTrue(bundle.readBytes().contentEquals(before))
  bundle.delete()
  output.delete()
 }

 @Test fun refusesOutputPathThatAliasesInputThroughDotSegments() {
  val node = FixtureNode("CAB-alias", 4, ByteArray(128) { 7 })
  val bundle = buildBundle(listOf(node))
  val before = bundle.readBytes()
  val alias = File(bundle.parentFile, "missing-parent/../" + bundle.name)

  assertThrows(IllegalArgumentException::class.java) {
   UnityFsBundleRewriter.rewriteNode(bundle, alias, node.path, ByteArray(64) { 3 })
  }

  assertTrue("The original bundle must remain byte-identical", bundle.readBytes().contentEquals(before))
  bundle.delete()
 }

 @Test fun failedAtomicPublishPreservesExistingDestinationAndCleansTemporaryFile() {
  val node = FixtureNode("CAB-atomic", 4, ByteArray(256) { (it % 17).toByte() })
  val bundle = buildBundle(listOf(node))
  val original = bundle.readBytes()
  val parent = createTempDir()
  val outputDirectory = File(parent, "existing-output")
  assertTrue(outputDirectory.mkdir())
  val sentinel = File(outputDirectory, "keep.txt")
  sentinel.writeText("do not replace")

  assertThrows(IOException::class.java) {
   UnityFsBundleRewriter.rewriteNode(bundle, outputDirectory, node.path, ByteArray(300) { 9 })
  }

  assertTrue(outputDirectory.isDirectory)
  assertEquals("do not replace", sentinel.readText())
  assertTrue(bundle.readBytes().contentEquals(original))
  assertTrue(
   "Temporary bundle files must be removed after a failed publish",
   parent.listFiles().orEmpty().none { it.name.startsWith(".arabify-unityfs-") }
  )
  bundle.delete()
  parent.deleteRecursively()
 }


 @Test fun refusesGrowthAboveHeapDerivedBudgetAndPreservesDestination() {
  val node = FixtureNode("CAB-heap-budget", 4, ByteArray(100) { 1 })
  val bundle = buildBundle(listOf(node))
  val original = bundle.readBytes()
  val output = File.createTempFile("arabify-heap-budget-output", ".unity3d")
  output.writeText("keep existing output")

  assertThrows(IllegalArgumentException::class.java) {
   UnityFsBundleRewriter.rewriteNode(
    bundle,
    output,
    node.path,
    ByteArray(600 * 1024) { 2 },
    maxHeapBytes = 32L * 1024L * 1024L
   )
  }

  assertEquals("keep existing output", output.readText())
  assertTrue("The source bundle must remain byte-identical", bundle.readBytes().contentEquals(original))
  bundle.delete()
  output.delete()
 }

 @Test fun refusesExpandedStreamAboveTheConfiguredDecodedByteBudget() {
  val node = FixtureNode("CAB-budget", 4, ByteArray(100) { 1 })
  val bundle = buildBundle(listOf(node))
  val original = bundle.readBytes()
  val output = File.createTempFile("arabify-budget-output", ".unity3d")
  output.writeText("keep existing output")
  val limits = UnityFsDecodeLimits(maxTotalDecodedBytes = 200)

  assertThrows(IllegalArgumentException::class.java) {
   UnityFsBundleRewriter.rewriteNode(bundle, output, node.path, ByteArray(1_000) { 2 }, limits)
  }

  assertEquals("keep existing output", output.readText())
  assertTrue(bundle.readBytes().contentEquals(original))
  bundle.delete()
  output.delete()
 }
}
