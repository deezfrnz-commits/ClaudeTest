package com.documentfacilitator.core

import com.documentfacilitator.core.XmlScanner.Companion.END
import com.documentfacilitator.core.XmlScanner.Companion.EOF
import com.documentfacilitator.core.XmlScanner.Companion.START
import com.documentfacilitator.core.XmlScanner.Companion.TEXT
import java.io.File
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.TimeZone

/** A bounded, dense snapshot of one worksheet. `formulas` maps (row shl 16 or col) -> formula text. */
class Grid(val name: String) {
    val rows = ArrayList<Array<String?>>()
    val formulas = HashMap<Int, String>()
    var cols = 0
    var truncated = false

    fun set(r: Int, c: Int, v: String?) {
        if (r >= MAX_ROWS || c >= MAX_COLS) { truncated = true; return }
        while (rows.size <= r) rows.add(arrayOfNulls(0))
        var row = rows[r]
        if (row.size <= c) { row = row.copyOf(maxOf(c + 1, row.size * 2).coerceAtMost(MAX_COLS)); rows[r] = row }
        row[c] = v
        if (c + 1 > cols) cols = c + 1
    }

    fun get(r: Int, c: Int): String? = rows.getOrNull(r)?.getOrNull(c)

    companion object {
        const val MAX_ROWS = 2000
        const val MAX_COLS = 100

        fun colName(c: Int): String {
            var n = c + 1
            val sb = StringBuilder()
            while (n > 0) { val m = (n - 1) % 26; sb.append('A' + m); n = (n - 1) / 26 }
            return sb.reverse().toString()
        }

        /** "BC12" -> (row, col), both zero-based; null when malformed. */
        fun parseRef(ref: String): IntArray? {
            var c = 0
            var i = 0
            while (i < ref.length && ref[i].isLetter()) { c = c * 26 + (ref[i].uppercaseChar() - 'A' + 1); i++ }
            if (i == 0 || i == ref.length) return null
            val r = ref.substring(i).toIntOrNull() ?: return null
            return intArrayOf(r - 1, c - 1)
        }

        fun toHtml(grids: List<Grid>): String {
            val sb = StringBuilder()
            for ((si, g) in grids.withIndex()) {
                sb.append("<section class=\"sheet\" data-s=\"").append(si).append("\" data-n=\"").append(esc(g.name)).append("\">")
                if (g.truncated) sb.append("<div class=\"x note\" contenteditable=\"false\">Showing the first $MAX_ROWS rows / $MAX_COLS columns of this sheet.</div>")
                val nr = minOf(MAX_ROWS, g.rows.size + 3)
                val nc = minOf(MAX_COLS, maxOf(g.cols, 1) + 1)
                sb.append("<table><tr><th></th>")
                for (c in 0 until nc) sb.append("<th>").append(colName(c)).append("</th>")
                sb.append("</tr>")
                for (r in 0 until nr) {
                    sb.append("<tr><th>").append(r + 1).append("</th>")
                    val row = g.rows.getOrNull(r)
                    for (c in 0 until nc) {
                        val v = row?.getOrNull(c)
                        val f = g.formulas[(r shl 16) or c]
                        if (v == null && f == null) { sb.append("<td></td>"); continue }
                        sb.append("<td")
                        if (f != null) sb.append(" data-f=\"").append(esc(f)).append('"')
                        if (v != null && isNumber(v)) sb.append(" class=\"n\"")
                        sb.append('>').append(esc(v ?: "")).append("</td>")
                    }
                    sb.append("</tr>")
                }
                sb.append("</table></section>")
            }
            if (grids.isEmpty()) sb.append("<p>No sheets found.</p>")
            return sb.toString()
        }

        fun isNumber(v: String): Boolean {
            if (v.isEmpty() || v.length > 24) return false
            var digits = 0
            for ((i, ch) in v.withIndex()) {
                if (ch.isDigit()) digits++
                else if (!(ch == '.' || ch == 'e' || ch == 'E' || ((ch == '-' || ch == '+') && (i == 0 || v[i - 1] == 'e' || v[i - 1] == 'E')))) return false
            }
            return digits > 0 && v.toDoubleOrNull() != null
        }
    }
}

object Xlsx {
    class Sheet(val name: String, val path: String)

