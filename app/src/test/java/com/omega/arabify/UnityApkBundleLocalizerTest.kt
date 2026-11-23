package com.omega.arabify

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UnityApkBundleLocalizerTest {

    private fun buildApk(dir: File): File {
        val serialized = UnityTestFixtures.serializedFileV17(
            listOf(
                UnityTestFixtures.TextAssetSpec(1L, "menu.txt", "Start Game\nOptions"),
                UnityTestFixtures.TextAssetSpec(2L, "dialog.txt", "The gate is locked.")
            )
        )
        val bundle = UnityTestFixtures.unityFs(
            listOf(
                UnityTestFixtures.NodeSpec("CAB-menu", 4, serialized),
                UnityTestFixtures.NodeSpec("CAB-menu.resS", 0, ByteArray(64) { it.toByte() })
            )
        )
        val apk = File(dir, "game.apk")
        ZipOutputStream(apk.outputStream().buffered()).use { out ->
            fun add(name: String, bytes: ByteArray) {
                out.putNextEntry(ZipEntry(name))
                out.write(bytes)
                out.closeEntry()
            }
            add("AndroidManifest.xml", byteArrayOf(3, 0, 8, 0))
            add("classes.dex", byteArrayOf(100, 101, 120, 10))
            add("assets/bin/Data/StreamingAssets/pack.unity3d", bundle)
            add("META-INF/OLD.SF", "stale signature".toByteArray())
        }
        return apk
    }

    private val fakeArabic: suspend (String) -> String = { text ->
        when (text) {
            "Start Game" -> "ابدأ اللعبة"
            "Options" -> "الخيارات"
            else -> error("unexpected text: $text")
        }
    }

    @Test
    fun localizesTextAssetsInsideBundlesInsideApk() {
        runBlocking {
            val dir = createTempDir()
            val apk = buildApk(dir)
            val originalApkBytes = apk.readBytes()
            val output = File(dir, "game.ar.apk")
            val report = UnityApkBundleLocalizer.localizeBundlesInApk(
                apk, output, File(dir, "work"), fakeArabic
            )
            assertEquals(1, report.bundlesProcessed)
            assertEquals(1, report.textAssetsTranslated)
            assertEquals(2, report.stringsTranslated)

            // Source APK untouched.
            assertArrayEquals(originalApkBytes, apk.readBytes())

            ZipFile(output).use { zip ->
                // Old signatures are dropped; other entries are byte-identical.
                assertNull(zip.getEntry("META-INF/OLD.SF"))
                assertArrayEquals(
                    byteArrayOf(3, 0, 8, 0),
                    zip.getInputStream(zip.getEntry("AndroidManifest.xml")).use { it.readBytes() }
                )
                assertArrayEquals(
                    byteArrayOf(100, 101, 120, 10),
                    zip.getInputStream(zip.getEntry("classes.dex")).use { it.readBytes() }
                )

                val bundleEntry = zip.getEntry("assets/bin/Data/StreamingAssets/pack.unity3d")
                assertNotNull(bundleEntry)
                val bundleFile = File(dir, "out.bundle")
                zip.getInputStream(bundleEntry).use { input ->
                    bundleFile.outputStream().use { input.copyTo(it) }
                }

                val stream = ByteArrayOutputStream()
                val decoded = UnityFsBundleDecoder.decode(bundleFile, stream)
                assertNotNull(decoded)
                val node = decoded!!.blockInfo.nodes.single { it.path == "CAB-menu" }
                val bytes = stream.toByteArray()
                val cabFile = File(dir, "out.assets")
                cabFile.writeBytes(
                    bytes.copyOfRange(node.offset.toInt(), (node.offset + node.size).toInt())
                )
                val inspection = cabFile.inputStream().use {
                    UnitySerializedFileInspector.inspect(it, cabFile.length())
                }!!
                val table = inspection.objectTable!!
                val translated = UnityTextAssetReader.read(
                    cabFile, inspection.header, inspection.typeTable!!,
                    table.objects.single { it.pathId == 1L }
                )!!
                assertEquals("menu.txt", translated.name)
                assertEquals("ابدأ اللعبة\nالخيارات", translated.script)
                val sibling = UnityTextAssetReader.read(
                    cabFile, inspection.header, inspection.typeTable,
                    table.objects.single { it.pathId == 2L }
                )!!
                assertEquals("The gate is locked.", sibling.script)
            }
            dir.deleteRecursively()
        }
    }

    @Test
    fun tolerantVariantReturnsNullWithoutWritingOutput() {
        runBlocking {
            val dir = createTempDir()
            val apk = File(dir, "plain.apk")
            ZipOutputStream(apk.outputStream().buffered()).use { out ->
                out.putNextEntry(ZipEntry("classes.dex"))
                out.write(byteArrayOf(1, 2, 3))
                out.closeEntry()
            }
            val output = File(dir, "out.apk")
            val report = UnityApkBundleLocalizer.tryLocalizeBundlesInApk(
                apk, output, File(dir, "work"), fakeArabic
            )
            assertNull(report)
            assertTrue(!output.exists())
            dir.deleteRecursively()
        }
    }

    @Test
    fun apkWithoutTranslatableBundlesIsRefused() {
        runBlocking {
            val dir = createTempDir()
            val apk = File(dir, "plain.apk")
            ZipOutputStream(apk.outputStream().buffered()).use { out ->
                out.putNextEntry(ZipEntry("classes.dex"))
                out.write(byteArrayOf(1, 2, 3))
                out.closeEntry()
            }
            val error = org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
                runBlocking {
                    UnityApkBundleLocalizer.localizeBundlesInApk(
                        apk, File(dir, "out.apk"), File(dir, "work"), fakeArabic
                    )
                }
            }
            assertTrue(error.message!!.contains("لم يتم العثور على TextAssets"))
            dir.deleteRecursively()
        }
    }
}
