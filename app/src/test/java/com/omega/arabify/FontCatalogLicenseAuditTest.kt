package com.omega.arabify

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FontCatalogLicenseAuditTest {
    private val assets = File("src/main/assets")

    @Test
    fun everyBundledFontHasItsOwnFamilySpecificOflNotice() {
        assertTrue("font catalog must not be empty", ArabicFontCatalog.entries.isNotEmpty())
        assertEquals(ArabicFontCatalog.entries.map { it.id }, FontSelector.catalog.map { it.id })

        ArabicFontCatalog.entries.forEach { font ->
            assertTrue("${font.id}: font asset is missing", File(assets, font.assetPath).isFile)
            assertTrue("${font.id}: asset path must be relative", !font.assetPath.startsWith("/"))
            assertFalse("${font.id}: asset path traversal is forbidden", font.assetPath.split('/').any { it == ".." })
            assertTrue("${font.id}: license path must be relative", !font.licenseAssetPath.startsWith("/"))
            assertFalse("${font.id}: license path traversal is forbidden", font.licenseAssetPath.split('/').any { it == ".." })

            val noticeFile = File(assets, font.licenseAssetPath)
            assertTrue("${font.id}: exact upstream license notice is missing: ${font.licenseAssetPath}", noticeFile.isFile)
            val notice = noticeFile.readText()
            assertEquals(font.copyright, notice.lineSequence().first().trim())
            assertTrue("${font.id}: notice must include SIL OFL 1.1", notice.contains("SIL Open Font License, Version 1.1"))
            assertEquals(ArabicFontCatalog.LICENSE_OFL_1_1, font.licenseId)
            assertTrue("${font.id}: source provenance is missing", font.sourceUrl.startsWith("https://"))
        }
    }

    @Test
    fun noFamilyInheritsTheAmiriCopyrightNotice() {
        val amiriNotice = "fonts/licenses/OFL-amiri.txt"
        ArabicFontCatalog.entries.filter { it.family != "Amiri" }.forEach { font ->
            assertTrue(font.licenseAssetPath != amiriNotice)
        }
    }

    @Test
    fun catalogNeverClaimsAnUnverifiedMonospaceCandidate() {
        ArabicFontCatalog.entries.forEach { font ->
            if (ArabicFontRole.MONO in font.roles) assertTrue(font.monospace)
        }
        assertTrue(ArabicFontCatalog.entries.none { ArabicFontRole.MONO in it.roles })
    }
}
