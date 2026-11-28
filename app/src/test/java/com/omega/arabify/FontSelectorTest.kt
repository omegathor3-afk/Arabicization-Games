package com.omega.arabify

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FontSelectorTest {
    private val assets = File("src/main/assets")
    private fun cov(font: SelectableFont) = FontAnalyzer.analyze(File(assets, font.assetPath).readBytes())
    private fun game(family: String, weight: Int, bitmap: Boolean = false, mono: Boolean = false) =
        FontAnalyzer.FontProfile("TTF", family, "Regular", weight, false, false, mono, bitmap, 1000, 0, 0, 0, emptyList())

    private val shapingCapable = setOf("amiri-regular", "noto-naskh", "rooyin-regular", "rooyin-bold")
    private val coreOnly = setOf("tajawal-regular", "tajawal-bold", "cairo", "almarai-regular", "almarai-bold")

    @Test
    fun catalogIsFullyClassifiedByActualShaperGlyphCoverageAndRole() {
        assertEquals(shapingCapable + coreOnly, FontSelector.catalog.map { it.id }.toSet())
        FontSelector.catalog.forEach { font ->
            assertEquals("OFL-1.1", font.licenseId)
            assertTrue(font.roles.isNotEmpty())
            val profile = cov(font)
            assertTrue("${font.id} must cover the Arabic core", profile.hasArabicCore)
            assertEquals(font.supportsPresentationForms, ArabicShaper.emittedCodepoints.all(profile::supports))
        }
    }

    @Test
    fun onlyFontsCoveringTheFullShaperEmitSetAreSelectable() {
        FontSelector.catalog.forEach { font ->
            val profile = cov(font)
            val complete = ArabicShaper.emittedCodepoints.all(profile::supports)
            assertEquals("${font.id} shaping capability", font.id in shapingCapable, complete)
        }
    }

    @Test
    fun selectionNeverReturnsAFontMissingShapedForms() {
        listOf(
            game("fontello-like", 400),
            game("Xolonium", 700),
            game("Pixel Operator", 400, bitmap = true),
        ).forEach { original ->
            val choice = FontSelector.selectForMerge(original) { font -> cov(font) }
            assertTrue(choice.font.id in shapingCapable)
        }
    }

    @Test
    fun pixelGameGetsPixelFont() {
        val choice = FontSelector.select(game("Pixel Operator", 400, bitmap = true)) { font -> cov(font) }
        assertEquals(FontStyleTag.PIXEL, choice.font.style)
        assertTrue(choice.font.family.startsWith("Rooyin"))
        assertTrue(choice.reason.contains("style matches"))
    }

    @Test
    fun uiAndTitleRolesCanChooseDifferentVerifiedFamilies() {
        val original = game("Xolonium", 400)
        val ui = FontSelector.select(original, ArabicFontRole.UI) { font -> cov(font) }
        val title = FontSelector.select(original, ArabicFontRole.TITLE) { font -> cov(font) }
        assertEquals("Noto Naskh Arabic", ui.font.family)
        assertEquals("Amiri", title.font.family)
        assertTrue(ui.font.id != title.font.id)
        assertTrue(ui.reason.contains("ui"))
        assertTrue(title.reason.contains("title"))
    }

    @Test
    fun boldSansGameFallsBackWithAnHonestRoleAndStyleReason() {
        val choice = FontSelector.select(game("Xolonium", 700), ArabicFontRole.UI) { font -> cov(font) }
        assertTrue(choice.font.id in shapingCapable)
        assertTrue(choice.reason.contains("no verified catalog font of that style"))
    }

    @Test
    fun monospaceGameIsPreservedWhenNoArabicMonospaceCandidateExists() {
        val failure = runCatching {
            FontSelector.select(game("Terminal Mono", 400, mono = true)) { font -> cov(font) }
        }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure?.message.orEmpty().contains("role 'mono'"))
        assertTrue(failure?.message.orEmpty().contains("preserve the original font"))
    }

    @Test
    fun roleInferenceUsesFontPathAndDoesNotMistakeMonoForGeometricSans() {
        val profile = game("Game Font", 400)
        assertEquals(ArabicFontRole.UI, FontSelector.inferRole(profile, "assets/fonts/font.ttf"))
        assertEquals(ArabicFontRole.TITLE, FontSelector.inferRole(profile, "assets/fonts/title.ttf"))
        assertEquals(ArabicFontRole.DIALOGUE, FontSelector.inferRole(profile, "assets/fonts/dialogue.ttf"))
        assertEquals(ArabicFontRole.MONO, FontSelector.inferRole(profile, "assets/fonts/console.ttf"))
    }

    @Test
    fun refusesToReplaceRecognizedIconFonts() {
        val failure = runCatching {
            FontSelector.select(game("fontello", 400)) { font -> cov(font) }
        }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure?.message.orEmpty().contains("icon font"))
    }
}
