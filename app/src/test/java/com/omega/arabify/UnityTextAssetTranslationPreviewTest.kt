package com.omega.arabify

import kotlinx.coroutines.CancellationException
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
        truncated: Boolean = false,
        fullScript: String = script
    ) = UnityTextAssetPreview(
        bundlePath = "assets/bin/Data/StreamingAssets/localization.bundle",
        serializedFilePath = "CAB/resources.assets",
        pathId = 42L,
        name = name,
        pathName = pathName,
        scriptPreview = script,
        fullScript = fullScript,
        originalCodePointCount = fullScript.codePointCount(0, fullScript.length),
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
        assertEquals(result.text, result.previewText)
        assertFalse(result.previewTruncated)
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
    fun translatesCompleteSourceWhenOnlyTheVisiblePreviewIsTruncated() = runBlocking {
        val fullSource = "Welcome to the castle.\nFind the hidden key."
        val seen = mutableListOf<String>()
        val result = UnityTextAssetPreviewTranslator.translate(
            preview(
                script = "Welcome to",
                fullScript = fullSource,
                truncated = true
            )
        ) { value ->
            seen += value
            when (value) {
                "Welcome to the castle." -> "أهلًا بك في القلعة."
                "Find the hidden key." -> "اعثر على المفتاح المخفي."
                else -> value
            }
        }

        assertEquals(
            listOf("Welcome to the castle.", "Find the hidden key."),
            seen
        )
        assertEquals(
            "أهلًا بك في القلعة.\nاعثر على المفتاح المخفي.",
            result.text
        )
        assertEquals(result.text, result.previewText)
        assertEquals(2, result.translatedStrings)
    }

    @Test
    fun rejectsExcessiveTextLinesBeforeAllocatingTheLineList() {
        val source = "A\n".repeat(UnityTextAssetPreviewInputPolicy.MAX_TEXT_LINES + 1)
        var translatorCalled = false
        val error = runCatching {
            runBlocking {
                UnityTextAssetPreviewTranslator.translate(
                    preview("A", fullScript = source)
                ) {
                    translatorCalled = true
                    "نص"
                }
            }
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertFalse(translatorCalled)
    }

    @Test
    fun rejectsExcessiveJsonNestingBeforeParsing() {
        val depth = UnityTextAssetPreviewInputPolicy.MAX_JSON_DEPTH + 1
        val source = "[".repeat(depth) + "0" + "]".repeat(depth)
        var translatorCalled = false
        val error = runCatching {
            runBlocking {
                UnityTextAssetPreviewTranslator.translate(
                    preview(source, pathName = "Assets/deep.json")
                ) {
                    translatorCalled = true
                    "نص"
                }
            }
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertFalse(translatorCalled)
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
    fun refusesUnknownTextAssetFormats() {
        val error = runCatching {
            runBlocking {
                UnityTextAssetPreviewTranslator.translate(
                    preview("binary-like payload", pathName = "Assets/data.bytes", name = "data")
                ) { "ترجمة" }
            }
        }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
    }

    @Test
    fun explicitUnsupportedPathDoesNotFallBackToSupportedDisplayName() {
        val item = preview(
            "binary-like payload",
            pathName = "Assets/data.bytes",
            name = "dialogue.txt"
        )
        assertEquals(null, UnityTextAssetPreviewTranslator.supportedFormat(item))
    }

    @Test
    fun displayNameProvidesFormatOnlyWhenPathNameIsMissing() {
        val item = preview(
            "Welcome to the castle",
            pathName = null,
            name = "dialogue.txt"
        )
        assertEquals("txt", UnityTextAssetPreviewTranslator.supportedFormat(item))
    }

    @Test
    fun propagatesCancellationFromCompleteTextAssetTranslation() = runBlocking {
        val error = runCatching {
            runBlocking {
                UnityTextAssetPreviewTranslator.translate(
                    preview("Welcome", fullScript = "Welcome\nFind the key.")
                ) {
                    throw CancellationException("cancel translation")
                }
            }
        }.exceptionOrNull()
        assertTrue(error is CancellationException)
    }
}