    fun sheets(z: ZipDoc): List<Sheet> {
        val wb = z.text("xl/workbook.xml") ?: return emptyList()
        val rels = z.rels("xl/workbook.xml")
        val out = ArrayList<Sheet>()
        val x = XmlScanner(wb)
        while (true) {
            val t = x.next()
            if (t == EOF) break
            if (t == START && (x.name == "sheet" || x.name.endsWith(":sheet"))) {
                val path = rels[x.attr("r:id") ?: ""] ?: continue
                if (z.has(path)) out.add(Sheet(x.attr("name") ?: "Sheet", path))
            }
        }
        return out
    }

    private fun sharedStrings(xml: String?): List<String> {
        val out = ArrayList<String>()
        if (xml == null) return out
        val x = XmlScanner(xml)
        val sb = StringBuilder()
        var inT = false
        var ph = 0
        while (true) {
            val t = x.next()
            if (t == EOF) break
            when (t) {
                START -> when (x.name) {
                    "si" -> sb.setLength(0)
                    "rPh" -> ph++
                    "t" -> inT = ph == 0
                }
                END -> when (x.name) {
                    "si" -> out.add(sb.toString())
                    "rPh" -> ph--
                    "t" -> inT = false
                }
                TEXT -> if (inT) sb.append(x.text)
            }
        }
        return out
    }

    /** For each cell style index: true when the number format is a date/time. */
    private fun dateStyles(xml: String?): BooleanArray {
        if (xml == null) return BooleanArray(0)
        val custom = HashMap<Int, Boolean>()
        val out = ArrayList<Boolean>()
        val x = XmlScanner(xml)
        var inXfs = false
        while (true) {
            val t = x.next()
            if (t == EOF) break
            if (t == START) when (x.name) {
                "numFmt" -> {
                    val id = x.attr("numFmtId")?.toIntOrNull() ?: continue
                    val code = (x.attr("formatCode") ?: "").replace(Regex("\"[^\"]*\"|\\[[^\\]]*]|\\\\."), "")
                    custom[id] = code.any { it in "dmyhsDMYHS" } && !code.contains("General")
                }
                "cellXfs" -> inXfs = true
                "xf" -> if (inXfs) {
                    val id = x.attr("numFmtId")?.toIntOrNull() ?: 0
                    out.add(id in 14..22 || id in 45..47 || custom[id] == true)
                }
            } else if (t == END && x.name == "cellXfs") inXfs = false
        }
        return out.toBooleanArray()
    }

    fun serialToDate(v: Double): String {
        val days = Math.floor(v).toLong()
        val secs = Math.round((v - days) * 86400.0)
        val cal = GregorianCalendar(TimeZone.getTimeZone("UTC"))
        cal.clear()
        cal.set(1899, Calendar.DECEMBER, 30)
        cal.add(Calendar.DAY_OF_YEAR, days.toInt())
        cal.add(Calendar.SECOND, secs.toInt())
        val date = String.format("%04d-%02d-%02d", cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH))
        val time = String.format("%02d:%02d", cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE)) +
            (if (cal.get(Calendar.SECOND) != 0) String.format(":%02d", cal.get(Calendar.SECOND)) else "")
        return if (secs == 0L) date else if (days == 0L) time else "$date $time"
    }

    fun tidyNumber(raw: String): String {
        val d = raw.toDoubleOrNull() ?: return raw
        if (d == Math.rint(d) && Math.abs(d) < 1e15) return d.toLong().toString()
        if (raw.length <= 12) return raw
        var s = String.format("%.10g", d)
        if (s.contains('e')) return s.replace(Regex("0+e"), "e")
        if (s.contains('.')) s = s.trimEnd('0').trimEnd('.')
        return s
    }

    fun grids(z: ZipDoc): List<Grid> {
        val shared = sharedStrings(z.text("xl/sharedStrings.xml"))
        val dates = dateStyles(z.text("xl/styles.xml"))
        val out = ArrayList<Grid>()
        for (s in sheets(z)) {
            val g = Grid(s.name)
            out.add(g)
            val xml = z.text(s.path)
            if (xml == null) { g.truncated = true; continue }
            val x = XmlScanner(xml)
            var row = -1
            var col = -1
            var type = ""
            var style = 0
            var v: String? = null
            var f: String? = null
            var cap = 0 // 1=v 2=f 3=is/t
            val txt = StringBuilder()
            while (true) {
                val t = x.next()
                if (t == EOF) break
                when (t) {
                    START -> when (x.name) {
                        "row" -> { row = (x.attr("r")?.toIntOrNull() ?: (row + 2)) - 1; col = -1 }
                        "c" -> {
                            val ref = x.attr("r")?.let { Grid.parseRef(it) }
                            if (ref != null) { row = ref[0]; col = ref[1] } else col++
                            type = x.attr("t") ?: ""
                            style = x.attr("s")?.toIntOrNull() ?: 0
                            v = null; f = null
                        }
                        "v" -> { cap = 1; txt.setLength(0) }
                        "f" -> { cap = 2; txt.setLength(0) }
                        "is" -> txt.setLength(0)
                        "t" -> if (type == "inlineStr") cap = 3
                    }
                    TEXT -> if (cap != 0) txt.append(x.text)
                    END -> when (x.name) {
                        "v" -> { v = txt.toString(); cap = 0 }
                        "f" -> { f = txt.toString(); cap = 0 }
                        "t" -> if (cap == 3) cap = 0
                        "is" -> v = txt.toString()
                        "c" -> if (row >= 0 && col >= 0) {
                            val raw = v
                            val shown: String? = when {
                                raw == null -> null
                                type == "s" -> shared.getOrNull(raw.trim().toIntOrNull() ?: -1) ?: ""
                                type == "b" -> if (raw.trim() == "1") "TRUE" else "FALSE"
                                type == "str" || type == "e" || type == "inlineStr" -> raw
                                else -> {
                                    val d = raw.toDoubleOrNull()
                                    if (d != null && style < dates.size && dates[style] && d >= 0 && d < 2958466) serialToDate(d) else tidyNumber(raw)
                                }
                            }
                            if (shown != null || f != null) {
                                g.set(row, col, shown)
                                val ff = f
                                if (ff != null && ff.isNotEmpty() && row < Grid.MAX_ROWS && col < Grid.MAX_COLS) g.formulas[(row shl 16) or col] = "=$ff"
                            }
                        }
                    }
                }
            }
        }
        return out
    }

    fun toHtml(z: ZipDoc): Converted {
        val s = sheets(z)
        return Converted(Grid.toHtml(grids(z)), s.map { it.path })
    }
}

