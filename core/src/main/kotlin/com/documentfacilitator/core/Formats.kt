package com.documentfacilitator.core

import com.documentfacilitator.core.XmlScanner.Companion.END
import com.documentfacilitator.core.XmlScanner.Companion.EOF
import com.documentfacilitator.core.XmlScanner.Companion.START
import com.documentfacilitator.core.XmlScanner.Companion.TEXT

/** OpenDocument (odt / odp / ods). */
object Odf {
    private fun styles(xml: String): Map<String, String> {
        val out = HashMap<String, String>()
        val x = XmlScanner(xml)
        var name: String? = null
        val css = StringBuilder()
        while (true) {
            val t = x.next()
            if (t == EOF) break
            if (t == START) when (x.name) {
                "style:style" -> { name = x.attr("style:name"); css.setLength(0) }
                "style:text-properties" -> if (name != null) {
                    if (x.attr("fo:font-weight") == "bold") css.append("font-weight:bold;")
                    if (x.attr("fo:font-style") == "italic") css.append("font-style:italic;")
                    val ul = x.attr("style:text-underline-style")
                    if (ul != null && ul != "none") css.append("text-decoration:underline;")
                    x.attr("fo:color")?.takeIf { Regex("#[0-9a-fA-F]{6}").matches(it) }?.let { css.append("color:").append(it).append(';') }
                    x.attr("fo:font-size")?.takeIf { Regex("[0-9.]+pt").matches(it) }?.let { css.append("font-size:").append(it).append(';') }
                }
                "style:paragraph-properties" -> if (name != null) when (x.attr("fo:text-align")) {
                    "center" -> css.append("text-align:center;")
                    "end", "right" -> css.append("text-align:right;")
                    "justify" -> css.append("text-align:justify;")
                }
            } else if (t == END && x.name == "style:style") {
                val n = name
                if (n != null && css.isNotEmpty()) out[n] = css.toString()
                name = null
            }
        }
        return out
    }

    fun toHtml(z: ZipDoc): Converted {
        val xml = z.text("content.xml") ?: return Converted("<p>This document could not be read.</p>")
        val st = styles(xml)
        val sb = StringBuilder(xml.length / 3)
        val x = XmlScanner(xml)
        var body = false
        var textDepth = 0
        val closers = ArrayList<String>()
        var slide = 0
        while (true) {
            val t = x.next()
            if (t == EOF) break
            if (t == TEXT) { if (body && textDepth > 0) sb.append(esc(x.text)); continue }
            val n = x.name
            if (t == START) {
                if (n == "office:body") { body = true; continue }
                if (!body) continue
                fun styled(tag: String, attrName: String): String {
                    val css = st[x.attr(attrName) ?: ""]
                    return if (css != null) "<$tag style=\"$css\">" else "<$tag>"
                }
                when (n) {
                    "text:h" -> {
                        val l = (x.attr("text:outline-level")?.toIntOrNull() ?: 1).coerceIn(1, 6)
                        if (textDepth == 0) { sb.append(styled("h$l", "text:style-name")); closers.add("</h$l>") } else closers.add("")
                        textDepth++
                    }
                    "text:p" -> {
                        if (textDepth == 0) { sb.append(styled("p", "text:style-name")); closers.add("</p>") } else { sb.append("<br>"); closers.add("") }
                        textDepth++
                    }
                    "text:span" -> if (textDepth > 0) sb.append(styled("span", "text:style-name"))
                    "text:s" -> if (textDepth > 0) { val c = (x.attr("text:c")?.toIntOrNull() ?: 1).coerceIn(1, 200); repeat(c) { sb.append("\u00a0") } }
                    "text:tab" -> if (textDepth > 0) sb.append("\u2003")
                    "text:line-break" -> if (textDepth > 0) sb.append("<br>")
                    "text:list" -> if (textDepth == 0) sb.append("<ul>")
                    "text:list-item" -> if (textDepth == 0) sb.append("<li>")
                    "table:table" -> if (textDepth == 0) sb.append("<table>")
                    "table:table-row" -> if (textDepth == 0) sb.append("<tr>")
                    "table:table-cell" -> if (textDepth == 0) {
                        val span = x.attr("table:number-columns-spanned")?.toIntOrNull() ?: 1
                        sb.append(if (span > 1) "<td colspan=\"${span.coerceAtMost(64)}\">" else "<td>")
                    }
                    "draw:page" -> {
                        slide++
                        sb.append("<section class=\"slide\"><div class=\"x sn\" contenteditable=\"false\">").append(slide).append("</div>")
                    }
                    "draw:image" -> {
                        val href = x.attr("xlink:href")
                        if (href != null && !href.contains(":") && !href.endsWith(".svm") && !href.endsWith(".wmf") && !href.endsWith(".emf"))
                            sb.append("<img src=\"").append(RES_BASE).append(esc(resolvePath("", href))).append("\">")
                    }
                }
            } else {
                if (!body) continue
                when (n) {
                    "office:body" -> body = false
                    "text:h", "text:p" -> if (textDepth > 0) { textDepth--; sb.append(closers.removeAt(closers.size - 1)) }
                    "text:span" -> if (textDepth > 0) sb.append("</span>")
                    "text:list" -> if (textDepth == 0) sb.append("</ul>")
                    "text:list-item" -> if (textDepth == 0) sb.append("</li>")
                    "table:table" -> if (textDepth == 0) sb.append("</table>")
                    "table:table-row" -> if (textDepth == 0) sb.append("</tr>")
                    "table:table-cell" -> if (textDepth == 0) sb.append("</td>")
                    "draw:page" -> sb.append("</section>")
                }
            }
        }
        return Converted(sb.toString())
    }

