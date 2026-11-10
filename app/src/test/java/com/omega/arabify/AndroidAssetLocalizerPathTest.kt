package com.omega.arabify

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AndroidAssetLocalizerPathTest {
    @Test fun unityStreamingAssetsScopeIncludesOnlySupportedTextFiles() {
        val root = listOf("assets/bin/Data/StreamingAssets/")
        assertTrue(AndroidAssetLocalizer.isSupportedTextAsset(
            "assets/bin/Data/StreamingAssets/localization/en.json", 120, root
        ))
        assertTrue(AndroidAssetLocalizer.isSupportedTextAsset(
            "assets/bin/Data/StreamingAssets/dialogue.txt", 80, root
        ))
        assertFalse(AndroidAssetLocalizer.isSupportedTextAsset(
            "assets/bin/Data/Resources/config.json", 120, root
        ))
        assertFalse(AndroidAssetLocalizer.isSupportedTextAsset(
            "assets/bin/Data/StreamingAssets/texture.png", 120, root
        ))
        assertFalse(AndroidAssetLocalizer.isSupportedTextAsset(
            "assets/bin/Data/StreamingAssets/empty.txt", 0, root
        ))
    }

    @Test fun unityStreamingAssetsRejectsTraversalAndAmbiguousPaths() {
        val root = listOf("assets/bin/Data/StreamingAssets/")
        assertFalse(AndroidAssetLocalizer.isSupportedTextAsset(
            "assets/bin/Data/StreamingAssets/../Resources/config.json", 120, root
        ))
        assertFalse(AndroidAssetLocalizer.isSupportedTextAsset(
            "assets/bin/Data/StreamingAssets/../../../../res/raw/payload.txt", 120, root
        ))
        assertFalse(AndroidAssetLocalizer.isSupportedTextAsset(
            "/assets/bin/Data/StreamingAssets/dialogue.txt", 80, root
        ))
        assertFalse(AndroidAssetLocalizer.isSupportedTextAsset(
            "assets/bin/Data/StreamingAssets//dialogue.txt", 80, root
        ))
        assertFalse(AndroidAssetLocalizer.isSupportedTextAsset(
            "assets/bin/Data/StreamingAssets/./dialogue.txt", 80, root
        ))
        assertFalse(AndroidAssetLocalizer.isSupportedTextAsset(
            "assets/bin/Data/StreamingAssets/dialogue.txt", 80,
            listOf("assets/bin/Data/StreamingAssets/../")
        ))
    }

    @Test fun normalizesBackslashSeparatorsBeforeApplyingUnityAllowlist() {
        val root = listOf("assets/bin/Data/StreamingAssets/")
        assertTrue(AndroidAssetLocalizer.isSupportedTextAsset(
            "assets\\bin\\Data\\StreamingAssets\\dialogue.txt", 80, root
        ))
    }

    @Test fun boundedArchiveReadAcceptsExactLimit() {
        val bytes = "safe archive text".toByteArray(Charsets.UTF_8)
        val result = BoundedArchiveInput.readAll(ByteArrayInputStream(bytes), bytes.size.toLong())
        assertArrayEquals(bytes, result)
    }

    @Test fun boundedArchiveReadRejectsActualBytesBeyondDeclaredBudget() {
        val bytes = ByteArray(4097) { (it % 251).toByte() }
        try {
            BoundedArchiveInput.readAll(ByteArrayInputStream(bytes), 4096)
            fail("Expected an archive-entry byte-limit failure")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("byte limit"))
        }
    }

    @Test fun boundedArchiveCopyStreamsWithinLimit() {
        val bytes = ByteArray(128 * 1024) { (it % 251).toByte() }
        val output = ByteArrayOutputStream()
        val copied = BoundedArchiveInput.copy(ByteArrayInputStream(bytes), output, bytes.size.toLong())
        assertTrue(copied == bytes.size.toLong())
        assertArrayEquals(bytes, output.toByteArray())
    }

    @Test fun boundedArchiveCopyStopsBeforeExpandingPastBudget() {
        val bytes = ByteArray(4097) { (it % 251).toByte() }
        val output = ByteArrayOutputStream()
        try {
            BoundedArchiveInput.copy(ByteArrayInputStream(bytes), output, 4096)
            fail("Expected an archive-copy byte-limit failure")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("copy limit"))
            assertTrue(output.size() <= 4096)
        }
    }

    @Test fun defaultAndroidRootsRemainSupported() {
        assertTrue(AndroidAssetLocalizer.isSupportedTextAsset("assets/localization/en.json", 64))
        assertTrue(AndroidAssetLocalizer.isSupportedTextAsset("res/raw/dialogue.txt", 64))
        assertFalse(AndroidAssetLocalizer.isSupportedTextAsset("lib/arm64-v8a/libgame.so", 64))
    }
}
