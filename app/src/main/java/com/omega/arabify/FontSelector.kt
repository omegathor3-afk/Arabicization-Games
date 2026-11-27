package com.omega.arabify

/** Style families used to match a game font to an Arabic catalog font. */
internal enum class FontStyleTag { NASKH, KUFI_GEOMETRIC, SANS_MODERN, PIXEL }

internal data class SelectableFont(
    val id: String,
    val assetPath: String,
    val family: String,
    val style: FontStyleTag,
    val weight: Int,
    val licenseId: String,
    val sourceUrl: String,
    /** Exact upstream copyright + license notice bundled for this font family. */
    val licenseAssetPath: String,
)

internal data class FontChoice(
    val font: SelectableFont,
    val score: Int,
    val reason: String,
)

/**
 * Automatic, explainable Arabic font selection. Hard requirement: the font
 * must cover the Arabic core and every codepoint ArabicShaper can emit
 * ([ArabicShaper.emittedCodepoints]) - a font missing some of them renders
 * incomplete words, because libGDX's FreeType rasterizer silently drops
 * missing glyphs. Then score style match, weight distance and
 * proportional-vs-monospace fit. Every choice carries a human-readable reason,
 * and a weak match says so instead of pretending.
 */
internal object FontSelector {
    private const val G = "https://raw.githubusercontent.com/google/fonts/main/ofl/"
    val catalog: List<SelectableFont> = listOf(
        SelectableFont("amiri-regular", "fonts/catalog/amiri-regular.ttf", "Amiri", FontStyleTag.NASKH, 400, "OFL-1.1", G + "amiri/Amiri-Regular.ttf", "fonts/licenses/OFL-amiri.txt"),
        SelectableFont("noto-naskh", "fonts/catalog/notonaskharabic-var.ttf", "Noto Naskh Arabic", FontStyleTag.NASKH, 400, "OFL-1.1", G + "notonaskharabic/", "fonts/licenses/OFL-notonaskharabic.txt"),
        SelectableFont("cairo", "fonts/catalog/cairo-var.ttf", "Cairo", FontStyleTag.KUFI_GEOMETRIC, 400, "OFL-1.1", G + "cairo/", "fonts/licenses/OFL-cairo.txt"),
        SelectableFont("tajawal-regular", "fonts/catalog/tajawal-regular.ttf", "Tajawal", FontStyleTag.SANS_MODERN, 400, "OFL-1.1", G + "tajawal/Tajawal-Regular.ttf", "fonts/licenses/OFL-tajawal.txt"),
        SelectableFont("tajawal-bold", "fonts/catalog/tajawal-bold.ttf", "Tajawal Bold", FontStyleTag.SANS_MODERN, 700, "OFL-1.1", G + "tajawal/Tajawal-Bold.ttf", "fonts/licenses/OFL-tajawal.txt"),
        SelectableFont("almarai-regular", "fonts/catalog/almarai-regular.ttf", "Almarai", FontStyleTag.SANS_MODERN, 400, "OFL-1.1", G + "almarai/Almarai-Regular.ttf", "fonts/licenses/OFL-almarai.txt"),
        SelectableFont("almarai-bold", "fonts/catalog/almarai-bold.ttf", "Almarai Bold", FontStyleTag.SANS_MODERN, 700, "OFL-1.1", G + "almarai/Almarai-Bold.ttf", "fonts/licenses/OFL-almarai.txt"),
        SelectableFont("rooyin-regular", "fonts/catalog/rooyinfree-regular.ttf", "Rooyin Free", FontStyleTag.PIXEL, 400, "OFL-1.1", "https://github.com/MohamadDarvishi/Rooyin", "fonts/licenses/OFL-rooyin.txt"),
        SelectableFont("rooyin-bold", "fonts/catalog/rooyinfree-bold.ttf", "Rooyin Free Bold", FontStyleTag.PIXEL, 700, "OFL-1.1", "https://github.com/MohamadDarvishi/Rooyin", "fonts/licenses/OFL-rooyin.txt"),
    )

    /** Infers the game font's style from its profile; serif detection is not possible from tables alone. */
    fun inferStyle(game: FontAnalyzer.FontProfile): FontStyleTag = when {
        game.pixelStyle -> FontStyleTag.PIXEL
        game.monospace -> FontStyleTag.KUFI_GEOMETRIC
        else -> FontStyleTag.SANS_MODERN
    }

    fun select(
        game: FontAnalyzer.FontProfile,
        coverage: (SelectableFont) -> FontAnalyzer.FontProfile,
    ): FontChoice {
        require(!game.isIconFont) {
            "Refusing to replace recognized icon font '${game.familyName}'; its glyphs may encode game UI symbols."
        }
        return pick(game, coverage)
    }

    /**
     * Selection for the glyph-MERGE tier: the game font is not replaced, so an
     * icon font is acceptable here - its icons stay and only Arabic glyphs are
     * appended from the chosen catalog font. Same coverage gate and scoring.
     */
    fun selectForMerge(
        game: FontAnalyzer.FontProfile,
        coverage: (SelectableFont) -> FontAnalyzer.FontProfile,
    ): FontChoice = pick(game, coverage)

    private fun pick(
        game: FontAnalyzer.FontProfile,
        coverage: (SelectableFont) -> FontAnalyzer.FontProfile,
    ): FontChoice {
        val wanted = inferStyle(game)
        val scored = catalog.mapNotNull { f ->
            val p = coverage(f)
            if (!p.hasArabicCore || !ArabicShaper.emittedCodepoints.all(p::supports)) return@mapNotNull null
            val styleScore = if (f.style == wanted) 60 else 0
            val weightScore = 40 - minOf(40, kotlin.math.abs(f.weight - game.weightClass) / 10)
            f to (styleScore + weightScore)
        }.sortedByDescending { it.second }
        val (best, score) = scored.firstOrNull() ?: error("no catalog font passes the Arabic coverage requirement")
        val matched = best.style == wanted
        val reason = buildString {
            append("Game font '${game.familyName}' looks ${wanted.name.lowercase()} (weight ${game.weightClass}")
            if (game.bitmapStrikes) append(", bitmap strikes")
            if (game.monospace) append(", monospace")
            append("). Chose ${best.family} (${best.licenseId}, weight ${best.weight}): ")
            append(if (matched) "style matches" else "no catalog font of that style, nearest by weight")
            append(", score $score.")
        }
        return FontChoice(best, score, reason)
    }
}
