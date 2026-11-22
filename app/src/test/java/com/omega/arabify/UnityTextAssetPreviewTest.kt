package com.omega.arabify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UnityTextAssetPreviewTest {
    @Test
    fun previewTruncatesByUnicodeCodePointWithoutSplittingSurrogatePairs() {
        val content = UnityTextAssetContent(
            name = "Game Strings",
            script = "A😀بC",
            pathName = "Assets/Strings.txt"
        )

        val preview = createUnityTextAssetPreview(
            bundlePath = "assets/bin/Data/StreamingAssets/data.bundle",
            serializedFilePath = "CAB-123/resources.assets",
            pathId = 42L,
            content = content,
            maxCodePoints = 2
        )

        assertEquals("A😀", preview?.scriptPreview)
        assertEquals(4, preview?.originalCodePointCount)
        assertTrue(preview?.truncated == true)
        assertEquals(42L, preview?.pathId)
        assertEquals("Assets/Strings.txt", preview?.pathName)
    }

    @Test
    fun previewKeepsCompleteShortTextAndBoundsDisplayMetadata() {
        val content = UnityTextAssetContent(
            name = "😀".repeat(200),
            script = "مرحبا 123",
            pathName = "x".repeat(600)
        )

        val preview = createUnityTextAssetPreview(
            bundlePath = "bundle",
            serializedFilePath = "CAB/data.assets",
            pathId = 9L,
            content = content,
            maxCodePoints = 20
        )

        assertEquals("مرحبا 123", preview?.scriptPreview)
        assertFalse(preview?.truncated ?: true)
        assertEquals(128, preview?.name?.codePointCount(0, preview.name.length))
        assertEquals(512, preview?.pathName?.codePointCount(0, preview.pathName!!.length))
    }

    @Test
    fun blankTextIsNotPresentedAsATranslationCandidate() {
        val content = UnityTextAssetContent(
            name = "Empty",
            script = " \n\t ",
            pathName = null
        )

        assertNull(createUnityTextAssetPreview(
            bundlePath = "bundle",
            serializedFilePath = "CAB/data.assets",
            pathId = 1L,
            content = content,
            maxCodePoints = 20
        ))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnboundedPreviewRequests() {
        createUnityTextAssetPreview(
            bundlePath = "bundle",
            serializedFilePath = "CAB/data.assets",
            pathId = 1L,
            content = UnityTextAssetContent("name", "text", null),
            maxCodePoints = 100_000
        )
    }
}
