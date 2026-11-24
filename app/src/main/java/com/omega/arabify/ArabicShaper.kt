package com.omega.arabify

/**
 * Approximate Arabic shaper for engines with no shaping/bidi (libGDX bitmap
 * text and similar). Logical text -> contextual Presentation Forms-B glyphs
 * -> visual (left-to-right) order. Approximation only: no kerning, no mark
 * positioning, simplified bidi (RTL base, Latin/digit runs kept in order).
 * Placeholders ({0}, %s, %1$d, \n) are kept intact and never mirrored.
 */
internal object ArabicShaper {
    // base -> [isolated, final, initial, medial]; right-joining letters have two forms.
    private val forms: Map<Int, IntArray> = buildMap {
        fun r(base: Int, iso: Int) = put(base, intArrayOf(iso, iso + 1))
        fun d(base: Int, iso: Int) = put(base, intArrayOf(iso, iso + 1, iso + 2, iso + 3))
        put(0x0621, intArrayOf(0xFE80))
        r(0x0622, 0xFE81); r(0x0623, 0xFE83); r(0x0624, 0xFE85); r(0x0625, 0xFE87)
        d(0x0626, 0xFE89); r(0x0627, 0xFE8D); d(0x0628, 0xFE8F); r(0x0629, 0xFE93)
        d(0x062A, 0xFE95); d(0x062B, 0xFE99); d(0x062C, 0xFE9D); d(0x062D, 0xFEA1); d(0x062E, 0xFEA5)
        r(0x062F, 0xFEA9); r(0x0630, 0xFEAB); r(0x0631, 0xFEAD); r(0x0632, 0xFEAF)
        d(0x0633, 0xFEB1); d(0x0634, 0xFEB5); d(0x0635, 0xFEB9); d(0x0636, 0xFEBD)
        d(0x0637, 0xFEC1); d(0x0638, 0xFEC5); d(0x0639, 0xFEC9); d(0x063A, 0xFECD)
        d(0x0641, 0xFED1); d(0x0642, 0xFED5); d(0x0643, 0xFED9); d(0x0644, 0xFEDD)
        d(0x0645, 0xFEE1); d(0x0646, 0xFEE5); d(0x0647, 0xFEE9); r(0x0648, 0xFEED)
        r(0x0649, 0xFEEF); d(0x064A, 0xFEF1)
    }
    private val lamAlef = mapOf(0x0622 to 0xFEF5, 0x0623 to 0xFEF7, 0x0625 to 0xFEF9, 0x0627 to 0xFEFB)
    private val placeholder = Regex("""\{[^}]*\}|%(\d+\$)?[-#+ 0,(]*\d*(\.\d+)?[sdfxXc%]|\\n|\n""")
    private val mirror = mapOf('(' to ')', ')' to '(', '[' to ']', ']' to '[', '<' to '>', '>' to '<', '«' to '»', '»' to '«')

    private fun isMark(c: Int) = c in 0x064B..0x065F || c == 0x0670
    private fun isArabicLetter(c: Int) = forms.containsKey(c) || c == 0x0640
    private fun joinsNext(c: Int) = (forms[c]?.size ?: 0) == 4 || c == 0x0640   // can connect to following letter
    private fun joinsPrev(c: Int) = (forms[c]?.size ?: 0) >= 2 || c == 0x0640   // can connect to preceding letter

    fun containsArabic(text: String): Boolean = text.any { it.code in 0x0600..0x06FF }

    /** Shapes and reorders one string for visual rendering. Idempotence is NOT guaranteed; call once. */
    fun toVisual(text: String): String {
        if (!containsArabic(text)) return text
        return text.split("\n").joinToString("\n") { visualLine(it) }
    }

    private fun visualLine(line: String): String {
        // split into atomic segments: placeholders vs text
        val segs = ArrayList<Pair<String, Boolean>>() // text, isPlaceholder
        var last = 0
        for (m in placeholder.findAll(line)) {
            if (m.range.first > last) segs.add(line.substring(last, m.range.first) to false)
            segs.add(m.value to true)
            last = m.range.last + 1
        }
        if (last < line.length) segs.add(line.substring(last) to false)
        // shape text segments (joining does not cross placeholders)
        val shaped = segs.map { (t, ph) -> if (ph) t to true else shape(t) to false }
        // reverse overall order; inside each segment reverse RTL runs only
        val out = StringBuilder()
        for ((t, ph) in shaped.asReversed()) out.append(if (ph) t else reorder(t))
        return out.toString()
    }

    private fun shape(t: String): String {
        val cps = t.codePoints().toArray()
        val sb = StringBuilder()
        var i = 0
        fun prevJoins(): Boolean {  // previous base letter in `cps` before i connects forward
            var k = i - 1
            while (k >= 0 && isMark(cps[k])) k--
            return k >= 0 && joinsNext(cps[k])
        }
        while (i < cps.size) {
            val c = cps[i]
            if (!isArabicLetter(c)) { sb.appendCodePoint(c); i++; continue }
            // lam-alef ligature (skipping marks between is not handled; plain adjacency only)
            if (c == 0x0644 && i + 1 < cps.size && lamAlef.containsKey(cps[i + 1])) {
                val lig = lamAlef.getValue(cps[i + 1])
                sb.appendCodePoint(if (prevJoins()) lig + 1 else lig)
                i += 2; continue
            }
            if (c == 0x0640) { sb.appendCodePoint(c); i++; continue }
            val f = forms.getValue(c)
            val before = prevJoins() && joinsPrev(c)
            var k = i + 1
            while (k < cps.size && isMark(cps[k])) k++
            val after = k < cps.size && joinsPrev(cps[k]) && joinsNext(c)
            val idx = when {
                f.size == 1 -> 0
                before && after -> 3
                before -> 1
                after -> 2
                else -> 0
            }
            sb.appendCodePoint(f[minOf(idx, f.size - 1)])
            i++
        }
        return sb.toString()
    }

    private fun isRtl(ch: Char) = ch.code in 0x0600..0x06FF || ch.code in 0xFB50..0xFDFF || ch.code in 0xFE70..0xFEFF

    /** RTL base: reverse whole text, then restore LTR (Latin/digit) runs. Marks stay after base glyphs. */
    private fun reorder(t: String): String {
        val rev = t.reversed()
        val sb = StringBuilder()
        var i = 0
        while (i < rev.length) {
            val ch = rev[i]
            if (ch.isLetterOrDigit() && !isRtl(ch)) {
                var j = i
                while (j < rev.length && rev[j].isLetterOrDigit() && !isRtl(rev[j])) j++
                sb.append(rev.substring(i, j).reversed())
                i = j
            } else { sb.append(mirror[ch] ?: ch); i++ }
        }
        // combining marks (U+064B..) come before their base after reversal; move each after its base
        val s = sb.toString().toCharArray()
        var k = 0
        while (k < s.size - 1) {
            if (isMark(s[k].code) && !isMark(s[k + 1].code)) { val m = s[k]; s[k] = s[k + 1]; s[k + 1] = m; k += 2 } else k++
        }
        return String(s)
    }
}
