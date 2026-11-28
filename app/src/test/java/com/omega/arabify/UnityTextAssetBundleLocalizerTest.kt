package com.omega.arabify

import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class UnityTextAssetBundleLocalizerTest {

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun buildGameBundle(dir: File): File {
        val serialized = UnityTestFixtures.serializedFileV17(
            listOf(
                UnityTestFixtures.TextAssetSpec(1L, "menu.txt", "Start Game\nShop"),
                UnityTestFixtures.TextAssetSpec(2L, "dialog.txt", "The gate is locked.")
            )
        )
        val resource = "raw-resource-bytes-untouched".toByteArray(Charsets.UTF_8)
        val bundleBytes = UnityTestFixtures.unityFs(
            listOf(
                UnityTestFixtures.NodeSpec("CAB-menu", 4, serialized),
                UnityTestFixtures.NodeSpec("CAB-menu.resS", 0, resource)
            )
        )
        val file = File(dir, "game.unity3d")
        file.writeBytes(bundleBytes)
        return file
    }

    private val fakeArabic: suspend (String) -> String = { text ->
        when (text) {
            "Start Game" -> "ابدأ اللعبة"
            "Shop" -> "المتجر" // non-glossary: glossary hits bypass the callback
            "The gate is locked." -> "البوابة مقفلة."
            else -> error("unexpected text: $text")
        }
    }

    @Test
    fun endToEndBundleLocalization() {
        runBlocking {
        val dir = createTempDir()
        val bundle = buildGameBundle(dir)
        val originalBytes = bundle.readBytes()
        val output = File(dir, "game.ar.unity3d")
        val report = UnityTextAssetBundleLocalizer.localizeTextAsset(
            bundle = bundle,
            outputBundle = output,
            nodePath = "CAB-menu",
            pathId = 1L,
            workingDir = dir,
            translate = fakeArabic
        )
        assertEquals("txt", report.format)
        assertEquals(2, report.translatedStrings)
        assertEquals(0, report.skippedStrings)
        assertEquals("CAB-menu", report.rewrite.nodePath)

        // The source bundle is untouched.
        assertArrayEquals(originalBytes, bundle.readBytes())

        // The final bundle decodes, keeps every node, and holds the translation.
        val stream = ByteArrayOutputStream()
        val decoded = UnityFsBundleDecoder.decode(output, stream)!!
        assertEquals(listOf("CAB-menu", "CAB-menu.resS"), decoded.blockInfo.nodes.map { it.path })
        val streamBytes = stream.toByteArray()

        val resNode = decoded.blockInfo.nodes.single { it.path == "CAB-menu.resS" }
        val resBytes = streamBytes.copyOfRange(
            resNode.offset.toInt(), (resNode.offset + resNode.size).toInt()
        )
        assertEquals("raw-resource-bytes-untouched", String(resBytes, Charsets.UTF_8))

        val cabNode = decoded.blockInfo.nodes.single { it.path == "CAB-menu" }
        val cabBytes = streamBytes.copyOfRange(
            cabNode.offset.toInt(), (cabNode.offset + cabNode.size).toInt()
        )
        val cabFile = File(dir, "final.assets")
        cabFile.writeBytes(cabBytes)
        val inspection = cabFile.inputStream().use {
            UnitySerializedFileInspector.inspect(it, cabFile.length())
        }!!
        val table = inspection.objectTable!!
        val patched = UnityTextAssetReader.read(
            cabFile, inspection.header, inspection.typeTable!!,
            table.objects.single { it.pathId == 1L }
        )!!
        assertEquals("menu.txt", patched.name)
        assertEquals("ابدأ اللعبة\nالمتجر", patched.script)
        // The sibling TextAsset in the same SerializedFile is preserved.
        val sibling = UnityTextAssetReader.read(
            cabFile, inspection.header, inspection.typeTable,
            table.objects.single { it.pathId == 2L }
        )!!
        assertEquals("The gate is locked.", sibling.script)
        dir.deleteRecursively()
        }
    }

    @Test
    fun missingNodeIsRefusedInArabic() {
        runBlocking {
        val dir = createTempDir()
        val bundle = buildGameBundle(dir)
        val output = File(dir, "out.unity3d")
        val error = assertThrows(IllegalStateException::class.java) {
            runBlocking {
                UnityTextAssetBundleLocalizer.localizeTextAsset(
                    bundle, output, "CAB-missing", 1L, dir, fakeArabic
                )
            }
        }
        assertTrue(error.message!!.contains("لم يتم العثور على العقدة"))
        assertTrue(!output.exists())
        dir.deleteRecursively()
        }
    }

    @Test
    fun outputNeverOverwritesSource() {
        runBlocking {
        val dir = createTempDir()
        val bundle = buildGameBundle(dir)
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                UnityTextAssetBundleLocalizer.localizeTextAsset(
                    bundle, bundle, "CAB-menu", 1L, dir, fakeArabic
                )
            }
        }
        dir.deleteRecursively()
        }
    }
}
