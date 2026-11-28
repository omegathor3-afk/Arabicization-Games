package com.omega.arabify

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.provider.MediaStore
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry

/**
 * Shared pixel-level proof that an Arabized game VISIBLY renders shaped
 * Arabic on screen. The caller supplies the deterministic pseudo word the
 * pipeline wrote into every translated label and the typefaces the game's
 * text stack rasterizes with (merged font, or the system font for games that
 * build fonts from Android at runtime). The check polls screenshots of the
 * running game, detects text-line bands by y-projection of bright pixels, and
 * template-matches each band against an in-test Canvas render of the shaped
 * word. Frames and stats go to Downloads/ARABIFY and from there into the
 * private workflow artifact only (repo privacy rule).
 */
internal object ArabicVisualRenderCheck {

    data class LineMatch(val top: Int, val height: Int, val f1: Double, val fontIndex: Int)
    private data class Band(val top: Int, val bottom: Int)

    data class Outcome(val report: String, val matchedLines: Int, val framesTried: Int)

    fun run(
        context: Context,
        instrumentation: android.app.Instrumentation,
        templateWord: String,
        typefaces: List<Typeface>,
        reportPrefix: String,
        minMatchedLines: Int,
        minF1: Double,
        maxFrames: Int = 24,
    ): Outcome {
        val shapedTemplate = ArabicShaper.toVisual(templateWord)
        require(shapedTemplate.any { it.code in 0xFE70..0xFEFF }) {
            "template word must shape to presentation forms, got: $shapedTemplate"
        }

        var bestFrame: Bitmap? = null
        var bestMatches = emptyList<LineMatch>()
        var framesTried = 0
        var bandsSeenMax = 0
        var maxRowInkSeen = 0
        var maxInkPctSeen = 0.0
        var lastPolarity = "bright"

        for (attempt in 1..maxFrames) {
            val shot = instrumentation.uiAutomation.takeScreenshot()
            if (shot != null) {
                framesTried++
                val analysis = analyzeFrame(shot, typefaces, shapedTemplate, minF1)
                val matches = analysis.matches
                bandsSeenMax = maxOf(bandsSeenMax, analysis.bands)
                maxRowInkSeen = maxOf(maxRowInkSeen, analysis.maxRowInk)
                maxInkPctSeen = maxOf(maxInkPctSeen, analysis.inkPct)
                lastPolarity = analysis.polarity
                if (matches.size > bestMatches.size ||
                    (matches.size == bestMatches.size && matches.sumOf { it.f1 } > bestMatches.sumOf { it.f1 })
                ) {
                    bestFrame?.recycle()
                    bestFrame = shot
                    bestMatches = matches
                } else {
                    shot.recycle()
                }
                if (bestMatches.size >= minMatchedLines) break
            }
            Thread.sleep(2_500)
        }

        val report = buildString {
            appendLine("framesTried=$framesTried textBandsSeen=$bandsSeenMax linesMatched=${bestMatches.size} polarity=$lastPolarity maxRowInk=$maxRowInkSeen inkPct=${String.format(java.util.Locale.US, "%.4f", maxInkPctSeen)}")
            bestMatches.take(8).forEach {
                appendLine("line y=${it.top} h=${it.height} f1=${String.format(java.util.Locale.US, "%.2f", it.f1)} fontIndex=${it.fontIndex}")
            }
        }
        Log.i("ArabicVisualCheck", report)

        bestFrame?.let {
            publish(context, it, "$reportPrefix-visual-frame.png", "image/png") { bitmap, stream ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            }
            it.recycle()
        }
        publishText(context, report, "$reportPrefix-visual-report.txt")
        return Outcome(report, bestMatches.size, framesTried)
    }

    fun renderTypefaceFromAsset(context: Context, assetPath: String, cacheName: String): Typeface {
        val file = java.io.File(context.cacheDir, cacheName)
        context.assets.open(assetPath).use { input -> file.outputStream().use { input.copyTo(it) } }
        require(file.isFile && file.length() > 0L) { "font asset missing: $assetPath" }
        return Typeface.createFromFile(file)
    }

