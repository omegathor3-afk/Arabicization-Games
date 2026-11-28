package com.omega.arabify

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArabicFontCatalogTest {
    private fun assetFile(relativePath: String): File {
        val direct = File("src/main/assets", relativePath)
        if (direct.isFile) return direct
        val fromRepoRoot = File("app/src/main/assets", relativePath)
        if (fromRepoRoot.isFile) return fromRepoRoot
        error("catalog asset not found: $relativePath")
    }

    private fun unsuitableFont(path: String = "game.ttf") = ArabicFontAssessment(
        path = path, format = "TTF", mappedArabicLetters = 3, totalArabicLetters = 100,
        coveragePercent = 3, hasArabicShapingTables = false, suitableForArabic = false, fileSize = 1024
    )

    private fun suitableFont(path: String = "game-ar.ttf") = ArabicFontAssessment(
        path = path, format = "TTF", mappedArabicLetters = 95, totalArabicLetters = 100,
        coveragePercent = 95, hasArabicShapingTables = true, suitableForArabic = true, fileSize = 2048
    )

    @Test
    fun everyCatalogFontExistsAndDeclaredShapingCapabilityMatchesTheActualFile() {
        ArabicFontCatalog.entries.forEach { entry ->
            val profile = FontAnalyzer.analyze(assetFile(entry.assetPath).readBytes())
            assertTrue("${entry.family} must cover the Arabic core", profile.hasArabicCore)
            assertEquals(
                "${entry.id}: presentation-form metadata must match actual cmap coverage",
                entry.supportsPresentationForms,
                ArabicShaper.emittedCodepoints.all(profile::supports)
            )
        }
    }

    @Test
    fun everyCatalogEntryShipsItsFamilySpecificLicenseText() {
        ArabicFontCatalog.entries.forEach { entry ->
            val license = assetFile(entry.licenseAssetPath).readText(Charsets.UTF_8)
            assertEquals(entry.copyright, license.lineSequence().first().trim())
            assertTrue(license.contains("SIL OPEN FONT LICENSE", ignoreCase = true))
            assertEquals(ArabicFontCatalog.LICENSE_OFL_1_1, entry.licenseId)
            assertTrue(entry.roles.isNotEmpty())
        }
    }

    @Test
    fun selectorAndRoleCatalogAreTheSameSingleSourceOfTruth() {
        assertEquals(ArabicFontCatalog.entries.map { it.id }, FontSelector.catalog.map { it.id })
        assertEquals(9, ArabicFontCatalog.entries.size)
    }

    @Test
    fun matchReturnsNullEntryWhenTheRoleFontAlreadyCoversArabic() {
        val result = ArabicFontCatalog.match(ArabicFontRole.UI, listOf(suitableFont(), unsuitableFont()))
        assertNull(result.catalogEntry)
        assertTrue(result.reason.contains("لا حاجة"))
    }

    @Test
    fun matchPicksOnlyAFullShapingCandidateForTheRequestedRole() {
        val result = ArabicFontCatalog.match(ArabicFontRole.BODY, listOf(unsuitableFont()))
        assertNotNull(result.catalogEntry)
        assertEquals("Amiri", result.catalogEntry!!.family)
        assertEquals(ArabicFontCatalog.LICENSE_OFL_1_1, result.catalogEntry!!.licenseId)
        assertTrue(result.catalogEntry!!.roles.contains(ArabicFontRole.BODY))
        assertTrue(result.catalogEntry!!.supportsPresentationForms)
    }

    @Test
    fun pixelRoleUsesThePixelFamilyInsteadOfTheGeneralCatalogHead() {
        val result = ArabicFontCatalog.match(ArabicFontRole.PIXEL, emptyList())
        assertEquals("Rooyin Free", result.catalogEntry?.family)
    }

    @Test
    fun monoRoleHasNoUnsafeProportionalFallback() {
        val result = ArabicFontCatalog.match(ArabicFontRole.MONO, emptyList())
        assertNull(result.catalogEntry)
        assertTrue(result.reason.contains("mono"))
        assertTrue(result.reason.contains("الحفاظ على الخط الأصلي"))
    }

    @Test
    fun matchAllCoversEveryRequestedRoleInStableOrder() {
        val results = ArabicFontCatalog.matchAll(
            setOf(ArabicFontRole.UI, ArabicFontRole.MONO, ArabicFontRole.PIXEL), listOf(unsuitableFont())
        )
        assertEquals(3, results.size)
        assertEquals(listOf(ArabicFontRole.UI, ArabicFontRole.PIXEL, ArabicFontRole.MONO), results.map { it.role })
        assertNotNull(results.first { it.role == ArabicFontRole.PIXEL }.catalogEntry)
        assertNull(results.first { it.role == ArabicFontRole.MONO }.catalogEntry)
    }
}
