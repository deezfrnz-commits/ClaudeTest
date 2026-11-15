package com.documentfacilitator.core

import java.io.File

/** Compound File Binary container (the wrapper of legacy .doc / .xls / .ppt). Read-only. */
class Cfb(private val d: ByteArray) {
    private val shift: Int
    private val miniShift: Int
    private val fat: IntArray
    private var miniFat = IntArray(0)
    private var miniStream = ByteArray(0)
    private val cutoff: Int
    val entries = LinkedHashMap<String, IntArray>() // name -> [start, size, type]

    private fun u16(o: Int) = (d[o].toInt() and 0xff) or ((d[o + 1].toInt() and 0xff) shl 8)
    private fun i32(o: Int) = u16(o) or (u16(o + 2) shl 16)
    private fun secOff(s: Int) = (s + 1) shl shift

    init {
        require(d.size >= 512 && i32(0) == 0xE011CFD0.toInt()) { "not a compound file" }
        shift = u16(0x1E)
        miniShift = u16(0x20)
        cutoff = i32(0x38)
        val ssz = 1 shl shift
        val per = ssz / 4
        val fatSecs = ArrayList<Int>()
        for (i in 0 until 109) { val s = i32(0x4C + i * 4); if (s >= 0) fatSecs.add(s) }
        var dif = i32(0x44)
        var guard = 0
        while (dif >= 0 && guard++ < 10000 && secOff(dif) + ssz <= d.size) {
            val o = secOff(dif)
            for (i in 0 until per - 1) { val s = i32(o + i * 4); if (s >= 0) fatSecs.add(s) }
            dif = i32(o + (per - 1) * 4)
        }
        fat = IntArray(fatSecs.size * per) { -1 }
        for ((k, s) in fatSecs.withIndex()) {
            val o = secOff(s)
            if (o + ssz > d.size) continue
            for (i in 0 until per) fat[k * per + i] = i32(o + i * 4)
        }
        val dir = chain(i32(0x30), Int.MAX_VALUE)
        var rootStart = -1
        var rootSize = 0
        var e = 0
        while (e + 128 <= dir.size) {
            val nameLen = ((dir[e + 0x40].toInt() and 0xff) or ((dir[e + 0x41].toInt() and 0xff) shl 8)).coerceIn(0, 64)
            val type = dir[e + 0x42].toInt()
            fun di(o: Int) = (dir[e + o].toInt() and 0xff) or ((dir[e + o + 1].toInt() and 0xff) shl 8) or ((dir[e + o + 2].toInt() and 0xff) shl 16) or ((dir[e + o + 3].toInt() and 0xff) shl 24)
            if (type != 0 && nameLen >= 2) {
                val name = String(dir, e, nameLen - 2, Charsets.UTF_16LE)
                if (type == 5) { rootStart = di(0x74); rootSize = di(0x78) }
                else if (type == 2 && !entries.containsKey(name)) entries[name] = intArrayOf(di(0x74), di(0x78), type)
            }
            e += 128
        }
        if (rootStart >= 0) {
            miniStream = chain(rootStart, rootSize)
            val mf = chain(i32(0x3C), Int.MAX_VALUE)
            miniFat = IntArray(mf.size / 4) { (mf[it * 4].toInt() and 0xff) or ((mf[it * 4 + 1].toInt() and 0xff) shl 8) or ((mf[it * 4 + 2].toInt() and 0xff) shl 16) or ((mf[it * 4 + 3].toInt() and 0xff) shl 24) }
        }
    }

    private fun chain(start: Int, size: Int): ByteArray {
        val ssz = 1 shl shift
        val bo = java.io.ByteArrayOutputStream()
        var s = start
        var guard = 0
        while (s >= 0 && s < fat.size && guard++ < fat.size + 1 && bo.size() < size) {
            val o = secOff(s)
            if (o >= d.size) break
            bo.write(d, o, minOf(ssz, d.size - o))
            s = fat[s]
        }
        val b = bo.toByteArray()
        return if (size != Int.MAX_VALUE && b.size > size) b.copyOf(size) else b
    }

    fun stream(name: String): ByteArray? {
        val e = entries[name] ?: return null
        if (e[1] < cutoff && miniFat.isNotEmpty()) {
            val msz = 1 shl miniShift
            val bo = java.io.ByteArrayOutputStream()
            var s = e[0]
            var guard = 0
            while (s >= 0 && s < miniFat.size && guard++ <= miniFat.size && bo.size() < e[1]) {
                val o = s * msz
                if (o >= miniStream.size) break
                bo.write(miniStream, o, minOf(msz, miniStream.size - o))
                s = miniFat[s]
            }
            val b = bo.toByteArray()
            return if (b.size > e[1]) b.copyOf(e[1]) else b
        }
        return chain(e[0], e[1])
    }