    data class FrameAnalysis(
        val matches: List<LineMatch>,
        val bands: Int,
        val maxRowInk: Int,
        val inkPct: Double,
        val polarity: String,
    )

    /**
     * Returns matched lines plus detection diagnostics. Menus come in both
     * polarities (Pixel Wheels: bright text on a dark background; Unciv: dark
     * text on a light tan background), so the frame is binarized both ways
     * and the polarity that yields more text-line bands wins. The template is
     * an alpha-mask glyph shape, so it matches either ink polarity unchanged.
     */
    internal fun analyzeFrame(
        frame: Bitmap,
        typefaces: List<Typeface>,
        shapedTemplate: String,
        minF1: Double,
    ): FrameAnalysis {
        val width = frame.width
        val height = frame.height
        val pixels = IntArray(width * height)
        frame.getPixels(pixels, 0, width, 0, 0, width, height)

        val bright = BooleanArray(width * height)
        val dark = BooleanArray(width * height)
        for (i in pixels.indices) {
            val c = pixels[i]
            val lum = (Color.red(c) + Color.green(c) + Color.blue(c)) / 3
            bright[i] = lum >= 130
            dark[i] = lum <= 110
        }

        val brightBands = detectBands(bright, width, height)
        val darkBands = detectBands(dark, width, height)
        val ink = if (darkBands.first.size > brightBands.first.size) dark else bright
        val (bands, maxRowInk) = if (darkBands.first.size > brightBands.first.size) darkBands else brightBands
        val polarity = if (darkBands.first.size > brightBands.first.size) "dark" else "bright"
        var inkTotal = 0L
        for (v in ink) if (v) inkTotal++
        val inkPct = inkTotal.toDouble() / (width.toDouble() * height)

        val matches = mutableListOf<LineMatch>()
        for (band in bands) {
            val bandHeight = band.bottom - band.top
            var left = width
            var right = 0
            for (row in band.top until band.bottom) {
                val base = row * width
                for (x in 0 until width) {
                    if (ink[base + x]) {
                        if (x < left) left = x
                        if (x > right) right = x
                    }
                }
            }
            if (right - left < 60) continue
            var best: LineMatch? = null
            typefaces.forEachIndexed { fontIndex, typeface ->
                for (scale in listOf(1.0, 1.2, 0.85)) {
                    val template = renderTemplate(shapedTemplate, typeface, bandHeight, scale)
                    val f1 = slideMatch(ink, width, left, right, band.top, band.bottom, template)
                    if (best == null || f1 > best!!.f1) best = LineMatch(band.top, bandHeight, f1, fontIndex)
                }
            }
            if (best != null && best!!.f1 >= minF1) matches.add(best!!)
        }
        return FrameAnalysis(matches, bands.size, maxRowInk, inkPct, polarity)
    }

    /** Text-line detection from the y-axis projection of ink pixels. Returns bands to the max row ink count. */
    private fun detectBands(mask: BooleanArray, width: Int, height: Int): Pair<List<Band>, Int> {
        val rowCounts = IntArray(height)
        for (y in 0 until height) {
            var count = 0
            val base = y * width
            for (x in 0 until width) if (mask[base + x]) count++
            rowCounts[y] = count
        }
        var maxRow = 0
        for (c in rowCounts) maxRow = maxOf(maxRow, c)
        val bands = mutableListOf<Band>()
        var y = 0
        while (y < height) {
            if (rowCounts[y] >= 12) {
                var end = y
                while (end + 1 < height && (rowCounts[end + 1] >= 4 || (end + 2 < height && rowCounts[end + 2] >= 4))) end++
                if (end - y + 1 in 10..160) bands.add(Band(y, end + 1))
                y = end + 1
            } else y++
        }
        return bands to maxRow
    }

