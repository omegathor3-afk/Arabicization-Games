package com.omega.arabify

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnityTextAssetPatchManifestTest {
    private data class Fixture(
        val preview: UnityTextAssetPreview,
        val result: UnityTextAssetTranslationResult,
        val reviewedText: String
    )

    private fun fixture(
        source: String = "Welcome to the old castle.",
        translated: String = "مرحبًا بك في القلعة القديمة.",
        format: String = "txt",
        pathName: String = "Assets/dialogue.$format",
        bundlePath: String = "assets/bin/Data/sharedassets0.assets",
        serializedFilePath: String = "CAB-123"
    ): Fixture {
        val preview = UnityTextAssetPreview(
            bundlePath = bundlePath,
            serializedFilePath = serializedFilePath,
            pathId = 42L,
            name = "dialogue.$format",
            pathName = pathName,
            scriptPreview = source.take(32),
            fullScript = source,
            originalCodePointCount = source.codePointCount(0, source.length),
            truncated = source.codePointCount(0, source.length) > 32
        )
        val result = UnityTextAssetTranslationResult(
            text = translated,
            previewText = translated.take(32),
            previewTruncated = translated.codePointCount(0, translated.length) > 32,
            translatedStrings = 1,
            skippedStrings = 0,
            format = format
        )
        return Fixture(preview, result, translated)
    }

    @Test
    fun exportsCompleteTextsObjectIdentityAndSha256Checksums() {
        val value = fixture()
        val manifest = UnityTextAssetPatchManifest.create(
            value.preview,
            value.result,
            value.reviewedText
        )
        val json = JSONObject(manifest.toJsonString())

        assertEquals(1, json.getInt("schemaVersion"))
        assertEquals("ARABIFY", json.getString("tool"))
        assertEquals("unity-textasset-review", json.getString("kind"))
        assertEquals(42L, json.getJSONObject("target").getLong("pathId"))
        assertEquals(49, json.getJSONObject("target").getInt("classId"))
        assertEquals(value.preview.bundlePath, json.getJSONObject("target").getString("bundlePath"))
        assertEquals(value.preview.serializedFilePath, json.getJSONObject("target").getString("serializedFilePath"))
        assertEquals(value.preview.fullScript, json.getJSONObject("source").getString("text"))
        assertEquals(manifest.sourceSha256, json.getJSONObject("source").getString("sha256"))
        assertEquals(value.reviewedText, json.getJSONObject("translation").getString("text"))
        assertEquals(manifest.reviewedSha256, json.getJSONObject("translation").getString("sha256"))
        assertEquals("ar", json.getJSONObject("translation").getString("language"))
        assertTrue(json.getJSONObject("validation").getBoolean("placeholderSequencePreserved"))
        assertFalse(json.getJSONObject("application").getBoolean("applied"))
        assertFalse(json.getJSONObject("application").getBoolean("writerAvailable"))
    }

    @Test
    fun allowsMixedArabicDigitsLatinNamesAndPreservedPrintfTokens() {
        val source = "You found %1\$s after %2\$d tries."
        val translated = "وجدت %1\$s بعد %2\$d محاولات."
        val value = fixture(source, translated)
        val manifest = UnityTextAssetPatchManifest.create(
            value.preview,
            value.result,
            value.reviewedText
        )

        assertTrue(manifest.reviewedSha256.matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun rejectsLostDuplicatedReorderedAndInventedPlaceholders() {
        val source = "You found %s after %d tries."
        val cases = listOf(
            "وجدت بعد %d محاولات." to "lost",
            "وجدت %s %s بعد %d محاولات." to "duplicated",
            "وجدت %d بعد %s محاولات." to "reordered",
            "وجدت %s بعد %d محاولات مع {enemy}." to "invented"
        )

        cases.forEach { (reviewed, _) ->
            val value = fixture(source, "وجدت %s بعد %d محاولات.")
            val error = runCatching {
                UnityTextAssetPatchManifest.create(value.preview, value.result, reviewed)
            }.exceptionOrNull()
            assertTrue(error is IllegalArgumentException)
        }
    }

    @Test
    fun exportsJsonValuesWithoutTreatingObjectBracesAsPlaceholders() {
        val source = """{"start":"Start {player}","count":4}"""
        val translated = """{"start":"ابدأ {player}","count":4}"""
        val value = fixture(
            source = source,
            translated = translated,
            format = "json"
        )
        val manifest = UnityTextAssetPatchManifest.create(
            value.preview,
            value.result,
            value.reviewedText
        )

        val json = JSONObject(manifest.toJsonString())
        assertEquals(translated, json.getJSONObject("translation").getString("text"))
    }

    @Test
    fun rejectsChangedJsonKeysAndNonStringValues() {
        val source = """{"start":"Start Game","count":4}"""
        val value = fixture(
            source = source,
            translated = """{"start":"ابدأ اللعبة","count":4}""",
            format = "json"
        )
        val changedKey = """{"begin":"ابدأ اللعبة","count":4}"""
        val changedNumber = """{"start":"ابدأ اللعبة","count":5}"""

        assertTrue(runCatching {
            UnityTextAssetPatchManifest.create(value.preview, value.result, changedKey)
        }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching {
            UnityTextAssetPatchManifest.create(value.preview, value.result, changedNumber)
        }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun rejectsInvalidEditedJsonEvenWhenTheSourceWasValid() {
        val source = """{"start":"Start Game"}"""
        val value = fixture(
            source = source,
            translated = """{"start":"ابدأ اللعبة"}""",
            format = "json"
        )
        val error = runCatching {
            UnityTextAssetPatchManifest.create(
                value.preview,
                value.result,
                """{"start":"ابدأ اللعبة"""
            )
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun rejectsArchivePathTraversalAndAbsolutePaths() {
        val traversal = fixture(bundlePath = "assets/../../outside.bundle")
        val absolute = fixture(serializedFilePath = "/tmp/CAB-123")

        assertTrue(runCatching {
            UnityTextAssetPatchManifest.create(traversal.preview, traversal.result, traversal.reviewedText)
        }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching {
            UnityTextAssetPatchManifest.create(absolute.preview, absolute.result, absolute.reviewedText)
        }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun rejectsNoOpAndOversizedReviewText() {
        val value = fixture()
        val noOp = runCatching {
            UnityTextAssetPatchManifest.create(value.preview, value.result, value.preview.fullScript)
        }.exceptionOrNull()
        assertTrue(noOp is IllegalArgumentException)

        val oversized = "\u0800".repeat(UnityTextAssetPatchManifest.MAX_REVIEWED_BYTES / 3 + 1)
        val tooLarge = runCatching {
            UnityTextAssetPatchManifest.create(value.preview, value.result, oversized)
        }.exceptionOrNull()
        assertTrue(tooLarge is IllegalArgumentException)
    }
}
