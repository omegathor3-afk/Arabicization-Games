package com.omega.arabify

import java.io.ByteArrayOutputStream
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Differential tests against real Unity-built binaries from Unity's official
 * UnityDataTools test data (see the NOTICE next to the fixtures). Expected
 * node lists and object inventories were generated with UnityPy 1.25.4 and
 * checked in as oracle.json, so our parsers are compared against the
 * reference implementation, not only against synthetic fixtures.
 */
class RealUnityFixturesDifferentialTest {

    private fun resourceBytes(name: String): ByteArray {
        val stream = javaClass.classLoader.getResourceAsStream("unityfixtures/$name")
        assertNotNull("missing fixture $name", stream)
        return stream!!.use { it.readBytes() }
    }

    private val oracle: JSONObject by lazy {
        JSONObject(String(resourceBytes("oracle.json"), Charsets.UTF_8))
    }

    private fun expectedObjects(bundleKey: String, fileName: String): List<Triple<Long, Int, Long>> {
        val array = oracle.getJSONObject(bundleKey).getJSONObject("serialized")
            .getJSONObject(fileName).getJSONArray("objects")
        return (0 until array.length()).map { i ->
            val obj = array.getJSONObject(i)
            Triple(obj.getLong("path_id"), obj.getInt("class_id"), obj.getLong("byte_size"))
        }
    }

    private fun expectedNodes(bundleKey: String): List<String> {
        val array = oracle.getJSONObject(bundleKey).getJSONArray("node_details")
        return (0 until array.length()).map { array.getJSONObject(it).getString("path") }
    }

    private fun writeTemp(bytes: ByteArray, suffix: String): File {
        val file = File.createTempFile("arabify-real", suffix)
        file.writeBytes(bytes)
        return file
    }

    private fun assertObjectsMatch(
        inspection: UnitySerializedFileInspection,
        expected: List<Triple<Long, Int, Long>>
    ) {
        val table = inspection.objectTable
        assertNotNull(table)
        val actual = table!!.objects.map { obj ->
            val classId = inspection.typeTable!!.records[obj.typeId].classId
            Triple(obj.pathId, classId, obj.byteSize)
        }.sortedBy { it.first }
        assertEquals(expected, actual)
    }

    private fun extractNode(bundle: File, nodePath: String): ByteArray {
        val stream = ByteArrayOutputStream()
        val decoded = UnityFsBundleDecoder.decode(bundle, stream)
        assertNotNull(decoded)
        val node = decoded!!.blockInfo.nodes.single { it.path == nodePath }
        val bytes = stream.toByteArray()
        return bytes.copyOfRange(node.offset.toInt(), (node.offset + node.size).toInt())
    }

    @Test
    fun real2021BundleDecodesWithSameNodesAsUnityPy() {
        val bundle = writeTemp(resourceBytes("2021.3.0f1_assetbundle"), ".bundle")
        val stream = ByteArrayOutputStream()
        val decoded = UnityFsBundleDecoder.decode(bundle, stream)
        assertNotNull("our decoder refused a real Unity 2021.3 bundle", decoded)
        val nodes = decoded!!.blockInfo.nodes.map { it.path }.sorted()
        assertEquals(expectedNodes("u2021_bundle"), nodes)
        bundle.delete()
    }

    @Test
    fun real2021CabObjectInventoryMatchesUnityPy() {
        val bundle = writeTemp(resourceBytes("2021.3.0f1_assetbundle"), ".bundle")
        val cabName = expectedNodes("u2021_bundle").single { !it.endsWith(".resS") && !it.endsWith(".resource") }
        val cabBytes = extractNode(bundle, cabName)
        val cabFile = writeTemp(cabBytes, ".assets")
        val inspection = cabFile.inputStream().use {
            UnitySerializedFileInspector.inspect(it, cabFile.length())
        }
        assertNotNull("our inspector refused a real Unity 2021.3 SerializedFile", inspection)
        assertEquals(22, inspection!!.header.formatVersion)
        assertNotNull(inspection.typeTable)
        assertObjectsMatch(inspection, expectedObjects("u2021_bundle", cabName))
        bundle.delete()
        cabFile.delete()
    }

    @Test
    fun realUnity6SerializedFileV22InventoryMatchesUnityPy() {
        val file = writeTemp(resourceBytes("v22_monoscriptbundle.serializedfile"), ".assets")
        val inspection = file.inputStream().use {
            UnitySerializedFileInspector.inspect(it, file.length())
        }
        assertNotNull("our inspector refused a real Unity 6 SerializedFile", inspection)
        assertEquals(22, inspection!!.header.formatVersion)
        assertNotNull(inspection.typeTable)
        assertObjectsMatch(
            inspection,
            expectedObjects("v22_serialized", "v22_monoscriptbundle.serializedfile")
        )
        file.delete()
    }

    @Test
    fun realUnity6BundleV22CabInventoryMatchesUnityPy() {
        val bundle = writeTemp(resourceBytes("v22_packedassets_assets_all.bundle"), ".bundle")
        val cabName = expectedNodes("v22_bundle").single()
        val cabBytes = extractNode(bundle, cabName)
        val cabFile = writeTemp(cabBytes, ".assets")
        val inspection = cabFile.inputStream().use {
            UnitySerializedFileInspector.inspect(it, cabFile.length())
        }
        assertNotNull("our inspector refused a real Unity 6 CAB SerializedFile", inspection)
        assertEquals(22, inspection!!.header.formatVersion)
        assertObjectsMatch(inspection, expectedObjects("v22_bundle", cabName))
        bundle.delete()
        cabFile.delete()
    }
}