    companion object {
        const val MAX_FILE = 96L * 1024 * 1024

        fun open(file: File): Cfb? = try {
            if (file.length() > MAX_FILE) null else Cfb(file.readBytes())
        } catch (_: Exception) {
            null
        }

        fun guess(file: File): Kind {
            val c = open(file) ?: return Kind.UNKNOWN
            return when {
                c.entries.containsKey("WordDocument") -> Kind.DOC
                c.entries.containsKey("Workbook") || c.entries.containsKey("Book") -> Kind.XLS
                c.entries.containsKey("PowerPoint Document") -> Kind.PPT
                else -> Kind.UNKNOWN
            }
        }
    }
}

/** Text-level readers for the legacy binary Office formats. Layout and images are not reproduced. */
object Legacy {
    private fun u16(b: ByteArray, o: Int) = if (o + 1 < b.size) (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8) else 0
    private fun i32(b: ByteArray, o: Int) = u16(b, o) or (u16(b, o + 2) shl 16)
    private val cp1252 = charset("windows-1252")
    private const val NOTE = "Legacy binary format: text content only, shown read-only. Edits are saved in a modern format."

    fun docToHtml(file: File): Converted {
        val cfb = Cfb.open(file) ?: return Converted("<p>This file could not be read.</p>")
        val wd = cfb.stream("WordDocument") ?: return Converted("<p>This file could not be read.</p>")
        val flags = u16(wd, 0x0A)
        if (flags and 0x0100 != 0) return Converted("<p>This document is password-protected.</p>")
        val table = cfb.stream(if (flags and 0x0200 != 0) "1Table" else "0Table")
        val ccpText = i32(wd, 0x4C)
        val text = StringBuilder()
        var ok = false
        if (table != null) {
            val fcClx = i32(wd, 0x1A2)
            val lcbClx = i32(wd, 0x1A6)
            var p = fcClx
            val end = minOf(table.size, fcClx + lcbClx)
            while (p in 0 until end) {
                val type = table[p].toInt()
                if (type == 1) { p += 3 + u16(table, p + 1); continue }
                if (type != 2) break
                val lcb = i32(table, p + 1)
                val base = p + 5
                val n = (lcb - 4) / 12
                if (n <= 0 || base + lcb > table.size) break
                for (k in 0 until n) {
                    val cp0 = i32(table, base + k * 4)
                    val cp1 = i32(table, base + (k + 1) * 4)
                    val pcd = base + (n + 1) * 4 + k * 8
                    val fcRaw = i32(table, pcd + 2)
                    val compressed = fcRaw and 0x40000000 != 0
                    val fc = fcRaw and 0x3FFFFFFF
                    val len = cp1 - cp0
                    if (len <= 0 || len > 50_000_000) continue
                    if (compressed) {
                        val o = fc / 2
                        if (o < 0 || o >= wd.size) continue
                        text.append(String(wd, o, minOf(len, wd.size - o), cp1252))
                    } else {
                        if (fc < 0 || fc >= wd.size) continue
                        text.append(String(wd, fc, minOf(len * 2, (wd.size - fc) and 1.inv()), Charsets.UTF_16LE))
                    }
                }
                ok = true
                break
            }
        }
        if (!ok) return Converted("<p>This Word file uses a layout this viewer cannot read.</p>", note = NOTE)
        val main = if (ccpText in 1 until text.length) text.substring(0, ccpText) else text.toString()
        val sb = StringBuilder(main.length + main.length / 8)
        val para = StringBuilder()
        var field = 0
        var cells = 0
        fun flush() {
            sb.append("<p>").append(if (para.isEmpty()) "<br>" else esc(para.toString())).append("</p>")
            para.setLength(0); cells = 0
        }
        for (c in main) {
            when (c.code) {
                0x13 -> field++
                0x14 -> if (field > 0) field--
                0x15 -> {}
                0x0D -> if (field == 0) flush()
                0x07 -> if (field == 0) { para.append(" | "); cells++ }
                0x0B -> if (field == 0) para.append('\n')
                0x0C -> if (field == 0) { flush(); sb.append("<hr class=\"pb\">") }
                0x09 -> if (field == 0) para.append(' ')
                0x1E -> if (field == 0) para.append('-')
                0xA0 -> if (field == 0) para.append(' ')
                else -> if (field == 0 && c.code >= 0x20) para.append(c)
            }
        }
        if (para.isNotEmpty()) flush()
        return Converted(sb.toString().replace("\n", "<br>"), note = NOTE)
    }

