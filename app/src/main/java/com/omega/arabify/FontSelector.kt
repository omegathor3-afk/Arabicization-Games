package com.omega.arabify

import java.util.Locale

/** Compatibility name; all metadata now comes directly from ArabicFontCatalog. */
internal typealias SelectableFont = ArabicFontCatalogEntry

internal data class FontChoice(
    val font: SelectableFont,
    val score: Int,
    val reason: String,
)

/**
 * Role-aware selection. A candidate must be approved for the requested role
 * and its actual font file must cover every glyph emitted by ArabicShaper.
 * Monospace is a hard compatibility requirement: until a fixed-width Arabic
 * candidate passes the same gates, the original mono font is preserved.
 */
internal object FontSelector {
    val catalog: List<SelectableFont> get() = ArabicFontCatalog.entries

    fun inferStyle(game: FontAnalyzer.FontProfile): FontStyleTag = when {
        game.pixelStyle -> FontStyleTag.PIXEL
        game.familyName.contains("amiri", ignoreCase = true) ||
            game.familyName.contains("naskh", ignoreCase = true) ||
            game.familyName.contains("serif", ignoreCase = true) -> FontStyleTag.NASKH
        game.familyName.contains("cairo", ignoreCase = true) ||
            game.familyName.contains("kufi", ignoreCase = true) -> FontStyleTag.KUFI_GEOMETRIC
        else -> FontStyleTag.SANS_MODERN
    }

    /** Infer a role from both the actual font profile and its archive path. */
    fun inferRole(game: FontAnalyzer.FontProfile, assetPath: String? = null): ArabicFontRole {
        val identity = "${assetPath.orEmpty()} ${game.familyName}".lowercase(Locale.ROOT)
        return when {
            game.monospace || Regex("(^|[/_. -])(mono|monospace|code|console)([/_. -]|$)").containsMatchIn(identity) ->
                ArabicFontRole.MONO
            game.pixelStyle || listOf("pixel", "bitmap", "retro").any(identity::contains) -> ArabicFontRole.PIXEL
            listOf("title", "headline", "display", "logo").any(identity::contains) -> ArabicFontRole.TITLE
            listOf("dialogue", "dialog").any(identity::contains) -> ArabicFontRole.DIALOGUE
            identity.contains("body") -> ArabicFontRole.BODY
            else -> ArabicFontRole.UI
        }
    }

    fun select(
        game: FontAnalyzer.FontProfile,
        role: ArabicFontRole = inferRole(game),
        coverage: (SelectableFont) -> FontAnalyzer.FontProfile,
    ): FontChoice {
        require(!game.isIconFont) {
            "Refusing to replace recognized icon font '${game.familyName}'; its glyphs may encode game UI symbols."
        }
        return pick(game, role, coverage)
    }

    /** Merge mode preserves original icon glyphs and adds only the chosen Arabic glyphs. */
    fun selectForMerge(
        game: FontAnalyzer.FontProfile,
        role: ArabicFontRole = inferRole(game),
        coverage: (SelectableFont) -> FontAnalyzer.FontProfile,
    ): FontChoice = pick(game, role, coverage)

    private fun pick(
        game: FontAnalyzer.FontProfile,
        role: ArabicFontRole,
        coverage: (SelectableFont) -> FontAnalyzer.FontProfile,
    ): FontChoice {
        val wanted = inferStyle(game)
        val candidates = catalog.filter {
            role in it.roles && it.supportsPresentationForms && (role != ArabicFontRole.MONO || it.monospace)
        }
        val scored = candidates.mapNotNull { font ->
            val profile = coverage(font)
            if (!profile.hasArabicCore || !ArabicShaper.emittedCodepoints.all(profile::supports)) return@mapNotNull null
            val styleScore = if (font.style == wanted) 60 else 0
            val weightScore = 40 - minOf(40, kotlin.math.abs(font.weight - game.weightClass) / 10)
            font to (styleScore + weightScore)
        }.sortedByDescending { it.second }

        val (best, score) = scored.firstOrNull() ?: throw IllegalArgumentException(
            "no verified Arabic font candidate for role '${role.name.lowercase()}'; preserve the original font",
        )
        val matched = best.style == wanted
        val reason = buildString {
            append("Game font '${game.familyName}' maps to role ${role.name.lowercase()} and style ${wanted.name.lowercase()} (weight ${game.weightClass}")
            if (game.bitmapStrikes) append(", bitmap strikes")
            if (game.monospace) append(", monospace")
            append("). Chose ${best.family} (${best.licenseId}, weight ${best.weight}): ")
            append(if (matched) "style matches" else "no verified catalog font of that style for this role")
            append(", score $score.")
        }
        return FontChoice(best, score, reason)
    }
}
