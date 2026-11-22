package com.omega.arabify

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnityTextAssetTranslationPreviewTest {
    private fun preview(
        script: String,
        pathName: String? = "Assets/Localization/dialogue.txt",
        name: String = "dialogue",
        truncated: Boolean = false
    ) = UnityTextAssetPreview(
        bundlePath = "assets/bin/Data/StreamingAssets/localization.bundle",
        serializedFilePath = "CAB/resources.assets",
        pathId = 42L,
        name = name,
        pathName = pathName,
        scriptPreview = script,
        originalCodePointCount = script.codePointCount(0, script.length) + if (truncated) 10 else 0,
        truncated = truncated
    )

    @Test
    fun translatesJsonValuesWithoutChangingKeysOrFormatPlaceholders() = runBlocking {
        val source = """{"start":"Start game %1${'$'}s","count":4}"""
        val result = UnityTextAssetPreviewTranslator.translate(
            preview(source, pathName = "Assets/Localization/menu.json")
        ) { value ->
            value.replace("Start game", "ابدأ اللعبة")
        }

        val json = JSONObject(result.text)
        assertEquals("ابدأ اللعبة %1${'$'}s", json.getString("start"))
        assertEquals(4, json.getInt("count"))
        assertEquals(1, result.translatedStrings)
        assertEquals(0, result.skippedStrings)
        assertEquals("json", result.format)
    }

    @Test
    fun preservesTextCommentsAndRestoresNamedGameTokens() = runBlocking {
        val source = "# keep this comment\nWelcome {player}\n"
        val result = UnityTextAssetPreviewTranslator.translate(preview(source)) { value ->
            value.replace("Welcome", "أهلًا")
        }

        assertTrue(result.text.startsWith("# keep this comment\n"))
        assertTrue(result.text.contains("أهلًا {player}"))
        assertEquals(1, result.translatedStrings)
        assertEquals(0, result.skippedStrings)
    }

    @Test
    fun rejectsTranslationsThatDoNotContainArabicForEnglishSentences() = runBlocking {
        val source = "Welcome to the old castle."
        val result = UnityTextAssetPreviewTranslator.translate(preview(source)) {
            "Welcome to the ancient fortress."
        }

        assertEquals(source, result.text)
        assertEquals(0, result.translatedStrings)
        assertEquals(1, result.skippedStrings)
    }

    @Test
    fun refusesTruncatedPreviewsBeforeCallingTranslator() = runBlocking {
        var called = false
        val error = runCatching {
            UnityTextAssetPreviewTranslator.translate(
                preview("Welcome to the castle", truncated = true)
            ) {
                called = true
                "مرحبًا"
            }
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertFalse(called)
    }

    @Test(expected = IllegalStateException::class)
    fun refusesUnknownTextAssetFormats() = runBlocking {
        UnityTextAssetPreviewTranslator.translate(
            preview("binary-like payload", pathName = "Assets/data.bytes", name = "data")
        ) { "ترجمة" }
    }
}
