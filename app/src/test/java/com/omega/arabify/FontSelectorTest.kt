package com.omega.arabify

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FontSelectorTest {
    private val assets = File("src/main/assets")
    private fun cov(f: SelectableFont) = FontAnalyzer.analyze(File(assets, f.assetPath).readBytes())
    private fun game(family: String, weight: Int, bitmap: Boolean = false, mono: Boolean = false) =
        FontAnalyzer.FontProfile("TTF", family, "Regular", weight, false, false, mono, bitmap, 1000, 0, 0, 0, emptyList())

    private val shapingCapable = setOf("amiri-regular", "noto-naskh", "rooyin-regular", "rooyin-bold")
    private val coreOnly = setOf("tajawal-regular", "tajawal-bold", "cairo", "almarai-regular", "almarai-bold")

    @Test
    fun catalogIsFullyClassifiedByShapingCapability() {
        assertEquals(
            FontSelector.catalog.map { it.id }.toSet(),
            shapingCapable + coreOnly,
        )
        FontSelector.catalog.forEach { f ->
            assertEquals("OFL-1.1", f.licenseId)
            val p = cov(f)
            assertTrue("${f.id} must cover the Arabic core", p.hasArabicCore)
        }
    }

    // A font missing any codepoint ArabicShaper emits renders incomplete words at
    // runtime (libGDX drops missing glyphs silently). Tajawal/Cairo/Almarai omit the
    // isolated forms (U+FE8D etc.), so the gate must exclude them.
    @Test
    fun onlyFontsCoveringTheFullShaperEmitSetAreSelectable() {
        FontSelector.catalog.forEach { f ->
            val p = cov(f)
            val complete = ArabicShaper.emittedCodepoints.all(p::supports)
            assertEquals("${f.id} shaping capability", f.id in shapingCapable, complete)
        }
    }

    @Test
    fun selectionNeverReturnsAFontMissingShapedForms() {
        listOf(
            game("fontello-like", 400),
            game("Xolonium", 700),
            game("Pixel Operator", 400, bitmap = true),
        ).forEach { g ->
            val c = FontSelector.selectForMerge(g, ::cov)
            assertTrue(c.font.id in shapingCapable)
        }
    }

    @Test
    fun pixelGameGetsPixelFont() {
        val c = FontSelector.select(game("Pixel Operator", 400, bitmap = true), ::cov)
        assertEquals(FontStyleTag.PIXEL, c.font.style)
        assertTrue(c.reason.contains("style matches"))
    }

    @Test
    fun boldSansGameFallsBackWithHonestReason() {
        // No SANS_MODERN catalog font covers the full shaper emit set, so a bold sans
        // game gets the closest shaping-capable font and the reason says so.
        val c = FontSelector.select(game("Xolonium", 700), ::cov)
        assertTrue(c.font.id in shapingCapable)
        assertTrue(c.reason.contains("no catalog font of that style"))
    }

    @Test
    fun monospaceGameFallsBackWithHonestReason() {
        val c = FontSelector.select(game("Mono", 400, mono = true), ::cov)
        assertTrue(c.reason.isNotBlank())
    }

    @Test
    fun refusesToReplaceRecognizedIconFonts() {
        val failure = runCatching {
            FontSelector.select(game("fontello", 400), ::cov)
        }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure?.message.orEmpty().contains("icon font"))
    }
}