    fun grids(z: ZipDoc): List<Grid> {
        val xml = z.text("content.xml") ?: return emptyList()
        val out = ArrayList<Grid>()
        val x = XmlScanner(xml)
        var g: Grid? = null
        var r = 0
        var c = 0
        var rowRep = 1
        var colRep = 1
        var inCell = false
        var pCount = 0
        var formula: String? = null
        val cell = StringBuilder()
        var depthTable = 0
        while (true) {
            val t = x.next()
            if (t == EOF) break
            if (t == TEXT) { if (inCell && pCount > 0) cell.append(x.text); continue }
            val n = x.name
            if (t == START) when (n) {
                "table:table" -> { depthTable++; if (depthTable == 1) { g = Grid(x.attr("table:name") ?: "Sheet"); out.add(g); r = 0 } }
                "table:table-row" -> if (depthTable == 1) { c = 0; rowRep = (x.attr("table:number-rows-repeated")?.toIntOrNull() ?: 1).coerceAtLeast(1) }
                "table:table-cell", "table:covered-table-cell" -> if (depthTable == 1) {
                    colRep = (x.attr("table:number-columns-repeated")?.toIntOrNull() ?: 1).coerceAtLeast(1)
                    inCell = true; pCount = 0; cell.setLength(0)
                    formula = x.attr("table:formula")
                }
                "text:p" -> if (inCell) { if (pCount > 0) cell.append('\n'); pCount++ }
                "text:s" -> if (inCell && pCount > 0) cell.append(' ')
            } else when (n) {
                "table:table" -> { depthTable--; if (depthTable == 0) g = null }
                "table:table-cell", "table:covered-table-cell" -> if (depthTable == 1 && inCell) {
                    inCell = false
                    val gg = g
                    if (gg != null && cell.isNotEmpty()) {
                        val v = cell.toString()
                        for (k in 0 until minOf(colRep, Grid.MAX_COLS)) {
                            gg.set(r, c + k, v)
                            formula?.let { f -> if (r < Grid.MAX_ROWS && c + k < Grid.MAX_COLS) gg.formulas[(r shl 16) or (c + k)] = f.removePrefix("of:") }
                        }
                    }
                    c += colRep
                }
                "table:table-row" -> if (depthTable == 1) {
                    val gg = g
                    // repeated non-empty rows are real data; repeated empty rows are just padding
                    if (gg != null && rowRep > 1 && r < gg.rows.size && gg.rows[r].any { it != null }) {
                        val src = gg.rows[r]
                        for (k in 1 until minOf(rowRep, Grid.MAX_ROWS - r)) for (ci in src.indices) src[ci]?.let { gg.set(r + k, ci, it) }
                    }
                    r += rowRep
                }
            }
        }
        return out
    }
}

