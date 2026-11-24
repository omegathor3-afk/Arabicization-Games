package com.omega.arabify

/**
 * License-aware catalog of the Arabic fonts bundled with the app, plus the
 * per-role matching pipeline used when a game's own fonts cannot render
 * Arabic. Every entry must name its license explicitly; only licenses that
 * allow redistribution of unmodified font files inside an app (today: SIL
 * OFL 1.1) are accepted. A catalog font is a fallback recommendation, never
 * silently injected: the UI shows which role needs it and why.
 */
internal enum class ArabicFontRole { UI, BODY, MONO }

internal data class ArabicFontCatalogEntry(
    val family: String,
    val assetPath: String,
    val roles: Set<ArabicFontRole>,
    val licenseId: String,
    val licenseAssetPath: String,
    val copyright: String
)

internal data class ArabicFontMatch(
    val role: ArabicFontRole,
    val catalogEntry: ArabicFontCatalogEntry?,
    val reason: String
)

internal object ArabicFontCatalog {

    const val LICENSE_OFL_1_1 = "OFL-1.1"

    private val allowedLicenses = setOf(LICENSE_OFL_1_1)

    val entries: List<ArabicFontCatalogEntry> = listOf(
        ArabicFontCatalogEntry(
            family = "Amiri",
            assetPath = "fonts/Amiri-Regular.ttf",
            roles = setOf(ArabicFontRole.UI, ArabicFontRole.BODY),
            licenseId = LICENSE_OFL_1_1,
            licenseAssetPath = "fonts/OFL-Amiri.txt",
            copyright = "Copyright 2010-2022 The Amiri Project Authors (https://github.com/aliftype/amiri)"
        )
    )

    init {
        entries.forEach { entry ->
            require(entry.licenseId in allowedLicenses) {
                "خط ${entry.family} يستخدم رخصة غير معتمدة للتوزيع داخل التطبيق: ${entry.licenseId}"
            }
            require(entry.assetPath.isNotBlank() && !entry.assetPath.startsWith("/") && !entry.assetPath.contains("..")) {
                "مسار خط غير آمن في الكتالوج: ${entry.assetPath}"
            }
        }
    }

    /**
     * Matches one role against the game's scanned fonts. Returns null entry
     * when a game font already renders Arabic suitably (coverage + shaping
     * tables, per ArabicFontAnalyzer); otherwise the first catalog font
     * covering the role, or the catalog head as the general fallback.
     */
    fun match(role: ArabicFontRole, gameFonts: List<ArabicFontAssessment>): ArabicFontMatch {
        val suitable = ArabicFontAnalyzer.rank(gameFonts).firstOrNull { it.suitableForArabic }
        if (suitable != null) {
            return ArabicFontMatch(role, null, "خط اللعبة ${suitable.path} يغطي العربية بنسبة ${suitable.coveragePercent}% مع جداول تشكيل؛ لا حاجة لخط بديل.")
        }
        val entry = entries.firstOrNull { role in it.roles } ?: entries.firstOrNull()
        return if (entry != null) {
            ArabicFontMatch(role, entry, "لا يوجد في اللعبة خط مناسب للعربية لهذا الدور؛ المرشح: ${entry.family} (${entry.licenseId}).")
        } else {
            ArabicFontMatch(role, null, "الكتالوج فارغ؛ لا يمكن ترشيح خط بديل.")
        }
    }

    fun matchAll(roles: Set<ArabicFontRole>, gameFonts: List<ArabicFontAssessment>): List<ArabicFontMatch> =
        roles.sortedBy { it.ordinal }.map { match(it, gameFonts) }
}
