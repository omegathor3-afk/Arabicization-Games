package com.omega.arabify

import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Writer differential on a real Unity 6 bundle: replaces the CAB node with a
 * TextAsset SerializedFile (Arabic content), rewrites the real container with
 * UnityFsBundleRewriter and verifies the result structurally. The rewritten
 * bundle is also copied into build/realFixtures/ so the sandbox can validate
 * it with UnityPy after the test run (documented in docs/agent-progress.md).
 */
class RealBundleRewriteDifferentialTest {

    private fun resourceBytes(name: String): ByteArray {
        val stream = javaClass.classLoader.getResourceAsStream("unityfixtures/$name")
        assertNotNull("missing fixture $name", stream)
        return stream!!.use { it.readBytes() }
    }

    @Test
    fun rewrittenRealBundleKeepsStructureAndHoldsArabicTextAsset() {
        val bundle = File.createTempFile("arabify-real-v22", ".bundle")
        bundle.writeBytes(resourceBytes("v22_packedassets_assets_all.bundle"))
        val cabName = "CAB-394ff12e47c27ee4c30d41d2747acd4b"

        val arabicText = "ابدأ اللعبة\nالخيارات"
        val serialized = UnityTestFixtures.serializedFileV22(
            listOf(
                UnityTestFixtures.TextAssetSpec(1L, "menu.txt", arabicText),
                UnityTestFixtures.TextAssetSpec(2L, "dialog.txt", "الباب مغلق.")
            )
        )
        val output = File.createTempFile("arabify-real-v22-out", ".bundle")
        val report = UnityFsBundleRewriter.rewriteNode(bundle, output, cabName, serialized)
        assertEquals(cabName, report.nodePath)

        // Structural verification with our own parser.
        val stream = ByteArrayOutputStream()
        val decoded = UnityFsBundleDecoder.decode(output, stream)
        assertNotNull("rewritten real bundle does not decode", decoded)
        val node = decoded!!.blockInfo.nodes.single { it.path == cabName }
        val streamBytes = stream.toByteArray()
        val nodeBytes = streamBytes.copyOfRange(
            node.offset.toInt(), (node.offset + node.size).toInt()
        )
        val cabFile = File.createTempFile("arabify-real-v22-cab", ".assets")
        cabFile.writeBytes(nodeBytes)
        val inspection = cabFile.inputStream().use {
            UnitySerializedFileInspector.inspect(it, cabFile.length())
        }
        assertNotNull(inspection)
        assertEquals(22, inspection!!.header.formatVersion)
        val table = inspection.objectTable!!
        val content = UnityTextAssetReader.read(
            cabFile, inspection.header, inspection.typeTable!!,
            table.objects.single { it.pathId == 1L }
        )
        assertNotNull(content)
        assertEquals("menu.txt", content!!.name)
        assertEquals(arabicText, content.script)

        // Copy for the sandbox-side UnityPy validation.
        val outDir = File("build/realFixtures")
        outDir.mkdirs()
        output.copyTo(File(outDir, "v22_rewritten_with_textasset.bundle"), overwrite = true)

        assertTrue(bundle.readBytes().isNotEmpty())
        bundle.delete()
        output.delete()
        cabFile.delete()
    }
}