object Epub {
    fun toHtml(z: ZipDoc): Converted {
        val container = z.text("META-INF/container.xml") ?: return Converted("<p>Not a valid EPUB.</p>")
        var opfPath: String? = null
        var x = XmlScanner(container)
        while (true) {
            val t = x.next()
            if (t == EOF) break
            if (t == START && x.name.endsWith("rootfile")) { opfPath = x.attr("full-path"); break }
        }
        val opf = opfPath?.let { z.text(it) } ?: return Converted("<p>Not a valid EPUB.</p>")
        val dir = opfPath.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
        val items = HashMap<String, String>()
        val spine = ArrayList<String>()
        var title: String? = null
        var inTitle = false
        x = XmlScanner(opf)
        while (true) {
            val t = x.next()
            if (t == EOF) break
            val n = x.name.substringAfter(':')
            if (t == START) when (n) {
                "item" -> { val id = x.attr("id"); val href = x.attr("href"); if (id != null && href != null) items[id] = resolvePath(dir, java.net.URLDecoder.decode(href.replace("+", "%2B"), "UTF-8")) }
                "itemref" -> x.attr("idref")?.let { spine.add(it) }
                "title" -> inTitle = true
            } else if (t == TEXT) { if (inTitle && title == null) title = x.text.trim() }
            else if (n == "title") inTitle = false
        }
        val sb = StringBuilder()
        var note: String? = null
        val attrRe = Regex("(\\s)(src|xlink:href|href)\\s*=\\s*(\"([^\"]*)\"|'([^']*)')")
        for (id in spine) {
            val path = items[id] ?: continue
            val html = z.text(path) ?: continue
            if (sb.length > 8_000_000) { note = "This book is very long; only the first part is shown."; break }
            val lower = html.lowercase()
            var a = lower.indexOf("<body")
            a = if (a < 0) 0 else lower.indexOf('>', a) + 1
            var b = lower.lastIndexOf("</body>")
            if (b < a) b = html.length
            val cdir = path.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
            val chapter = attrRe.replace(Html.stripActive(html.substring(a, b))) { m ->
                val attr = m.groupValues[2]
                val v = unescape(m.groupValues[4].ifEmpty { m.groupValues[5] })
                if (attr == "href") "${m.groupValues[1]}data-href=\"\""
                else if (v.startsWith("data:")) m.value
                else {
                    val clean = try { java.net.URLDecoder.decode(v.substringBefore('#').replace("+", "%2B"), "UTF-8") } catch (_: Exception) { v }
                    "${m.groupValues[1]}$attr=\"$RES_BASE${esc(resolvePath(cdir, clean))}\""
                }
            }
            sb.append("<div class=\"chapter\">").append(chapter).append("</div>")
        }
        if (sb.isEmpty()) sb.append("<p>This EPUB has no readable chapters.</p>")
        return Converted(sb.toString(), note = note)
    }
}

object Html {
    private val activeRe = Regex("<(script|style|iframe|object|embed|link|meta|base|form)\\b[\\s\\S]*?(</\\1\\s*>|/>|(?<=>))", RegexOption.IGNORE_CASE)
    private val scriptRe = Regex("<script\\b[\\s\\S]*?</script\\s*>|<script\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val onRe = Regex("\\s+on[a-zA-Z]+\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s>]+)")
    private val jsUrlRe = Regex("(href|src)\\s*=\\s*([\"']?)\\s*javascript:[^\"'>]*\\2", RegexOption.IGNORE_CASE)

    /** Removes scripts and inline handlers. The viewer also enforces a strict CSP, this is defence in depth. */
    fun stripActive(html: String): String =
        jsUrlRe.replace(onRe.replace(scriptRe.replace(html, ""), ""), "")

    /** Body content (plus any <style> blocks) of an HTML file, ready to be placed inside the viewer page. */
    fun body(html: String): String {
        val clean = stripActive(html)
        val lower = clean.lowercase()
        val sb = StringBuilder()
        var from = 0
        while (true) {
            val a = lower.indexOf("<style", from)
            if (a < 0) break
            val b = lower.indexOf("</style>", a)
            if (b < 0) break
            sb.append(clean, a, b + 8)
            from = b + 8
        }
        var a = lower.indexOf("<body")
        val start = if (a < 0) { a = lower.indexOf("</head>"); if (a < 0) 0 else a + 7 } else lower.indexOf('>', a) + 1
        var end = lower.lastIndexOf("</body>")
        if (end < start) end = clean.length
        val inner = clean.substring(start, end)
        return if (lower.indexOf("<body") < 0 && sb.isNotEmpty()) inner else sb.append(inner).toString()
    }

    fun title(html: String): String? = Regex("<title[^>]*>([\\s\\S]*?)</title>", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)?.trim()

    /** Wraps edited body HTML into a standalone file. */
    fun document(title: String, body: String): String =
        "<!DOCTYPE html>\n<html>\n<head>\n<meta charset=\"utf-8\">\n<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n<title>${esc(title)}</title>\n<style>body{font-family:sans-serif;line-height:1.5;max-width:50em;margin:1em auto;padding:0 1em}table{border-collapse:collapse}td,th{border:1px solid #999;padding:.2em .5em}img{max-width:100%}</style>\n</head>\n<body>\n$body\n</body>\n</html>\n"
}