    fun pptToHtml(file: File): Converted {
        val cfb = Cfb.open(file) ?: return Converted("<p>This file could not be read.</p>")
        val ps = cfb.stream("PowerPoint Document") ?: return Converted("<p>This file could not be read.</p>")
        val slides = ArrayList<StringBuilder>()
        var extra = 0
        fun walk(from: Int, to: Int, depth: Int, listText: Boolean, inSlide: StringBuilder?) {
            var p = from
            var cur = inSlide
            var title = false
            while (p + 8 <= to) {
                val verInst = u16(ps, p)
                val type = u16(ps, p + 2)
                val len = i32(ps, p + 4)
                val body = p + 8
                val end = body + len
                if (len < 0 || end > to) break
                val container = (verInst and 0xF) == 0xF || type == 0xF00D
                when {
                    type == 4080 -> if ((verInst shr 4) == 0) walk(body, end, depth + 1, true, null)
                    type == 1011 && listText -> { cur = StringBuilder(); slides.add(cur) }
                    type == 1006 && depth < 4 -> {
                        val sbx = if (extra < slides.size) slides[extra] else StringBuilder().also { slides.add(it) }
                        extra++
                        walk(body, end, depth + 1, false, sbx)
                    }
                    type == 3999 -> { val tt = i32(ps, body); title = tt == 0 || tt == 6 }
                    type == 4000 || type == 4008 -> {
                        val target = cur
                        // placeholder text of slides lives in the list; inside slide containers only free text boxes do
                        if (target != null) {
                            val s = if (type == 4000) String(ps, body, len and 1.inv(), Charsets.UTF_16LE) else String(ps, body, len, cp1252)
                            val clean = s.replace('\u000b', '\n').trim('\r')
                            if (clean.isNotBlank()) {
                                if (title) target.append("<h2>").append(esc(clean).replace("\r", " ")).append("</h2>")
                                else for (line in clean.split('\r')) target.append("<p>").append(esc(line)).append("</p>")
                            }
                        }
                        title = false
                    }
                    container && depth < 24 -> walk(body, end, depth + 1, listText, cur)
                }
                p = end
            }
        }
        walk(0, ps.size, 0, false, null)
        val sb = StringBuilder()
        for ((i, s) in slides.withIndex()) {
            sb.append("<section class=\"slide\"><div class=\"x sn\">").append(i + 1).append("</div>").append(s.toString().replace("\n", "<br>")).append("</section>")
        }
        if (slides.isEmpty()) sb.append("<p>No slide text found.</p>")
        return Converted(sb.toString(), note = NOTE)
    }

    private class Sst(val segs: List<IntArray>, val b: ByteArray) {
        var si = 0
        var off = segs.firstOrNull()?.get(0) ?: 0
        private fun end() = segs[si][0] + segs[si][1]
        fun eof() = si >= segs.size
        private fun fix(): Boolean {
            while (si < segs.size && off >= end()) { si++; if (si < segs.size) off = segs[si][0] }
            return si < segs.size
        }
        fun u8(): Int { if (!fix()) return 0; return b[off++].toInt() and 0xff }
        fun u16(): Int { val a = u8(); return a or (u8() shl 8) }
        fun i32(): Int { val a = u16(); return a or (u16() shl 16) }
        fun skip(n: Int) { var left = n; while (left > 0 && fix()) { val k = minOf(left, end() - off); off += k; left -= k } }
        fun string(): String {
            val cch = u16()
            var flags = u8()
            val runs = if (flags and 8 != 0) u16() else 0
            val ext = if (flags and 4 != 0) i32() else 0
            val sb = StringBuilder(cch)
            var left = cch
            while (left > 0) {
                if (si >= segs.size) break
                if (off >= end()) { si++; if (si >= segs.size) break; off = segs[si][0]; flags = (flags and 1.inv()) or (b[off++].toInt() and 1) }
                val wide = flags and 1 != 0
                val avail = end() - off
                val k = minOf(left, if (wide) avail / 2 else avail)
                if (k <= 0) { off = end(); continue }
                if (wide) { sb.append(String(b, off, k * 2, Charsets.UTF_16LE)); off += k * 2 } else { sb.append(String(b, off, k, Charsets.ISO_8859_1)); off += k }
                left -= k
            }
            skip(runs * 4 + ext)
            return sb.toString()
        }
    }

