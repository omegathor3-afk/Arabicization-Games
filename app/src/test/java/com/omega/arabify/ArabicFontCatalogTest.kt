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
        coveragePercent = 3, hasArabicShapingTables = false, suitableForArabic = false,
        fileSize = 1024
    )

    private fun suitableFont(path: String = "game-ar.ttf") = ArabicFontAssessment(
        path = path, format = "TTF", mappedArabicLetters = 95, totalArabicLetters = 100,
        coveragePercent = 95, hasArabicShapingTables = true, suitableForArabic = true,
        fileSize = 2048
    )

    @Test
    fun everyCatalogFontExistsAndParsesAsSuitableArabic() {
        ArabicFontCatalog.entries.forEach { entry ->
            val bytes = assetFile(entry.assetPath).readBytes()
            val assessment = ArabicFontAnalyzer.inspect(entry.assetPath, bytes)
            assertTrue(
                "${entry.family} must render Arabic suitably: coverage=${assessment.coveragePercent}% shaping=${assessment.hasArabicShapingTables}",
                assessment.suitableForArabic
            )
            assertEquals("Amiri", assessment.familyName)
        }
    }

    @Test
    fun everyCatalogEntryShipsItsLicenseText() {
        ArabicFontCatalog.entries.forEach { entry ->
            val license = assetFile(entry.licenseAssetPath).readText(Charsets.UTF_8)
            assertTrue(license.contains("SIL OPEN FONT LICENSE", ignoreCase = true))
            assertTrue(entry.copyright.isNotBlank())
        }
    }

    @Test
    fun catalogLoadValidatedLicenses() {
        // The catalog's init guard rejects unapproved licenses; the object
        // loading at all proves every entry passed. Assert it explicitly.
        assertTrue(ArabicFontCatalog.entries.isNotEmpty())
        ArabicFontCatalog.entries.forEach {
            assertEquals(ArabicFontCatalog.LICENSE_OFL_1_1, it.licenseId)
            assertTrue(!it.assetPath.startsWith("/") && !it.assetPath.contains(".."))
        }
    }

    @Test
    fun matchReturnsNullEntryWhenGameFontAlreadyCoversArabic() {
        val result = ArabicFontCatalog.match(ArabicFontRole.UI, listOf(suitableFont(), unsuitableFont()))
        assertNull(result.catalogEntry)
        assertTrue(result.reason.contains("لا حاجة"))
    }

    @Test
    fun matchPicksCatalogFontForRoleWhenNoGameFontIsSuitable() {
        val result = ArabicFontCatalog.match(ArabicFontRole.BODY, listOf(unsuitableFont()))
        assertNotNull(result.catalogEntry)
        assertEquals("Amiri", result.catalogEntry!!.family)
        assertEquals(ArabicFontCatalog.LICENSE_OFL_1_1, result.catalogEntry!!.licenseId)
        assertTrue(result.catalogEntry!!.roles.contains(ArabicFontRole.BODY))
    }

    @Test
    fun monoRoleFallsBackToCatalogHead() {
        val result = ArabicFontCatalog.match(ArabicFontRole.MONO, emptyList())
        assertNotNull(result.catalogEntry)
        assertEquals("Amiri", result.catalogEntry!!.family)
    }

    @Test
    fun matchAllCoversEveryRequestedRole() {
        val results = ArabicFontCatalog.matchAll(
            setOf(ArabicFontRole.UI, ArabicFontRole.MONO), listOf(unsuitableFont())
        )
        assertEquals(2, results.size)
        assertEquals(listOf(ArabicFontRole.UI, ArabicFontRole.MONO), results.map { it.role })
    }
}