object Markdown {
    private fun inline(t: String): String {
        var s = esc(t)
        val codes = ArrayList<String>()
        s = Regex("`([^`]+)`").replace(s) { codes.add(it.groupValues[1]); "\u0001${codes.size - 1}\u0001" }
        s = Regex("!\\[([^\\]]*)]\\(([^)\\s]+)[^)]*\\)").replace(s) { m ->
            val src = m.groupValues[2]
            if (src.startsWith("data:image/")) "<img alt=\"${m.groupValues[1]}\" src=\"$src\">" else "<span class=\"imgalt\">[${m.groupValues[1].ifEmpty { "image" }}]</span>"
        }
        s = Regex("\\[([^\\]]+)]\\(([^)\\s]+)[^)]*\\)").replace(s) { "<u title=\"${it.groupValues[2]}\">${it.groupValues[1]}</u>" }
        s = Regex("\\*\\*([^*]+)\\*\\*|__([^_]+)__").replace(s) { "<b>${it.groupValues[1].ifEmpty { it.groupValues[2] }}</b>" }
        s = Regex("(?<![\\w*])\\*([^*\\s][^*]*)\\*|(?<![\\w_])_([^_\\s][^_]*)_(?!\\w)").replace(s) { "<i>${it.groupValues[1].ifEmpty { it.groupValues[2] }}</i>" }
        s = Regex("~~([^~]+)~~").replace(s) { "<s>${it.groupValues[1]}</s>" }
        s = Regex("\u0001(\\d+)\u0001").replace(s) { "<code>${codes[it.groupValues[1].toInt()]}</code>" }
        return s
    }

    fun toHtml(md: String): String {
        val lines = md.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val sb = StringBuilder(md.length + md.length / 4)
        val para = StringBuilder()
        val lists = ArrayList<Pair<Int, String>>() // indent, tag
        fun flushPara() { if (para.isNotEmpty()) { sb.append("<p>").append(inline(para.toString())).append("</p>\n"); para.setLength(0) } }
        fun closeLists(toIndent: Int) {
            while (lists.isNotEmpty() && lists.last().first > toIndent) { sb.append("</li></").append(lists.last().second).append(">\n"); lists.removeAt(lists.size - 1) }
        }
        val liRe = Regex("^(\\s*)([-*+]|\\d+[.)])\\s+(.*)$")
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val tr = line.trim()
            if (tr.startsWith("```") || tr.startsWith("~~~")) {
                flushPara(); closeLists(-1)
                val fence = tr.substring(0, 3)
                sb.append("<pre><code>")
                i++
                while (i < lines.size && !lines[i].trim().startsWith(fence)) { sb.append(esc(lines[i])).append('\n'); i++ }
                sb.append("</code></pre>\n")
                i++
                continue
            }
            if (tr.isEmpty()) { flushPara(); if (i + 1 < lines.size && liRe.matchEntire(lines[i + 1]) == null) closeLists(-1); i++; continue }
            val h = Regex("^(#{1,6})\\s+(.*?)\\s*#*$").matchEntire(tr)
            if (h != null) {
                flushPara(); closeLists(-1)
                val l = h.groupValues[1].length
                sb.append("<h$l>").append(inline(h.groupValues[2])).append("</h$l>\n")
                i++; continue
            }
            if (Regex("^([-*_])(\\s*\\1){2,}$").matches(tr)) { flushPara(); closeLists(-1); sb.append("<hr>\n"); i++; continue }
            if (tr.startsWith(">")) {
                flushPara(); closeLists(-1)
                val q = StringBuilder()
                while (i < lines.size && lines[i].trim().startsWith(">")) { q.append(lines[i].trim().removePrefix(">").trim()).append(' '); i++ }
                sb.append("<blockquote>").append(inline(q.toString().trim())).append("</blockquote>\n")
                continue
            }
            if (tr.startsWith("|") && i + 1 < lines.size && Regex("^\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?$").matches(lines[i + 1].trim())) {
                flushPara(); closeLists(-1)
                fun cells(l: String) = l.trim().removePrefix("|").removeSuffix("|").split('|').map { it.trim() }
                sb.append("<table><tr>")
                for (c in cells(line)) sb.append("<th>").append(inline(c)).append("</th>")
                sb.append("</tr>")
                i += 2
                while (i < lines.size && lines[i].trim().startsWith("|")) {
                    sb.append("<tr>")
                    for (c in cells(lines[i])) sb.append("<td>").append(inline(c)).append("</td>")
                    sb.append("</tr>")
                    i++
                }
                sb.append("</table>\n")
                continue
            }
            val li = liRe.matchEntire(line)
            if (li != null) {
                flushPara()
                val indent = li.groupValues[1].replace("\t", "    ").length
                val tag = if (li.groupValues[2][0].isDigit()) "ol" else "ul"
                closeLists(indent)
                if (lists.isNotEmpty() && lists.last().first == indent) sb.append("</li>")
                else { sb.append('<').append(tag).append('>'); lists.add(Pair(indent, tag)) }
                var body = li.groupValues[3]
                val task = Regex("^\\[([ xX])]\\s+(.*)$").matchEntire(body)
                if (task != null) body = (if (task.groupValues[1] == " ") "\u2610 " else "\u2612 ") + task.groupValues[2]
                sb.append("<li>").append(inline(body))
                i++; continue
            }
            if (lists.isNotEmpty() && line.startsWith(" ")) { sb.append(' ').append(inline(tr)); i++; continue }
            closeLists(-1)
            if (para.isNotEmpty()) para.append(if (line.endsWith("  ")) "\n" else " ")
            para.append(tr)
            i++
        }
        flushPara(); closeLists(-1)
        return sb.toString().replace("\n</p>", "</p>")
    }
}