/** Writes cell edits back into an existing XLSX, leaving styles, formulas and other sheets untouched. */
object XlsxPatch {
    private fun cellXml(ref: String, style: String?, value: String): String {
        val s = if (style != null) " s=\"$style\"" else ""
        val v = value.trim()
        return when {
            value.isEmpty() -> "<c r=\"$ref\"$s/>"
            v.startsWith("=") && v.length > 1 -> "<c r=\"$ref\"$s><f>${xmlText(v.substring(1))}</f></c>"
            Grid.isNumber(v) -> "<c r=\"$ref\"$s><v>$v</v></c>"
            else -> "<c r=\"$ref\"$s t=\"inlineStr\"><is><t xml:space=\"preserve\">${xmlText(value)}</t></is></c>"
        }
    }

    private val rowRe = Regex("<row\\b[^>]*?(/?)>")
    private val cellRe = Regex("<c\\b[^>]*?(/>|>[\\s\\S]*?</c>)")
    private fun attrOf(tag: String, name: String): String? = Regex("\\s$name=\"([^\"]*)\"").find(tag)?.groupValues?.get(1)

    /** [edits]: cell reference ("B3") -> new text. */
    fun patchSheet(xml: String, edits: Map<String, String>): String {
        if (edits.isEmpty()) return xml
        var s = xml
        // make sure there is a <sheetData> body to work in
        s = s.replace(Regex("<sheetData\\s*/>"), "<sheetData></sheetData>")
        val byRow = java.util.TreeMap<Int, java.util.TreeMap<Int, Pair<String, String>>>()
        for ((ref, v) in edits) {
            val rc = Grid.parseRef(ref) ?: continue
            byRow.getOrPut(rc[0]) { java.util.TreeMap() }[rc[1]] = Pair(Grid.colName(rc[1]) + (rc[0] + 1), v)
        }
        val sdStart = s.indexOf("<sheetData")
        val sdEnd = s.indexOf("</sheetData>")
        if (sdStart < 0 || sdEnd < 0) return xml
        val bodyStart = s.indexOf('>', sdStart) + 1
        val body = s.substring(bodyStart, sdEnd)
        val out = StringBuilder(body.length + 1024)

        fun newRow(r: Int, cells: java.util.TreeMap<Int, Pair<String, String>>): String {
            val sb = StringBuilder("<row r=\"${r + 1}\">")
            for ((_, p) in cells) if (p.second.isNotEmpty()) sb.append(cellXml(p.first, null, p.second))
            return sb.append("</row>").toString()
        }

        fun patchRow(inner: String, cells: java.util.TreeMap<Int, Pair<String, String>>): String {
            val sb = StringBuilder(inner.length + 256)
            val pending = java.util.TreeMap(cells)
            var last = 0
            var autoCol = -1
            for (m in cellRe.findAll(inner)) {
                val open = m.value.substring(0, m.value.indexOf('>') + 1)
                val ref = attrOf(open, "r")
                val col = ref?.let { Grid.parseRef(it)?.get(1) } ?: (autoCol + 1)
                autoCol = col
                sb.append(inner, last, m.range.first)
                last = m.range.last + 1
                // cells that sort before this one and do not exist yet
                while (pending.isNotEmpty() && pending.firstKey() < col) {
                    val p = pending.pollFirstEntry().value
                    if (p.second.isNotEmpty()) sb.append(cellXml(p.first, null, p.second))
                }
                val e = pending.remove(col)
                if (e != null) sb.append(cellXml(e.first, attrOf(open, "s"), e.second)) else sb.append(m.value)
            }
            sb.append(inner, last, inner.length)
            for ((_, p) in pending) if (p.second.isNotEmpty()) sb.append(cellXml(p.first, null, p.second))
            return sb.toString()
        }

        var pos = 0
        var autoRow = -1
        while (true) {
            val m = rowRe.find(body, pos) ?: break
            val r = attrOf(m.value, "r")?.toIntOrNull()?.minus(1) ?: (autoRow + 1)
            autoRow = r
            out.append(body, pos, m.range.first)
            while (byRow.isNotEmpty() && byRow.firstKey() < r) {
                val e = byRow.pollFirstEntry()
                out.append(newRow(e.key, e.value))
            }
            val cells = byRow.remove(r)
            if (m.groupValues[1] == "/") {
                pos = m.range.last + 1
                if (cells == null) out.append(m.value)
                else out.append(m.value.substring(0, m.value.length - 2)).append('>').append(patchRow("", cells)).append("</row>")
            } else {
                val close = body.indexOf("</row>", m.range.last + 1)
                if (close < 0) { out.append(body, m.range.first, body.length); pos = body.length; break }
                val inner = body.substring(m.range.last + 1, close)
                pos = close + 6
                out.append(m.value)
                out.append(if (cells == null) inner else patchRow(inner, cells))
                out.append("</row>")
            }
        }
        out.append(body, pos, body.length)
        for ((r, cells) in byRow) out.append(newRow(r, cells))
        return s.substring(0, bodyStart) + out + s.substring(sdEnd)
    }

