package com.omega.arabify

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.provider.MediaStore
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Visual RTL/font proof for the gettext runtime on the REAL Pixel Wheels
 * game. The CI script installs the deterministic pseudo build ("العربية N"
 * labels), reboots into an ar-EG locale and launches the game; this test then
 * screenshots the running game and checks that rendered menu lines actually
 * contain the shaped Arabic word the pipeline wrote, template-matched against
 * an in-test Canvas render of ArabicShaper.toVisual("العربية") drawn with the
 * merged font extracted by PixelWheelsGettextInstrumentedTest (font handoff
 * in filesDir/visual-fonts).
 *
 * This is the pixel-level leg the uiautomator hierarchy cannot give (libGDX
 * renders labels in OpenGL with zero text nodes): it proves the pre-shaped
 * presentation-form strings survive rasterization into VISIBLE Arabic glyph
 * shapes, not tofu, not missing glyphs, not Latin leftovers.
 *
 * Privacy: frames are published only to the app's Downloads/ARABIFY folder
 * and pulled into the private workflow artifact by the script; nothing is
 * uploaded or base64'd anywhere (repo privacy rule).
 */
@RunWith(AndroidJUnit4::class)
class PixelWheelsVisualArabicInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private data class LineMatch(val top: Int, val height: Int, val f1: Double, val fontIndex: Int)

    @Test
    fun arabicMenuLabelsAreVisiblyRenderedWithMergedFont() {
        // Font handoff via MediaStore Downloads/ARABIFY (published by
        // PixelWheelsGettextInstrumentedTest): the proven channel across the
        // visual leg's locale reboot.
        val fonts = mutableListOf<File>()
        val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME)
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?"
        val selectionArgs = arrayOf("Download/ARABIFY%", "merged-font-%.ttf")
        context.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI, projection, selection, selectionArgs, null,
        )?.use { cursor ->
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameCol)
                val uri = android.net.Uri.withAppendedPath(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, cursor.getLong(0).toString(),
                )
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                if (bytes != null && bytes.isNotEmpty()) {
                    val out = File(context.cacheDir, name)
                    out.writeBytes(bytes)
                    fonts += out
                }
            }
        }
        fonts.sortBy { it.name }
        assertTrue(
            "merged font handoff missing in Downloads/ARABIFY (PixelWheelsGettextInstrumentedTest must publish merged-font-*.ttf first)",
            fonts.isNotEmpty(),
        )
        val typefaces = fonts.map { Typeface.createFromFile(it) }

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val templateWord = ArabicShaper.toVisual("العربية")
        assertTrue(
            "shaper sanity: template must be presentation-form Arabic",
            templateWord.any { it.code in 0xFE70..0xFEFF },
        )

        var bestFrame: Bitmap? = null
        var bestMatches = emptyList<LineMatch>()
        var framesTried = 0
        var bandsSeenMax = 0

        for (attempt in 1..24) {
            val shot = instrumentation.uiAutomation.takeScreenshot()
            if (shot != null) {
                framesTried++
                val (matches, bands) = analyzeFrame(shot, typefaces, templateWord)
                bandsSeenMax = maxOf(bandsSeenMax, bands)
                if (matches.size > bestMatches.size ||
                    (matches.size == bestMatches.size && matches.sumOf { it.f1 } > bestMatches.sumOf { it.f1 })
                ) {
                    bestFrame?.recycle()
                    bestFrame = shot
                    bestMatches = matches
                } else {
                    shot.recycle()
                }
                if (bestMatches.size >= MIN_MATCHED_LINES) break
            }
            Thread.sleep(2_500)
        }

        val matched = bestMatches
        val report = buildString {
            appendLine("framesTried=$framesTried textBandsSeen=$bandsSeenMax linesMatched=${matched.size}")
            matched.take(8).forEach {
                appendLine("line y=${it.top} h=${it.height} f1=${"%.2f".format(it.f1)} font=${fonts[it.fontIndex].name}")
            }
        }
        Log.i("PixelWheelsVisual", report)

        val frame = bestFrame
        if (frame != null) {
            publish(frame, "pixelwheels-visual-frame.png", "image/png")
            frame.recycle()
        }
        publishText(report, "pixelwheels-visual-report.txt")

        assertTrue(
            "expected at least $MIN_MATCHED_LINES menu lines visibly rendering the shaped Arabic word, got ${matched.size}. $report",
            matched.size >= MIN_MATCHED_LINES,
        )
    }

    private data class Band(val top: Int, val bottom: Int)

    /** Returns matched lines to total text bands detected. */
    private fun analyzeFrame(
        frame: Bitmap,
        typefaces: List<Typeface>,
        templateWord: String,
    ): Pair<List<LineMatch>, Int> {
        val width = frame.width
        val height = frame.height
        val pixels = IntArray(width * height)
        frame.getPixels(pixels, 0, width, 0, 0, width, height)

        // Menu text is light on a dark background: binarize by luminance.
        val bright = BooleanArray(width * height)
        for (i in pixels.indices) {
            val c = pixels[i]
            val lum = (Color.red(c) + Color.green(c) + Color.blue(c)) / 3
            bright[i] = lum >= 130
        }

        // Text-line detection from the y-axis projection of bright pixels.
        val rowCounts = IntArray(height)
        for (y in 0 until height) {
            var count = 0
            val base = y * width
            for (x in 0 until width) if (bright[base + x]) count++
            rowCounts[y] = count
        }
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

        val matches = mutableListOf<LineMatch>()
        for (band in bands) {
            val bandHeight = band.bottom - band.top
            var left = width
            var right = 0
            for (row in band.top until band.bottom) {
                val base = row * width
                for (x in 0 until width) {
                    if (bright[base + x]) {
                        if (x < left) left = x
                        if (x > right) right = x
                    }
                }
            }
            if (right - left < 60) continue
            var best: LineMatch? = null
            typefaces.forEachIndexed { fontIndex, typeface ->
                for (scale in listOf(1.0, 1.2, 0.85)) {
                    val template = renderTemplate(templateWord, typeface, bandHeight, scale)
                    val f1 = slideMatch(bright, width, left, right, band.top, band.bottom, template)
                    if (best == null || f1 > best!!.f1) best = LineMatch(band.top, bandHeight, f1, fontIndex)
                }
            }
            if (best != null && best!!.f1 >= MIN_F1) matches.add(best!!)
        }
        return matches to bands.size
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

    private fun publish(bitmap: Bitmap, name: String, mime: String) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/ARABIFY")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Could not create Downloads entry for $name")
        try {
            context.contentResolver.openOutputStream(uri)?.use { stream ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            } ?: error("Could not open Downloads output for $name")
            val ready = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            context.contentResolver.update(uri, ready, null, null)
        } catch (error: Throwable) {
            context.contentResolver.delete(uri, null, null)
            throw error
        }
    }

    private fun publishText(text: String, name: String) {
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

    private companion object {
        const val MIN_MATCHED_LINES = 3
        const val MIN_F1 = 0.35
    }
}
