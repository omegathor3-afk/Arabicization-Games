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

    @Test
    fun everyCatalogFontPassesCoverageRequirement() {
        FontSelector.catalog.forEach { f ->
            val p = cov(f)
            assertTrue("${f.id} pfB=${p.presentationFormsB}", p.hasArabicCore && p.presentationFormsB >= 80)
            assertEquals("OFL-1.1", f.licenseId)
        }
    }

    @Test
    fun pixelGameGetsPixelFont() {
        val c = FontSelector.select(game("Pixel Operator", 400, bitmap = true), ::cov)
        assertEquals(FontStyleTag.PIXEL, c.font.style)
        assertTrue(c.reason.contains("style matches"))
    }

    @Test
    fun boldSansGameGetsBoldSansFont() {
        val c = FontSelector.select(game("Xolonium", 700), ::cov)
        assertEquals(FontStyleTag.SANS_MODERN, c.font.style)
        assertEquals(700, c.font.weight)
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
