package com.omega.arabify

/**
 * The single source of truth for redistributable Arabic fonts.
 * A catalog entry is a candidate, not permission to replace a game font:
 * callers must still check actual glyph coverage and role compatibility.
 */
internal enum class ArabicFontRole { UI, BODY, DIALOGUE, TITLE, PIXEL, MONO }
internal enum class FontStyleTag { NASKH, KUFI_GEOMETRIC, SANS_MODERN, PIXEL }

internal data class ArabicFontCatalogEntry(
    val id: String,
    val family: String,
    val assetPath: String,
    val roles: Set<ArabicFontRole>,
    val style: FontStyleTag,
    val weight: Int,
    val licenseId: String,
    val licenseAssetPath: String,
    val sourceUrl: String,
    val copyright: String,
    /** True only when the bundled file contains every ArabicShaper output glyph. */
    val supportsPresentationForms: Boolean,
    /** No bundled candidate is currently approved for fixed-width replacement. */
    val monospace: Boolean = false,
)

internal data class ArabicFontMatch(
    val role: ArabicFontRole,
    val catalogEntry: ArabicFontCatalogEntry?,
    val reason: String,
)

internal object ArabicFontCatalog {
    const val LICENSE_OFL_1_1 = "OFL-1.1"
    private val allowedLicenses = setOf(LICENSE_OFL_1_1)
    private const val GOOGLE_FONTS = "https://raw.githubusercontent.com/google/fonts/main/ofl/"

    /** All bundled font files are listed here once; MONO intentionally has no candidate. */
    val entries: List<ArabicFontCatalogEntry> = listOf(
        ArabicFontCatalogEntry(
            id = "amiri-regular", family = "Amiri", assetPath = "fonts/catalog/amiri-regular.ttf",
            roles = setOf(ArabicFontRole.BODY, ArabicFontRole.DIALOGUE, ArabicFontRole.TITLE),
            style = FontStyleTag.NASKH, weight = 400, licenseId = LICENSE_OFL_1_1,
            licenseAssetPath = "fonts/licenses/OFL-amiri.txt",
            sourceUrl = GOOGLE_FONTS + "amiri/Amiri-Regular.ttf",
            copyright = "Copyright 2010-2022 The Amiri Project Authors (https://github.com/aliftype/amiri).",
            supportsPresentationForms = true,
        ),
        ArabicFontCatalogEntry(
            id = "noto-naskh", family = "Noto Naskh Arabic", assetPath = "fonts/catalog/notonaskharabic-var.ttf",
            roles = setOf(ArabicFontRole.UI, ArabicFontRole.BODY, ArabicFontRole.DIALOGUE),
            style = FontStyleTag.NASKH, weight = 400, licenseId = LICENSE_OFL_1_1,
            licenseAssetPath = "fonts/licenses/OFL-notonaskharabic.txt",
            sourceUrl = GOOGLE_FONTS + "notonaskharabic/",
            copyright = "Copyright 2022 The Noto Project Authors (https://github.com/notofonts/arabic)",
            supportsPresentationForms = true,
        ),
        ArabicFontCatalogEntry(
            id = "cairo", family = "Cairo", assetPath = "fonts/catalog/cairo-var.ttf",
            roles = setOf(ArabicFontRole.UI, ArabicFontRole.TITLE),
            style = FontStyleTag.KUFI_GEOMETRIC, weight = 400, licenseId = LICENSE_OFL_1_1,
            licenseAssetPath = "fonts/licenses/OFL-cairo.txt",
            sourceUrl = GOOGLE_FONTS + "cairo/",
            copyright = "Copyright 2009 The Cairo Project Authors (https://github.com/Gue3bara/Cairo)",
            supportsPresentationForms = false,
        ),
        ArabicFontCatalogEntry(
            id = "tajawal-regular", family = "Tajawal", assetPath = "fonts/catalog/tajawal-regular.ttf",
            roles = setOf(ArabicFontRole.UI, ArabicFontRole.BODY, ArabicFontRole.DIALOGUE),
            style = FontStyleTag.SANS_MODERN, weight = 400, licenseId = LICENSE_OFL_1_1,
            licenseAssetPath = "fonts/licenses/OFL-tajawal.txt",
            sourceUrl = GOOGLE_FONTS + "tajawal/Tajawal-Regular.ttf",
            copyright = "Copyright 2018 Boutros International. (http://www.boutrosfonts.com)",
            supportsPresentationForms = false,
        ),
        ArabicFontCatalogEntry(
            id = "tajawal-bold", family = "Tajawal Bold", assetPath = "fonts/catalog/tajawal-bold.ttf",
            roles = setOf(ArabicFontRole.UI, ArabicFontRole.TITLE),
            style = FontStyleTag.SANS_MODERN, weight = 700, licenseId = LICENSE_OFL_1_1,
            licenseAssetPath = "fonts/licenses/OFL-tajawal.txt",
            sourceUrl = GOOGLE_FONTS + "tajawal/Tajawal-Bold.ttf",
            copyright = "Copyright 2018 Boutros International. (http://www.boutrosfonts.com)",
            supportsPresentationForms = false,
        ),
        ArabicFontCatalogEntry(
            id = "almarai-regular", family = "Almarai", assetPath = "fonts/catalog/almarai-regular.ttf",
            roles = setOf(ArabicFontRole.UI, ArabicFontRole.BODY, ArabicFontRole.DIALOGUE),
            style = FontStyleTag.SANS_MODERN, weight = 400, licenseId = LICENSE_OFL_1_1,
            licenseAssetPath = "fonts/licenses/OFL-almarai.txt",
            sourceUrl = GOOGLE_FONTS + "almarai/Almarai-Regular.ttf",
            copyright = "Copyright 2019 The Almarai Project Authors (https://github.com/JuergenWillrodt/Almarai)",
            supportsPresentationForms = false,
        ),
        ArabicFontCatalogEntry(
            id = "almarai-bold", family = "Almarai Bold", assetPath = "fonts/catalog/almarai-bold.ttf",
            roles = setOf(ArabicFontRole.UI, ArabicFontRole.TITLE),
            style = FontStyleTag.SANS_MODERN, weight = 700, licenseId = LICENSE_OFL_1_1,
            licenseAssetPath = "fonts/licenses/OFL-almarai.txt",
            sourceUrl = GOOGLE_FONTS + "almarai/Almarai-Bold.ttf",
            copyright = "Copyright 2019 The Almarai Project Authors (https://github.com/JuergenWillrodt/Almarai)",
            supportsPresentationForms = false,
        ),
        ArabicFontCatalogEntry(
            id = "rooyin-regular", family = "Rooyin Free", assetPath = "fonts/catalog/rooyinfree-regular.ttf",
            roles = setOf(ArabicFontRole.PIXEL, ArabicFontRole.BODY, ArabicFontRole.DIALOGUE),
            style = FontStyleTag.PIXEL, weight = 400, licenseId = LICENSE_OFL_1_1,
            licenseAssetPath = "fonts/licenses/OFL-rooyin.txt",
            sourceUrl = "https://github.com/MohamadDarvishi/Rooyin",
            copyright = "Copyright 2025 The Rooyin-Perfect Project Authors (https://Fontiran.com/Rooyin)",
            supportsPresentationForms = true,
        ),
        ArabicFontCatalogEntry(
            id = "rooyin-bold", family = "Rooyin Free Bold", assetPath = "fonts/catalog/rooyinfree-bold.ttf",
            roles = setOf(ArabicFontRole.PIXEL, ArabicFontRole.TITLE),
            style = FontStyleTag.PIXEL, weight = 700, licenseId = LICENSE_OFL_1_1,
            licenseAssetPath = "fonts/licenses/OFL-rooyin.txt",
            sourceUrl = "https://github.com/MohamadDarvishi/Rooyin",
            copyright = "Copyright 2025 The Rooyin-Perfect Project Authors (https://Fontiran.com/Rooyin)",
            supportsPresentationForms = true,
        ),
    )