    fun xlsGrids(file: File): List<Grid>? {
        val cfb = Cfb.open(file) ?: return null
        val wb = cfb.stream("Workbook") ?: cfb.stream("Book") ?: return null
        val names = LinkedHashMap<Int, String>()
        val shared = ArrayList<String>()
        val xfFmt = ArrayList<Int>()
        val fmts = HashMap<Int, Boolean>()
        val out = ArrayList<Grid>()
        var g: Grid? = null
        var depth = 0
        var p = 0
        var pendingStr: IntArray? = null
        fun rk(v: Int): Double {
            val d = if (v and 2 != 0) (v shr 2).toDouble() else java.lang.Double.longBitsToDouble((v.toLong() and 0xFFFFFFFCL) shl 32)
            return if (v and 1 != 0) d / 100.0 else d
        }
        fun num(xf: Int, d: Double): String {
            val f = xfFmt.getOrNull(xf) ?: 0
            val date = f in 14..22 || f in 45..47 || fmts[f] == true
            return if (date && d >= 0 && d < 2958466) Xlsx.serialToDate(d) else Xlsx.tidyNumber(d.toString())
        }
        fun str(o: Int, lenBytes: Int): String {
            val cch = if (lenBytes == 2) u16(wb, o) else wb[o].toInt() and 0xff
            val fl = wb[o + lenBytes].toInt()
            val s = o + lenBytes + 1
            return if (fl and 1 != 0) String(wb, s, minOf(cch * 2, wb.size - s), Charsets.UTF_16LE) else String(wb, s, minOf(cch, wb.size - s), Charsets.ISO_8859_1)
        }
        while (p + 4 <= wb.size) {
            val type = u16(wb, p)
            val len = u16(wb, p + 2)
            val o = p + 4
            if (o + len > wb.size) break
            try {
                when (type) {
                    0x0809 -> { depth++; if (depth == 1 && names.isNotEmpty()) { g = names[p]?.let { Grid(it) } ; g?.let { out.add(it) } } }
                    0x000A -> { depth--; if (depth == 0) g = null }
                    0x0085 -> names[i32(wb, o)] = str(o + 6, 1)
                    0x00E0 -> xfFmt.add(u16(wb, o + 2))
                    0x041E -> {
                        val code = str(o + 2, 2).replace(Regex("\"[^\"]*\"|\\[[^\\]]*]|\\\\."), "")
                        fmts[u16(wb, o)] = code.any { it in "dmyhsDMYHS" } && !code.contains("General")
                    }
                    0x00FC -> {
                        val segs = ArrayList<IntArray>()
                        segs.add(intArrayOf(o, len))
                        var q = o + len
                        while (q + 4 <= wb.size && u16(wb, q) == 0x003C) { val l = u16(wb, q + 2); segs.add(intArrayOf(q + 4, l)); q += 4 + l }
                        val r = Sst(segs, wb)
                        r.i32()
                        val unique = r.i32()
                        var k = 0
                        while (k < unique && !r.eof() && k < 2_000_000) { shared.add(r.string()); k++ }
                    }
                    0x00FD -> g?.set(u16(wb, o), u16(wb, o + 2), shared.getOrNull(i32(wb, o + 6)) ?: "")
                    0x0203 -> g?.set(u16(wb, o), u16(wb, o + 2), num(u16(wb, o + 4), java.lang.Double.longBitsToDouble((i32(wb, o + 6).toLong() and 0xFFFFFFFFL) or (i32(wb, o + 10).toLong() shl 32))))
                    0x027E -> g?.set(u16(wb, o), u16(wb, o + 2), num(u16(wb, o + 4), rk(i32(wb, o + 6))))
                    0x00BD -> {
                        val row = u16(wb, o)
                        var col = u16(wb, o + 2)
                        var q = o + 4
                        while (q + 6 <= o + len - 2) { g?.set(row, col, num(u16(wb, q), rk(i32(wb, q + 2)))); col++; q += 6 }
                    }
                    0x0204 -> g?.set(u16(wb, o), u16(wb, o + 2), str(o + 6, 2))
                    0x0205 -> if (wb[o + 7].toInt() == 0) g?.set(u16(wb, o), u16(wb, o + 2), if (wb[o + 6].toInt() != 0) "TRUE" else "FALSE")
                    0x0006 -> {
                        val row = u16(wb, o)
                        val col = u16(wb, o + 2)
                        if (u16(wb, o + 12) == 0xFFFF) {
                            when (wb[o + 6].toInt()) {
                                0 -> pendingStr = intArrayOf(row, col)
                                1 -> g?.set(row, col, if (wb[o + 8].toInt() != 0) "TRUE" else "FALSE")
                            }
                        } else g?.set(row, col, num(u16(wb, o + 4), java.lang.Double.longBitsToDouble((i32(wb, o + 6).toLong() and 0xFFFFFFFFL) or (i32(wb, o + 10).toLong() shl 32))))
                    }
                    0x0207 -> pendingStr?.let { g?.set(it[0], it[1], str(o, 2)); pendingStr = null }
                }
            } catch (_: Exception) {
            }
            p = o + len
        }
        return out
    }
}