    /** [edits]: sheet part path -> (cell ref -> new text). */
    fun patch(src: File, out: File, edits: Map<String, Map<String, String>>) {
        val rep = HashMap<String, ByteArray?>()
        ZipDoc(src).use { z ->
            for ((path, cells) in edits) {
                val xml = z.text(path) ?: continue
                rep[path] = patchSheet(xml, cells).toByteArray(Charsets.UTF_8)
            }
            // cached calc order is no longer trustworthy: drop it and ask for a recalculation on load
            if (z.has("xl/calcChain.xml")) {
                rep["xl/calcChain.xml"] = null
                z.text("[Content_Types].xml")?.let {
                    rep["[Content_Types].xml"] = it.replace(Regex("<Override[^>]*calcChain[^>]*/>"), "").toByteArray(Charsets.UTF_8)
                }
                z.text("xl/_rels/workbook.xml.rels")?.let {
                    rep["xl/_rels/workbook.xml.rels"] = it.replace(Regex("<Relationship[^>]*calcChain[^>]*/>"), "").toByteArray(Charsets.UTF_8)
                }
            }
            z.text("xl/workbook.xml")?.let { wb ->
                val patched = when {
                    wb.contains("fullCalcOnLoad") -> wb
                    wb.contains("<calcPr") -> wb.replaceFirst("<calcPr", "<calcPr fullCalcOnLoad=\"1\"")
                    else -> wb
                }
                if (patched !== wb) rep["xl/workbook.xml"] = patched.toByteArray(Charsets.UTF_8)
            }
        }
        ZipRewrite.copy(src, out, rep)
    }
}

