package com.omega.arabify

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class UnityMonoLocalizerTest {
    @Test fun detectsMonoFromManagedAssemblyLayout() {
        val paths = listOf(
            "assets/bin/Data/Managed/mscorlib.dll",
            "assets/bin/Data/Managed/UnityEngine.CoreModule.dll",
            "assets/bin/Data/Managed/Assembly-CSharp.dll"
        )
        assertEquals(UnityRuntimeKind.MONO, UnityMonoPackageDetector.detect(paths))
    }

    @Test fun il2cppTakesPrecedenceOverManagedLookingFiles() {
        val paths = listOf(
            "assets/bin/Data/Managed/Assembly-CSharp.dll",
            "lib/arm64-v8a/libil2cpp.so",
            "assets/bin/Data/Managed/Metadata/global-metadata.dat"
        )
        assertEquals(UnityRuntimeKind.IL2CPP, UnityMonoPackageDetector.detect(paths))
    }

    @Test fun doesNotGuessMonoWhenAssemblyCSharpIsMissing() {
        assertEquals(
            UnityRuntimeKind.UNKNOWN,
            UnityMonoPackageDetector.detect(listOf("assets/bin/Data/Managed/mscorlib.dll"))
        )
    }

    @Test fun realZipFixtureKeepsManagedAssemblyAndTranslatesStreamingAssets() = runBlocking {
        val directory = createTempDir(prefix = "arabify-unity-mono-")
        try {
            val input = File(directory, "unity-mono-fixture.apk")
            val output = File(directory, "unity-mono-transformed.apk")
            val assemblyBytes = byteArrayOf(0x4d, 0x5a, 0x01, 0x02, 0x03)
            ZipOutputStream(input.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("assets/bin/Data/Managed/Assembly-CSharp.dll"))
                zip.write(assemblyBytes)
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("assets/bin/Data/StreamingAssets/localization/en.json"))
                zip.write("""{"menu":"Start Game","hint":"Find the key"}""".toByteArray())
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("assets/bin/Data/StreamingAssets/readme.txt"))
                zip.write("# keep comment\nExit the forest".toByteArray())
                zip.closeEntry()
            }

            assertEquals(UnityRuntimeKind.MONO, UnityMonoPackageDetector.detect(input))
            val report = UnityMonoLocalizer.transformForTest(input, output) { text ->
                when (text) {
                    "Start Game" -> "ابدأ اللعبة"
                    "Find the key" -> "اعثر على المفتاح"
                    "Exit the forest" -> "اخرج من الغابة"
                    else -> text
                }
            }
            assertEquals(3, report.stringsTranslated)

            ZipFile(output).use { zip ->
                assertArrayEquals(assemblyBytes, zip.getInputStream(
                    zip.getEntry("assets/bin/Data/Managed/Assembly-CSharp.dll")
                ).use { it.readBytes() })
                val json = zip.getInputStream(zip.getEntry(
                    "assets/bin/Data/StreamingAssets/localization/en.json"
                )).bufferedReader().use { it.readText() }
                assertTrue(json.contains("ابدأ اللعبة"))
                assertTrue(json.contains("اعثر على المفتاح"))
                val txt = zip.getInputStream(zip.getEntry(
                    "assets/bin/Data/StreamingAssets/readme.txt"
                )).bufferedReader().use { it.readText() }
                assertTrue(txt.contains("# keep comment"))
                assertTrue(txt.contains("اخرج من الغابة"))
            }
        } finally {
            directory.deleteRecursively()
        }
    }
}