object Rtf {
    private val skipDest = setOf(
        "fonttbl", "colortbl", "stylesheet", "info", "pict", "header", "footer", "headerl", "headerr", "footerl", "footerr",
        "footnote", "generator", "object", "themedata", "colorschememapping", "latentstyles", "datastore", "xmlnstbl",
        "listtable", "listoverridetable", "rsidtbl", "fldinst", "nonshppict", "bkmkstart", "bkmkend", "filetbl", "revtbl"
    )

    private class St(var b: Boolean = false, var i: Boolean = false, var u: Boolean = false, var skip: Boolean = false, var uc: Int = 1, var fs: Int = 0) {
        fun copy() = St(b, i, u, skip, uc, fs)
    }

    fun toHtml(src: ByteArray): String {
        val s = String(src, Charsets.ISO_8859_1)
        val sb = StringBuilder(s.length / 2)
        val stack = ArrayList<St>()
        var st = St()
        var align = ""
        val para = StringBuilder()
        val row = ArrayList<String>()
        var inTable = false
        var tableOpen = false
        var open = "" // currently open inline style signature
        var skipChars = 0
        val pending = java.io.ByteArrayOutputStream()

        fun flushBytes() {
            if (pending.size() > 0) {
                val t = String(pending.toByteArray(), charset("windows-1252"))
                pending.reset()
                para.append(esc(t))
            }
        }
        fun setStyle() {
            flushBytes()
            val want = (if (st.b) "b" else "") + (if (st.i) "i" else "") + (if (st.u) "u" else "") + (if (st.fs > 0) "s${st.fs}" else "")
            if (want == open) return
            if (open.isNotEmpty()) para.append("</span>")
            open = want
            if (want.isNotEmpty()) {
                para.append("<span style=\"")
                if (st.b) para.append("font-weight:bold;")
                if (st.i) para.append("font-style:italic;")
                if (st.u) para.append("text-decoration:underline;")
                if (st.fs > 0) para.append("font-size:").append(st.fs / 2f).append("pt;")
                para.append("\">")
            }
        }
        fun closeInline() { flushBytes(); if (open.isNotEmpty()) { para.append("</span>"); open = "" } }
        fun endTable() { if (tableOpen) { sb.append("</table>"); tableOpen = false } }
        fun endPara() {
            closeInline()
            if (inTable) { para.append("<br>"); return }
            endTable()
            sb.append(if (align.isEmpty()) "<p>" else "<p style=\"text-align:$align\">")
            sb.append(if (para.isEmpty()) "<br>" else para).append("</p>")
            para.setLength(0)
        }
        fun text(c: Char) {
            if (st.skip) return
            if (skipChars > 0) { skipChars--; return }
            setStyle()
            if (c.code < 256) pending.write(c.code) else { flushBytes(); para.append(c) }
        }

        var i = 0
        val n = s.length
        while (i < n) {
            val c = s[i]
            when (c) {
                '{' -> { stack.add(st); st = st.copy(); i++ }
                '}' -> { flushBytes(); if (stack.isNotEmpty()) st = stack.removeAt(stack.size - 1); i++ }
                '\r', '\n' -> i++
                '\\' -> {
                    i++
                    if (i >= n) break
                    val d = s[i]
                    if (d == '\\' || d == '{' || d == '}') { text(d); i++ }
                    else if (d == '\'') {
                        val hex = if (i + 2 < n) s.substring(i + 1, i + 3).toIntOrNull(16) else null
                        i += 3
                        if (hex != null) text(hex.toChar())
                    } else if (d == '*') { st.skip = true; i++ }
                    else if (d == '~') { text('\u00a0'); i++ }
                    else if (d == '-' || d == '_' || d == '|' || d == ':') i++
                    else if (d == '\n' || d == '\r') { if (!st.skip) endPara(); i++ }
                    else if (d.isLetter()) {
                        val a = i
                        while (i < n && s[i].isLetter()) i++
                        val word = s.substring(a, i)
                        var num: Int? = null
                        val ns = i
                        if (i < n && (s[i] == '-' || s[i].isDigit())) {
                            i++
                            while (i < n && s[i].isDigit()) i++
                            num = s.substring(ns, i).toIntOrNull()
                        }
                        if (i < n && s[i] == ' ') i++
                        if (word in skipDest) { st.skip = true; continue }
                        if (st.skip) continue
                        when (word) {
                            "par" -> endPara()
                            "line" -> { setStyle(); flushBytes(); para.append("<br>") }
                            "tab" -> text('\u2003')
                            "b" -> st.b = num != 0
                            "i" -> st.i = num != 0
                            "ul" -> st.u = num != 0
                            "ulnone" -> st.u = false
                            "plain" -> { st.b = false; st.i = false; st.u = false; st.fs = 0 }
                            "fs" -> st.fs = num ?: 0
                            "pard" -> { align = ""; inTable = false }
                            "qc" -> align = "center"
                            "qr" -> align = "right"
                            "qj" -> align = "justify"
                            "ql" -> align = ""
                            "uc" -> st.uc = num ?: 1
                            "u" -> if (num != null) {
                                val cp = if (num < 0) num + 65536 else num
                                setStyle(); flushBytes(); para.append(esc(cp.toChar().toString()))
                                skipChars = st.uc
                            }
                            "intbl" -> inTable = true
                            "cell" -> { closeInline(); row.add(para.toString()); para.setLength(0) }
                            "row" -> {
                                if (!tableOpen) { sb.append("<table>"); tableOpen = true }
                                sb.append("<tr>")
                                for (cell in row) sb.append("<td>").append(cell).append("</td>")
                                sb.append("</tr>")
                                row.clear(); inTable = false
                            }
                            "emdash" -> text('\u2014')
                            "endash" -> text('\u2013')
                            "lquote" -> text('\u2018')
                            "rquote" -> text('\u2019')
                            "ldblquote" -> text('\u201c')
                            "rdblquote" -> text('\u201d')
                            "bullet" -> text('\u2022')
                        }
                    } else i++
                }
                else -> { text(c); i++ }
            }
        }
        closeInline()
        if (para.isNotEmpty() || row.isNotEmpty()) { row.forEach { para.insert(0, "$it ") }; inTable = false; endPara() }
        endTable()
        return sb.toString()
    }
}

object PlainText {
    /** Decodes text honouring BOMs, falling back from UTF-8 to Windows-1252. */
    fun decode(b: ByteArray): String {
        if (b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte()) return String(b, 3, b.size - 3, Charsets.UTF_8)
        if (b.size >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte()) return String(b, 2, b.size - 2, Charsets.UTF_16LE)
        if (b.size >= 2 && b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte()) return String(b, 2, b.size - 2, Charsets.UTF_16BE)
        val dec = Charsets.UTF_8.newDecoder()
        return try {
            dec.decode(java.nio.ByteBuffer.wrap(b)).toString()
        } catch (_: Exception) {
            String(b, charset("windows-1252"))
        }
    }

    fun toHtml(text: String): String = "<pre class=\"plain\">${esc(text)}</pre>"
}
