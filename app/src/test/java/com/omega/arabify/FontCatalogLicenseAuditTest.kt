package com.omega.arabify

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FontCatalogLicenseAuditTest {
    private val assets = File("src/main/assets")

    @Test
    fun everyBundledFontHasItsOwnFamilySpecificOflNotice() {
        assertTrue("font catalog must not be empty", FontSelector.catalog.isNotEmpty())

        FontSelector.catalog.forEach { font ->
            assertTrue("${font.id}: font asset is missing", File(assets, font.assetPath).isFile)
            assertTrue("${font.id}: license path must be relative", !font.licenseAssetPath.startsWith("/"))
            assertFalse("${font.id}: license path traversal is forbidden", font.licenseAssetPath.split('/').any { it == ".." })

            val noticeFile = File(assets, font.licenseAssetPath)
            assertTrue("${font.id}: exact upstream license notice is missing: ${font.licenseAssetPath}", noticeFile.isFile)
            val notice = noticeFile.readText()
            assertTrue("${font.id}: notice must identify the copyright holder", notice.lineSequence().any { it.startsWith("Copyright ") })
            assertTrue("${font.id}: notice must include SIL OFL 1.1", notice.contains("SIL Open Font License Version 1.1"))
            assertTrue("${font.id}: catalog license id and bundled notice disagree", font.licenseId == ArabicFontCatalog.LICENSE_OFL_1_1)
            assertTrue("${font.id}: source provenance is missing", font.sourceUrl.startsWith("https://"))
        }
    }

    @Test
    fun unrelatedFontFamiliesDoNotShareTheAmiriCopyrightNotice() {
        val amiriNotice = "fonts/licenses/OFL-amiri.txt"
        FontSelector.catalog.filter { it.family != "Amiri" }.forEach { font ->
            assertTrue(
                "${font.id} must not inherit Amiri's copyright notice",
                font.licenseAssetPath != amiriNotice,
            )
        }
    }
}
