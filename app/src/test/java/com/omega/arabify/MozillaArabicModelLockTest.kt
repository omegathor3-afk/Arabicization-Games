package com.omega.arabify

import org.junit.Assert.*
import org.junit.Test

class MozillaArabicModelLockTest {
    @Test fun containsBothIndependentArabicDirections() {
        assertTrue(MozillaArabicModelLock.isComplete())
        assertEquals(setOf("en-ar", "ar-en"), MozillaArabicModelLock.files.map { it.direction }.toSet())
    }

    @Test fun eachDirectionHasModelLexiconAndSentencePieceVocabulary() {
        for (direction in listOf("en-ar", "ar-en")) {
            assertEquals(setOf("model", "lex", "vocab"),
                MozillaArabicModelLock.files.filter { it.direction == direction }.map { it.type }.toSet())
        }
    }

    @Test fun allRegistryHashesAreSha256AndSizesArePositive() {
        MozillaArabicModelLock.files.forEach {
            assertEquals(64, it.compressedSha256.length)
            assertTrue(it.compressedSha256.all { c -> c in "0123456789abcdef" })
            assertEquals(64, it.decompressedSha256.length)
            assertTrue(it.decompressedSha256.all { c -> c in "0123456789abcdef" })
            assertTrue(it.compressedBytes > 0)
            assertTrue(it.decompressedBytes > 0)
            assertTrue(it.registryLocation.startsWith("main-workspace/translations-models-v2/"))
        }
    }

    @Test fun recordsUseMozillaPublishedEngineAndModelLicenses() {
        assertEquals("MPL-2.0", MozillaArabicModelLock.engineLicense)
        assertEquals("MPL-2.0", MozillaArabicModelLock.modelLicense)
        assertEquals("3.0", MozillaArabicModelLock.modelVersion)
        assertEquals(40, MozillaArabicModelLock.engineCommit.length)
    }

    @Test fun packageSizesAreTrackedSeparatelyByDirection() {
        val enAr = MozillaArabicModelLock.files.filter { it.direction == "en-ar" }.sumOf { it.compressedBytes }
        val arEn = MozillaArabicModelLock.files.filter { it.direction == "ar-en" }.sumOf { it.compressedBytes }
        assertEquals(23828180L, enAr)
        assertEquals(24743247L, arEn)
    }
}
