package com.omega.arabify

/**
 * Text-fit estimation: Arabic UI text typically runs 20-30% wider than
 * English, and game UI often allocates space for the source string. This
 * estimator predicts overflow risk from a deterministic width model so the
 * report can warn BEFORE a screen ships with clipped Arabic.
 *
 * The width model is a per-script average advance in units of the Latin
 * average: Arabic letters are wider than Latin on average, digits narrow,
 * spaces narrow, wide caps wider. It is deliberately conservative (flags
 * early) because a false warning costs a report line; a missed clip costs a
 * broken screen. It does NOT measure real glyph bounds - no font rasterizer
 * here - so the report must call it an estimate.
 */
internal object TextFitEstimator {

    data class Estimate(val widthRatio: Double, val overflowRisk: Boolean)

    /** Width of [translation] relative to [source]; > [overflowThreshold] flags risk. */
    fun estimate(source: String, translation: String, overflowThreshold: Double = 1.35): Estimate {
        val sourceWidth = widthUnits(source)
        if (sourceWidth <= 0.0) return Estimate(1.0, overflowRisk = false)
        val ratio = widthUnits(translation) / sourceWidth
        return Estimate(ratio, ratio > overflowThreshold)
    }

    // Joined Arabic is horizontally compact; overflow risk comes mostly from
    // word-count growth, not letter width. Narrow letters get Latin-ish width.
    private const val NARROW_ARABIC = "الإأآإىرزوةـ"

    internal fun widthUnits(text: String): Double {
        var units = 0.0
        for (ch in text) {
            units += when {
                ch in NARROW_ARABIC -> 0.65
                ch.code in 0x0600..0x06FF || ch.code in 0xFB50..0xFEFF -> 0.80
                ch.isDigit() -> 0.62
                ch == ' ' -> 0.35
                ch in "iljtf.,:;!|'" -> 0.38
                ch in "mwMW@%&" -> 1.25
                ch.isUpperCase() -> 0.92
                ch.isLetter() -> 0.72
                else -> 0.55
            }
        }
        return units
    }
}
