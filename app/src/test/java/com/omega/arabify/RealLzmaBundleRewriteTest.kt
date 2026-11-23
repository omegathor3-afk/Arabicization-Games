package com.omega.arabify

import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Rewrites a real Unity 2021.3 bundle whose storage blocks are LZMA (the
 * desktop default). The rewriter re-chunks storage as raw/LZ4, which every
 * Unity version reads; the rewritten bundle is verified with our own parser
 * and copied to build/realFixtures for the sandbox UnityPy check.
 */
class RealLzmaBundleRewriteTest {

    private fun resourceBytes(name: String): ByteArray {
        val stream = javaClass.classLoader.getResourceAsStream("unityfixtures/$name")
        assertNotNull("missing fixture $name", stream)
        return stream!!.use { it.readBytes() }
    }

    @Test
    fun rewriteRealLzmaStorageBundle() {
        val bundle = File.createTempFile("arabify-lzma-real", ".bundle")
        bundle.writeBytes(resourceBytes("2021.3.0f1_assetbundle"))
        val cabName = "CAB-5d40f7cad7c871cf2ad2af19ac542994"
        val arabicText = "ابدأ اللعبة\nالخيارات"
        val serialized = UnityTestFixtures.serializedFileV22(
            listOf(UnityTestFixtures.TextAssetSpec(1L, "menu.txt", arabicText))
        )
        val output = File.createTempFile("arabify-lzma-real-out", ".bundle")
        val report = UnityFsBundleRewriter.rewriteNode(bundle, output, cabName, serialized)
        assertEquals(cabName, report.nodePath)

        val stream = ByteArrayOutputStream()
        val decoded = UnityFsBundleDecoder.decode(output, stream)
        assertNotNull("rewritten LZMA bundle does not decode", decoded)
        val nodes = decoded!!.blockInfo.nodes.associateBy { it.path }
        assertEquals(
            setOf(cabName, "$cabName.resS", "$cabName.resource"),
            nodes.keys
        )
        val streamBytes = stream.toByteArray()

        // The untouched resource nodes keep their bytes.
        val originalStream = ByteArrayOutputStream()
        val originalDecoded = UnityFsBundleDecoder.decode(bundle, originalStream)!!
        val originalBytes = originalStream.toByteArray()
        listOf("$cabName.resS", "$cabName.resource").forEach { path ->
            val before = originalDecoded.blockInfo.nodes.single { it.path == path }
            val after = nodes.getValue(path)
            assertEquals(before.size, after.size)
            assertArrayEquals(
                originalBytes.copyOfRange(before.offset.toInt(), (before.offset + before.size).toInt()),
                streamBytes.copyOfRange(after.offset.toInt(), (after.offset + after.size).toInt())
            )
        }

        // The new CAB holds the Arabic TextAsset.
        val cabNode = nodes.getValue(cabName)
        val cabBytes = streamBytes.copyOfRange(
            cabNode.offset.toInt(), (cabNode.offset + cabNode.size).toInt()
        )
        val cabFile = File.createTempFile("arabify-lzma-cab", ".assets")
        cabFile.writeBytes(cabBytes)
        val inspection = cabFile.inputStream().use {
            UnitySerializedFileInspector.inspect(it, cabFile.length())
        }!!
        val content = UnityTextAssetReader.read(
            cabFile, inspection.header, inspection.typeTable!!,
            inspection.objectTable!!.objects.single { it.pathId == 1L }
        )!!
        assertEquals(arabicText, content.script)

        val outDir = File("build/realFixtures")
        outDir.mkdirs()
        output.copyTo(File(outDir, "u2021_rewritten_lzma_to_lz4.bundle"), overwrite = true)

        bundle.delete()
        output.delete()
        cabFile.delete()
    }
}