    init {
        require(entries.map { it.id }.distinct().size == entries.size) { "duplicate Arabic font catalog id" }
        require(entries.map { it.assetPath }.distinct().size == entries.size) { "duplicate Arabic font asset path" }
        entries.forEach { entry ->
            require(entry.licenseId in allowedLicenses) { "خط ${entry.family} يستخدم رخصة غير معتمدة: ${entry.licenseId}" }
            require(isSafeRelativePath(entry.assetPath) && isSafeRelativePath(entry.licenseAssetPath)) {
                "مسار غير آمن في كتالوج الخطوط: ${entry.assetPath}"
            }
            require(entry.sourceUrl.startsWith("https://")) { "مصدر الخط غير موثق: ${entry.id}" }
            require(entry.roles.isNotEmpty() && entry.weight in 1..1000) { "بيانات دور/وزن غير صالحة: ${entry.id}" }
            require(ArabicFontRole.MONO !in entry.roles || entry.monospace) {
                "لا يجوز تصنيف خط نسبي على أنه monospace: ${entry.id}"
            }
        }
    }

    private fun isSafeRelativePath(path: String): Boolean =
        path.isNotBlank() && !path.startsWith("/") &&
            path.split('/').none { it.isBlank() || it == "." || it == ".." }

    /**
     * gameFonts must describe the font(s) used by this role. A suitable in-game
     * font wins; otherwise only a verified role-compatible candidate is returned.
     * Missing roles (notably MONO) return no candidate instead of a layout-breaking fallback.
     */
    fun match(role: ArabicFontRole, gameFonts: List<ArabicFontAssessment>): ArabicFontMatch {
        val suitable = ArabicFontAnalyzer.rank(gameFonts).firstOrNull { it.suitableForArabic }
        if (suitable != null) {
            return ArabicFontMatch(role, null, "خط اللعبة ${suitable.path} مناسب للعربية لهذا الدور؛ لا حاجة لخط بديل.")
        }
        val entry = entries.firstOrNull {
            role in it.roles && it.supportsPresentationForms && (role != ArabicFontRole.MONO || it.monospace)
        }
        return if (entry != null) {
            ArabicFontMatch(role, entry, "لا يوجد خط مناسب داخل اللعبة لهذا الدور؛ المرشح ${entry.family} (${entry.licenseId}).")
        } else {
            ArabicFontMatch(role, null, "لا يوجد مرشح عربي موثّق لهذا الدور (${role.name.lowercase()})؛ سيتم الحفاظ على الخط الأصلي.")
        }
    }

    fun matchAll(roles: Set<ArabicFontRole>, gameFonts: List<ArabicFontAssessment>): List<ArabicFontMatch> =
        roles.sortedBy { it.ordinal }.map { match(it, gameFonts) }
}
