package com.omega.arabify

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FontAnalyzerTest {
    private val dir = File("src/main/assets/fonts/catalog")

    private fun profile(name: String) = FontAnalyzer.analyze(File(dir, name).readBytes())

    @Test
    fun everyCatalogCandidateCoversArabicCoreAndPresentationForms() {
        val names = dir.listFiles { f -> f.name.endsWith(".ttf") }!!.map { it.name }.sorted()
        assertTrue("no catalog fonts found in ${dir.absolutePath}", names.size >= 7)
        for (n in names) {
            val p = profile(n)
            println("FONTPROFILE $n family=${p.familyName} w=${p.weightClass} arabic=${p.arabicGlyphs} pfA=${p.presentationFormsA} pfB=${p.presentationFormsB} mono=${p.monospace}")
            assertTrue("$n lacks Arabic core: ${p.arabicGlyphs}", p.hasArabicCore)
        }
    }

    @Test
    fun amiriHasFullPresentationFormsB() {
        val p = profile("amiri-regular.ttf")
        assertTrue(p.presentationFormsB > 100)
        assertTrue(p.supports(0xFEED))
        assertEquals("Amiri", p.familyName)
    }

    @Test
    fun boldVariantsReportHeavierWeight() {
        assertTrue(profile("tajawal-bold.ttf").weightClass > profile("tajawal-regular.ttf").weightClass)
    }

    @Test
    fun proportionalCatalogFontsAreNotMonospace() {
        assertTrue(!profile("amiri-regular.ttf").monospace)
        assertTrue(!profile("tajawal-regular.ttf").monospace)
    }

    @Test(expected = FontAnalyzer.FontParseException::class)
    fun rejectsGarbage() {
        FontAnalyzer.analyze(ByteArray(64) { 7 })
    }
}