object GridWriter {
    /** Builds a fresh, minimal XLSX from plain cell text (used for CSV/ODS/XLS -> XLSX). */
    fun xlsx(out: File, sheets: List<Pair<String, List<List<String>>>>) {
        val e = LinkedHashMap<String, ByteArray>()
        val ct = StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>")
        val wb = StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets>")
        val rels = StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">")
        val list = if (sheets.isEmpty()) listOf(Pair("Sheet1", emptyList())) else sheets
        val used = HashSet<String>()
        for ((i, s) in list.withIndex()) {
            val n = i + 1
            var name = s.first.replace(Regex("[\\[\\]:*?/\\\\]"), " ").trim().take(31).ifEmpty { "Sheet$n" }
            if (!used.add(name.lowercase())) { name = "${name.take(27)} $n"; used.add(name.lowercase()) }
            ct.append("<Override PartName=\"/xl/worksheets/sheet$n.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>")
            wb.append("<sheet name=\"${xmlText(name)}\" sheetId=\"$n\" r:id=\"rId$n\"/>")
            rels.append("<Relationship Id=\"rId$n\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet$n.xml\"/>")
            val sb = StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>")
            for ((r, row) in s.second.withIndex()) {
                if (row.all { it.isEmpty() }) continue
                sb.append("<row r=\"${r + 1}\">")
                for ((c, v) in row.withIndex()) {
                    if (v.isEmpty()) continue
                    val ref = Grid.colName(c) + (r + 1)
                    val tv = v.trim()
                    if (tv.startsWith("=") && tv.length > 1) sb.append("<c r=\"$ref\"><f>${xmlText(tv.substring(1))}</f></c>")
                    else if (Grid.isNumber(tv)) sb.append("<c r=\"$ref\"><v>$tv</v></c>")
                    else sb.append("<c r=\"$ref\" t=\"inlineStr\"><is><t xml:space=\"preserve\">${xmlText(v)}</t></is></c>")
                }
                sb.append("</row>")
            }
            sb.append("</sheetData></worksheet>")
            e["xl/worksheets/sheet$n.xml"] = sb.toString().toByteArray(Charsets.UTF_8)
        }
        ct.append("</Types>")
        wb.append("</sheets></workbook>")
        rels.append("</Relationships>")
        val all = LinkedHashMap<String, ByteArray>()
        all["[Content_Types].xml"] = ct.toString().toByteArray(Charsets.UTF_8)
        all["_rels/.rels"] = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>".toByteArray(Charsets.UTF_8)
        all["xl/workbook.xml"] = wb.toString().toByteArray(Charsets.UTF_8)
        all["xl/_rels/workbook.xml.rels"] = rels.toString().toByteArray(Charsets.UTF_8)
        all.putAll(e)
        ZipRewrite.write(out, all)
    }

    fun csv(rows: List<List<String>>, sep: Char = ','): String {
        val sb = StringBuilder()
        var lastUsed = rows.size - 1
        while (lastUsed >= 0 && rows[lastUsed].all { it.isEmpty() }) lastUsed--
        for (r in 0..lastUsed) {
            val row = rows[r]
            var n = row.size
            while (n > 0 && row[n - 1].isEmpty()) n--
            for (c in 0 until n) {
                if (c > 0) sb.append(sep)
                val v = row[c]
                if (v.indexOf(sep) >= 0 || v.indexOf('"') >= 0 || v.indexOf('\n') >= 0 || v.indexOf('\r') >= 0 || v.startsWith(" ") || v.endsWith(" "))
                    sb.append('"').append(v.replace("\"", "\"\"")).append('"')
                else sb.append(v)
            }
            sb.append('\n')
        }
        return sb.toString()
    }
}

object Csv {
    fun separator(text: String): Char {
        val line = text.lineSequence().firstOrNull { it.isNotBlank() } ?: return ','
        var best = ','
        var bestN = -1
        for (c in charArrayOf(',', ';', '\t', '|')) {
            var n = 0
            var q = false
            for (ch in line) { if (ch == '"') q = !q else if (ch == c && !q) n++ }
            if (n > bestN) { bestN = n; best = c }
        }
        return best
    }

    fun parse(text: String, sep: Char = separator(text), name: String = "CSV"): Grid {
        val g = Grid(name)
        var r = 0
        var c = 0
        val cell = StringBuilder()
        var q = false
        var any = false
        var i = 0
        val n = text.length
        fun endCell() {
            if (cell.isNotEmpty()) g.set(r, c, cell.toString())
            cell.setLength(0)
            c++
        }
        while (i < n) {
            val ch = text[i]
            if (q) {
                if (ch == '"') {
                    if (i + 1 < n && text[i + 1] == '"') { cell.append('"'); i++ } else q = false
                } else cell.append(ch)
            } else when (ch) {
                '"' -> if (cell.isEmpty()) q = true else cell.append(ch)
                sep -> { endCell(); any = true }
                '\r' -> {}
                '\n' -> { endCell(); r++; c = 0; any = false }
                else -> { cell.append(ch); any = true }
            }
            i++
        }
        if (any || cell.isNotEmpty()) endCell()
        return g
    }
}