    /** Renders the shaped word in white with [typeface] and binarizes by alpha. */
    private fun renderTemplate(word: String, typeface: Typeface, targetHeight: Int, scale: Double): Array<BooleanArray> {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.typeface = typeface
            color = Color.WHITE
            textSize = targetHeight * 1.4f
        }
        val textWidth = paint.measureText(word).toInt().coerceAtLeast(1)
        val metrics = paint.fontMetrics
        val textHeight = (metrics.descent - metrics.ascent).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(textWidth + 8, textHeight + 8, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawText(word, 4f, 4f - metrics.ascent, paint)
        val raw = Array(bitmap.height) { row ->
            BooleanArray(bitmap.width) { col -> Color.alpha(bitmap.getPixel(col, row)) >= 100 }
        }
        bitmap.recycle()
        return scaleMask(raw, (targetHeight * scale).toInt().coerceAtLeast(6))
    }

    private fun scaleMask(mask: Array<BooleanArray>, targetHeight: Int): Array<BooleanArray> {
        val srcH = mask.size
        val srcW = mask[0].size
        val targetWidth = (srcW.toLong() * targetHeight / srcH).toInt().coerceAtLeast(4)
        return Array(targetHeight) { row ->
            val srcRow = row.toLong() * srcH / targetHeight
            BooleanArray(targetWidth) { col -> mask[srcRow.toInt()][(col.toLong() * srcW / targetWidth).toInt()] }
        }
    }

    /**
     * Slides the template along the line box and returns the best binary F1
     * (bright-pixel precision/recall). Coarse 3px steps keep it cheap on the
     * emulator CPU.
     */
    private fun slideMatch(
        bright: BooleanArray,
        width: Int,
        left: Int,
        right: Int,
        top: Int,
        bottom: Int,
        template: Array<BooleanArray>,
    ): Double {
        val tH = template.size
        val tW = template[0].size
        var templateOn = 0
        for (row in template) for (v in row) if (v) templateOn++
        if (templateOn == 0) return 0.0

        val centerY = (top + bottom) / 2
        var best = 0.0
        val dxRange = (left - tW / 3)..(right - tW / 2) step 3
        for (dy in -3..3) {
            val startY = centerY - tH / 2 + dy
            if (startY < 0 || startY + tH > bottom + tH / 2) continue
            for (startX in dxRange) {
                if (startX < 0 || startX + tW > width) continue
                var tp = 0
                var fp = 0
                for (ty in 0 until tH) {
                    val fy = startY + ty
                    if (fy < 0 || fy >= bright.size / width) continue
                    val base = fy * width
                    val tRow = template[ty]
                    for (tx in 0 until tW) {
                        if (!tRow[tx]) continue
                        if (bright[base + startX + tx]) tp++ else fp++
                    }
                }
                val precision = tp.toDouble() / (tp + fp)
                val recall = tp.toDouble() / templateOn
                val f1 = if (precision + recall > 0) 2 * precision * recall / (precision + recall) else 0.0
                if (f1 > best) best = f1
            }
        }
        return best
    }

    private fun publish(
        context: Context,
        payload: Bitmap,
        name: String,
        mime: String,
        write: (Bitmap, java.io.OutputStream) -> Unit,
    ) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/ARABIFY")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Could not create Downloads entry for $name")
        try {
            context.contentResolver.openOutputStream(uri)?.use { stream -> write(payload, stream) }
                ?: error("Could not open Downloads output for $name")
            val ready = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            context.contentResolver.update(uri, ready, null, null)
        } catch (error: Throwable) {
            context.contentResolver.delete(uri, null, null)
            throw error
        }
    }

    private fun publishText(context: Context, text: String, name: String) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/ARABIFY")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Could not create Downloads entry for $name")
        try {
            context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                ?: error("Could not open Downloads output for $name")
            val ready = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            context.contentResolver.update(uri, ready, null, null)
        } catch (error: Throwable) {
            context.contentResolver.delete(uri, null, null)
            throw error
        }
    }
}
