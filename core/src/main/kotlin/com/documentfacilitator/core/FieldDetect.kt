package com.documentfacilitator.core

/** A blank the user is expected to fill, found by analysing the rendered page. Units: pixels of the analysed image. */
class Blank(val kind: Int, val x: Int, val y: Int, val w: Int, val h: Int) {
    companion object {
        const val TEXT = 0   // a line or box to type into
        const val CHECK = 1  // checkbox / radio circle
        const val AREA = 2   // large empty box: free text, signature or image
    }
}

/**
 * Finds fill-in blanks on a flat (non-interactive) page image without any network or OCR:
 *  - enclosed empty boxes and table cells (white regions bounded by ruled lines),
 *  - small squares/circles (check boxes, radio buttons),
 *  - free-standing horizontal rules and underscore runs with empty space above them.
 */
object FieldDetector {
    /**
     * @param lum luminance per pixel (0..255), row-major
     * @param ppp pixels per point (image width / page width in points)
     */
    fun detect(lum: ByteArray, w: Int, h: Int, ppp: Float): List<Blank> {
        if (w < 32 || h < 32 || lum.size < w * h) return emptyList()
        val n = w * h
        val dark = BooleanArray(n) { (lum[it].toInt() and 0xff) < 185 }
        fun pt(v: Float) = Math.max(1, Math.round(v * ppp))
        val out = ArrayList<Blank>()

        // ---- 1. enclosed white regions ---------------------------------------------------
        val label = IntArray(n)
        val stack = IntArray(n)
        var next = 1
        val minBox = pt(6.5f)
        val boxes = ArrayList<IntArray>() // x0,y0,x1,y1 (inclusive) of accepted regions
        for (start in 0 until n) {
            if (dark[start] || label[start] != 0) continue
            val id = next++
            var sp = 0
            stack[sp++] = start
            label[start] = id
            var x0 = w; var y0 = h; var x1 = -1; var y1 = -1
            var area = 0
            var border = false
            while (sp > 0) {
                val p = stack[--sp]
                val x = p % w
                val y = p / w
                area++
                if (x < x0) x0 = x
                if (x > x1) x1 = x
                if (y < y0) y0 = y
                if (y > y1) y1 = y
                if (x == 0 || y == 0 || x == w - 1 || y == h - 1) border = true
                if (x > 0 && !dark[p - 1] && label[p - 1] == 0) { label[p - 1] = id; stack[sp++] = p - 1 }
                if (x < w - 1 && !dark[p + 1] && label[p + 1] == 0) { label[p + 1] = id; stack[sp++] = p + 1 }
                if (y > 0 && !dark[p - w] && label[p - w] == 0) { label[p - w] = id; stack[sp++] = p - w }
                if (y < h - 1 && !dark[p + w] && label[p + w] == 0) { label[p + w] = id; stack[sp++] = p + w }
            }
            if (border) continue
            val bw = x1 - x0 + 1
            val bh = y1 - y0 + 1
            if (bw < minBox || bh < minBox) continue
            if (bw.toLong() * bh > n * 0.55) continue
            val fill = area.toFloat() / (bw.toFloat() * bh)
            val small = bw <= pt(24f) && bh <= pt(24f)
            val aspect = bw.toFloat() / bh
            // the ring just outside the bounding box must be ruled lines for a rectangle
            fun ringDark(): Float {
                var d = 0; var t = 0
                for (x in x0..x1) {
                    if (y0 > 0) { t++; if (dark[(y0 - 1) * w + x]) d++ }
                    if (y1 < h - 1) { t++; if (dark[(y1 + 1) * w + x]) d++ }
                }
                for (y in y0..y1) {
                    if (x0 > 0) { t++; if (dark[y * w + x0 - 1]) d++ }
                    if (x1 < w - 1) { t++; if (dark[y * w + x1 + 1]) d++ }
                }
                return if (t == 0) 0f else d.toFloat() / t
            }
            // a check box / radio button stands alone; the bowl of a letter has neighbours right next to it
            fun isolated(): Boolean {
                val m = pt(2.2f)
                val reach = pt(2.5f)
                fun stroke(px: Int, py: Int, dx: Int, dy: Int): Int {
                    var k = 0; var x = px; var y = py
                    while (k <= reach && x in 0 until w && y in 0 until h && dark[y * w + x]) { k++; x += dx; y += dy }
                    return k
                }
                val cy = (y0 + y1) / 2; val cx = (x0 + x1) / 2
                val sl = stroke(x0 - 1, cy, -1, 0); val sr = stroke(x1 + 1, cy, 1, 0)
                val stp = stroke(cx, y0 - 1, 0, -1); val sb = stroke(cx, y1 + 1, 0, 1)
                if (sl > reach || sr > reach || stp > reach || sb > reach) return false
                val ax0 = x0 - sl - 1; val ax1 = x1 + sr + 1; val ay0 = y0 - stp - 1; val ay1 = y1 + sb + 1
                for (y in ay0 - m..ay1 + m) for (x in ax0 - m..ax1 + m) {
                    if (x in ax0..ax1 && y in ay0..ay1) continue
                    if (x < 0 || y < 0 || x >= w || y >= h) continue
                    if (dark[y * w + x]) return false
                }
                return true
            }
            if (fill >= (if (small) 0.97f else 0.996f)) {
                if (ringDark() < 0.9f) continue
                val kind = when {
                    small && aspect in 0.7f..1.45f -> Blank.CHECK
                    bh >= pt(54f) && bw >= pt(90f) -> Blank.AREA
                    else -> Blank.TEXT
                }
                if (kind == Blank.TEXT && bh > pt(40f) && bw < pt(30f)) continue // tall sliver: a margin, not a field
                out.add(Blank(kind, x0, y0, bw, bh))
                boxes.add(intArrayOf(x0, y0, x1, y1))
            } else if (small && fill in 0.70f..0.9f && aspect in 0.88f..1.14f && bw >= pt(7f)) {
                // round outline: radio button. The pixels of the region must form a disc.
                val cx = (x0 + x1) / 2f; val cy = (y0 + y1) / 2f; val r = (bw + bh) / 4f
                var inside = 0; var tot = 0
                for (y in y0..y1) for (x in x0..x1) {
                    val dx = x - cx; val dy = y - cy
                    if (dx * dx + dy * dy <= (r - 1) * (r - 1)) { tot++; if (label[y * w + x] == id) inside++ }
                }
                if (tot > 0 && inside >= tot * 0.97f && isolated()) { out.add(Blank(Blank.CHECK, x0, y0, bw, bh)); boxes.add(intArrayOf(x0, y0, x1, y1)) }
            } else if (fill >= 0.55f && bh >= pt(24f) && bw >= pt(40f) && ringDark() >= 0.9f) {
                // a ruled cell with a small caption at its top (tax-form style): the blank is the space below it
                var ty0 = h; var ty1 = -1
                for (y in y0..y1) for (x in x0..x1) if (label[y * w + x] != id) {
                    if (y < ty0) ty0 = y
                    if (y > ty1) ty1 = y
                }
                if (ty1 < 0) continue
                val gap = pt(2f)
                val below = y1 - ty1 - gap
                if (ty1 - ty0 + 1 <= pt(7.5f) && ty0 - y0 <= pt(6f) && below >= pt(13f)) {
                    out.add(Blank(if (below >= pt(54f) && bw >= pt(90f)) Blank.AREA else Blank.TEXT, x0, ty1 + gap, bw, below))
                    boxes.add(intArrayOf(x0, y0, x1, y1))
                }
            }
        }

        // ---- 2. free-standing rules / underscore runs ------------------------------------
        val minLen = pt(26f)
        val maxGap = pt(1.4f)
        val maxThick = pt(3f)
        val minClear = pt(8f)
        val maxField = pt(17f)
        val used = BooleanArray(n)
        for (y in 1 until h - 1) {
            var x = 0
            while (x < w) {
                if (!dark[y * w + x] || used[y * w + x]) { x++; continue }
                // extend run with small gaps
                var e = x
                var gap = 0
                var xx = x + 1
                while (xx < w && gap <= maxGap) {
                    if (dark[y * w + xx]) { e = xx; gap = 0 } else gap++
                    xx++
                }
                val len = e - x + 1
                if (len < minLen) { x = e + 1; continue }
                // thickness: following rows that are mostly dark over the same span
                var thick = 1
                while (y + thick < h && thick <= maxThick + 1) {
                    var d = 0
                    for (k in x..e) if (dark[(y + thick) * w + k]) d++
                    if (d < len * 0.7f) break
                    thick++
                }
                for (t in 0 until thick) for (k in x..e) used[(y + t) * w + k] = true
                val rx0 = x
                val rx1 = e
                x = e + 1
                if (thick > maxThick) continue
                // a rule that is the edge of a box, or has uprights at its ends, belongs to a table/frame
                var boxEdge = false
                for (b in boxes) {
                    val ox = minOf(rx1, b[2] + 2) - maxOf(rx0, b[0] - 2)
                    if (ox > len * 0.3f && (Math.abs(y - (b[1] - 1)) <= maxThick + 1 || Math.abs(y - (b[3] + 1)) <= maxThick + 1)) { boxEdge = true; break }
                }
                if (boxEdge) continue
                fun upright(cx: Int): Boolean {
                    val need = pt(7f)
                    for (dx in -2..2) {
                        val c = cx + dx
                        if (c < 0 || c >= w) continue
                        var up = 0
                        var yy = y - 1
                        while (yy >= 0 && dark[yy * w + c] && up < need) { up++; yy-- }
                        var dn = 0
                        yy = y + thick
                        while (yy < h && dark[yy * w + c] && dn < need) { dn++; yy++ }
                        if (up >= need || dn >= need) return true
                    }
                    return false
                }
                if (upright(rx0) || upright(rx1)) continue
                // empty space above the rule
                var clear = 0
                var yy = y - 1
                while (yy >= 0 && clear < maxField) {
                    var d = 0
                    for (k in rx0 + 1 until rx1) if (dark[yy * w + k]) d++
                    if (d > Math.max(1f, len * 0.004f)) break
                    clear++; yy--
                }
                // a page-wide rule with text sitting right on it is a heading underline / separator
                val need = if (len > w * 0.6f) pt(11f) else minClear
                if (need > clear) continue
                out.add(Blank(Blank.TEXT, rx0, y - clear, len, clear))
            }
        }

        // ---- 3. order (reading order) and de-duplicate -----------------------------------
        val sorted = out.sortedWith(compareBy<Blank>({ it.y + it.h / 2 }, { it.x }))
        val res = ArrayList<Blank>()
        for (b in sorted) {
            var dup = false
            for (o in res) {
                val ix = minOf(b.x + b.w, o.x + o.w) - maxOf(b.x, o.x)
                val iy = minOf(b.y + b.h, o.y + o.h) - maxOf(b.y, o.y)
                if (ix > 0 && iy > 0 && ix.toLong() * iy > 0.5 * minOf(b.w.toLong() * b.h, o.w.toLong() * o.h)) { dup = true; break }
            }
            if (!dup) res.add(b)
        }
        val band = pt(7f)
        return res.sortedWith { a, b ->
            val ay = a.y + a.h
            val by = b.y + b.h
            if (Math.abs(ay - by) > band) ay.compareTo(by) else a.x.compareTo(b.x)
        }
    }
}
