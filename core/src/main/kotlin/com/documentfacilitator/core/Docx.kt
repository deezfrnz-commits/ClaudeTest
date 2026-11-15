package com.documentfacilitator.core

import com.documentfacilitator.core.XmlScanner.Companion.END
import com.documentfacilitator.core.XmlScanner.Companion.EOF
import com.documentfacilitator.core.XmlScanner.Companion.START
import com.documentfacilitator.core.XmlScanner.Companion.TEXT

/** Host used for resources the viewer serves straight out of the document container. */
const val RES_BASE = "https://df.local/z/"

/**
 * DOCX -> HTML. Every `w:t` becomes `<span data-r="0:N">` (N = index of that element in the part)
 * and every paragraph without text becomes `<p data-e="0:N">`, so edits can be written back into
 * the original XML without touching any formatting (see [OoxmlPatch]).
 */
object Docx {
    const val PART = "word/document.xml"

    private val highlight = mapOf(
        "yellow" to "#ffff00", "green" to "#00ff00", "cyan" to "#00ffff", "magenta" to "#ff00ff",
        "blue" to "#0000ff", "red" to "#ff0000", "darkBlue" to "#000080", "darkCyan" to "#008080",
        "darkGreen" to "#008000", "darkMagenta" to "#800080", "darkRed" to "#800000",
        "darkYellow" to "#808000", "darkGray" to "#808080", "lightGray" to "#c0c0c0", "black" to "#000000"
    )

    private fun on(x: XmlScanner): Boolean {
        val v = x.attr("w:val")
        return v == null || !(v == "0" || v == "false" || v == "none")
    }

    /** styleId -> heading level (1..6); [nums] receives styleId -> numId for list styles. */
    private fun headingStyles(xml: String?, nums: HashMap<String, String>): Map<String, Int> {
        val out = HashMap<String, Int>()
        if (xml == null) return out
        val x = XmlScanner(xml)
        var id: String? = null
        while (true) {
            val t = x.next()
            if (t == EOF) break
            if (t != START) continue
            when (x.name) {
                "w:style" -> id = x.attr("w:styleId")
                "w:name" -> {
                    val n = (x.attr("w:val") ?: "").lowercase()
                    val cur = id
                    if (cur != null) {
                        if (n == "title") out[cur] = 1
                        else if (n == "subtitle") out[cur] = 2
                        else if (n.startsWith("heading ")) n.substring(8).trim().toIntOrNull()?.let { out[cur] = it.coerceIn(1, 6) }
                    }
                }
                "w:numId" -> { val cur = id; val v = x.attr("w:val"); if (cur != null && v != null) nums[cur] = v }
                "w:outlineLvl" -> {
                    val cur = id
                    val l = x.attr("w:val")?.toIntOrNull()
                    if (cur != null && l != null && l in 0..5 && !out.containsKey(cur)) out[cur] = l + 1
                }
            }
        }
        return out
    }

    /** numId -> true when the list's first level is a bullet. */
    private fun bulletNums(xml: String?): Map<String, Boolean> {
        val out = HashMap<String, Boolean>()
        if (xml == null) return out
        val abs = HashMap<String, Boolean>()
        val x = XmlScanner(xml)
        var absId: String? = null
        var lvl = -1
        var numId: String? = null
        while (true) {
            val t = x.next()
            if (t == EOF) break
            if (t != START) continue
            when (x.name) {
                "w:abstractNum" -> { absId = x.attr("w:abstractNumId"); numId = null }
                "w:lvl" -> lvl = x.attr("w:ilvl")?.toIntOrNull() ?: -1
                "w:numFmt" -> if (absId != null && numId == null && lvl == 0) abs[absId] = x.attr("w:val") == "bullet"
                "w:num" -> { numId = x.attr("w:numId"); absId = null }
                "w:abstractNumId" -> { val n = numId; if (n != null) out[n] = abs[x.attr("w:val") ?: ""] ?: true }
            }
        }
        return out
    }

    private class Para(val idx: Int) {
        var opened = false
        var style: String? = null
        var jc: String? = null
        var numId: String? = null
        var ilvl = 0
        var closeTag = ""
    }

    fun toHtml(z: ZipDoc): Converted {
        val xml = z.text(PART) ?: return Converted("<p>This document could not be read (missing or oversized body).</p>")
        val rels = z.rels(PART)
        val styleNums = HashMap<String, String>()
        val heads = headingStyles(z.text("word/styles.xml"), styleNums)
        val bullets = bulletNums(z.text("word/numbering.xml"))
        val sb = StringBuilder(xml.length / 3 + 256)
        val x = XmlScanner(xml)

        var tIdx = 0
        var pIdx = 0
        var skip = 0
        val paras = ArrayList<Para>()
        var inPPr = false
        var inRPr = false
        var inRun = false
        var inT = false
        var inNumPr = false
        // run formatting
        var b = false; var i = false; var u = false; var st = false
        var color: String? = null; var size: String? = null; var hl: String? = null; var va: String? = null
        // tables
        val pendingTd = ArrayList<Int>() // per open cell: colspan when the <td> is not yet written, else -1
        // drawings
        var extW = 0; var extH = 0
        // fields / content controls
        var fldBegin = false; var ffText = false; var ffResult = false
        val sdtInline = ArrayList<Boolean>()
        var sdtBlock = 0
        var sdtPh = false

        fun flushTd() {
            if (pendingTd.isNotEmpty() && pendingTd[pendingTd.size - 1] >= 0) {
                val span = pendingTd[pendingTd.size - 1]
                sb.append(if (span > 1) "<td colspan=\"$span\">" else "<td>")
                pendingTd[pendingTd.size - 1] = -1
            }
        }

        fun openPara(empty: Boolean) {
            val p = paras.lastOrNull() ?: return
            if (p.opened) return
            p.opened = true
            val nested = paras.size > 1
            if (!nested) flushTd()
            val cls = StringBuilder()
            val css = StringBuilder()
            var tag = "p"
            val lvl = p.style?.let { heads[it] }
            if (nested) {
                tag = "span"; cls.append("tp")
            } else if (lvl != null) {
                tag = "h$lvl"
            } else if ((p.numId ?: p.style?.let { styleNums[it] }).let { it != null && it != "0" }) {
                cls.append(if (bullets[p.numId ?: styleNums[p.style!!]!!] != false) "li" else "li ol")
                css.append("margin-left:").append((p.ilvl + 1) * 1.5).append("em;")
            }
            if (sdtBlock > 0) { if (cls.isNotEmpty()) cls.append(' '); cls.append("sdt") }
            when (p.jc) {
                "center" -> css.append("text-align:center;")
                "right", "end" -> css.append("text-align:right;")
                "both", "distribute" -> css.append("text-align:justify;")
            }
            sb.append('<').append(tag)
            if (empty) sb.append(" data-e=\"0:").append(p.idx).append('"')
            if (cls.isNotEmpty()) sb.append(" class=\"").append(cls).append('"')
            if (css.isNotEmpty()) sb.append(" style=\"").append(css).append('"')
            sb.append('>')
            p.closeTag = "</$tag>"
        }

        fun runCss(): String {
            if (!(b || i || u || st || color != null || size != null || hl != null || va != null)) return ""
            val c = StringBuilder()
            if (b) c.append("font-weight:bold;")
            if (i) c.append("font-style:italic;")
            if (u && st) c.append("text-decoration:underline line-through;")
            else if (u) c.append("text-decoration:underline;")
            else if (st) c.append("text-decoration:line-through;")
            color?.let { c.append("color:#").append(it).append(';') }
            size?.let { c.append("font-size:").append(it).append("pt;") }
            hl?.let { c.append("background:").append(it).append(';') }
            when (va) {
                "superscript" -> c.append("vertical-align:super;font-size:70%;")
                "subscript" -> c.append("vertical-align:sub;font-size:70%;")
            }
            return c.toString()
        }

        fun img(id: String?) {
            val path = rels[id ?: return] ?: return
            if (paras.isEmpty()) flushTd() else openPara(false)
            sb.append("<img src=\"").append(RES_BASE).append(esc(path)).append('"')
            if (extW > 0) sb.append(" style=\"width:").append(extW / 9525).append("px\"")
            sb.append('>')
            extW = 0; extH = 0
        }

        while (true) {
            val t = x.next()
            if (t == EOF) break
            if (t == TEXT) {
                if (inT && skip == 0) sb.append(esc(x.text))
                continue
            }
            val n = x.name
            if (t == START) {
                when (n) {
                    "mc:Fallback" -> skip++
                    "w:p" -> {
                        val p = Para(pIdx++)
                        if (skip == 0) {
                            if (paras.isNotEmpty()) openPara(false)
                            paras.add(p)
                        }
                    }
                    "w:t" -> {
                        val id = tIdx++
                        if (skip == 0 && paras.isNotEmpty()) {
                            openPara(false)
                            inT = true
                            sb.append("<span data-r=\"0:").append(id).append('"')
                            if (ffResult) sb.append(" class=\"ff\"")
                            val css = runCss()
                            if (css.isNotEmpty()) sb.append(" style=\"").append(css).append('"')
                            sb.append('>')
                        }
                    }
                    else -> if (skip == 0) when (n) {
                        "w:pPr" -> if (paras.isNotEmpty() && !inRun) inPPr = true
                        "w:pStyle" -> if (inPPr) paras.last().style = x.attr("w:val")
                        "w:jc" -> if (inPPr) paras.last().jc = x.attr("w:val")
                        "w:numPr" -> if (inPPr) inNumPr = true
                        "w:ilvl" -> if (inNumPr) paras.last().ilvl = x.attr("w:val")?.toIntOrNull()?.coerceIn(0, 8) ?: 0
                        "w:numId" -> if (inNumPr) paras.last().numId = x.attr("w:val")
                        "w:r" -> { inRun = true; b = false; i = false; u = false; st = false; color = null; size = null; hl = null; va = null }
                        "w:rPr" -> if (inRun) inRPr = true
                        "w:b" -> if (inRPr) b = on(x)
                        "w:i" -> if (inRPr) i = on(x)
                        "w:u" -> if (inRPr) u = on(x)
                        "w:strike" -> if (inRPr) st = on(x)
                        "w:color" -> if (inRPr) color = x.attr("w:val")?.takeIf { it.length == 6 && it != "auto" && it.all { c -> c.isLetterOrDigit() } }
                        "w:sz" -> if (inRPr) size = x.attr("w:val")?.toFloatOrNull()?.let { (it / 2f).toString().removeSuffix(".0") }
                        "w:highlight" -> if (inRPr) hl = highlight[x.attr("w:val") ?: ""]
                        "w:vertAlign" -> if (inRPr) va = x.attr("w:val")
                        "w:tab" -> if (inRun && !inRPr && paras.isNotEmpty()) { openPara(false); sb.append("<span class=\"x\" contenteditable=\"false\">&emsp;</span>") }
                        "w:br", "w:cr" -> if (inRun && paras.isNotEmpty()) {
                            openPara(false)
                            sb.append(if (x.attr("w:type") == "page") "<span class=\"x pb\" contenteditable=\"false\"></span>" else "<br>")
                        }
                        "w:tbl" -> if (paras.isEmpty()) { flushTd(); sb.append("<table>") }
                        "w:tr" -> if (paras.isEmpty()) sb.append("<tr>")
                        "w:tc" -> if (paras.isEmpty()) pendingTd.add(1)
                        "w:gridSpan" -> if (paras.isEmpty() && pendingTd.isNotEmpty() && pendingTd.last() >= 0)
                            pendingTd[pendingTd.size - 1] = x.attr("w:val")?.toIntOrNull()?.coerceIn(1, 64) ?: 1
                        "wp:extent" -> { extW = x.attr("cx")?.toIntOrNull() ?: 0; extH = x.attr("cy")?.toIntOrNull() ?: 0 }
                        "a:blip" -> img(x.attr("r:embed"))
                        "v:imagedata" -> img(x.attr("r:id"))
                        "w:fldChar" -> when (x.attr("w:fldCharType")) {
                            "begin" -> { fldBegin = true; ffText = false }
                            "separate" -> { fldBegin = false; if (ffText) ffResult = true }
                            "end" -> { fldBegin = false; ffResult = false; ffText = false }
                        }
                        "w:textInput" -> if (fldBegin) ffText = true
                        "w:sdt" -> sdtPh = false
                        "w:showingPlcHdr" -> sdtPh = true
                        "w:sdtContent" -> {
                            val inline = paras.isNotEmpty()
                            sdtInline.add(inline)
                            if (inline) {
                                openPara(false)
                                sb.append(if (sdtPh) "<span class=\"sdt ph\">" else "<span class=\"sdt\">")
                            } else sdtBlock++
                        }
                    }
                }
            } else if (t == END) {
                when (n) {
                    "mc:Fallback" -> if (skip > 0) skip--
                    "w:t" -> if (inT) { inT = false; sb.append("</span>") }
                    else -> if (skip == 0) when (n) {
                        "w:p" -> if (paras.isNotEmpty()) {
                            val p = paras.last()
                            if (!p.opened) { openPara(true); sb.append("<br>") }
                            sb.append(p.closeTag)
                            paras.removeAt(paras.size - 1)
                            inPPr = false; inNumPr = false
                        }
                        "w:pPr" -> { inPPr = false; inNumPr = false }
                        "w:numPr" -> inNumPr = false
                        "w:r" -> { inRun = false; inRPr = false }
                        "w:rPr" -> inRPr = false
                        "w:tbl" -> if (paras.isEmpty()) sb.append("</table>")
                        "w:tr" -> if (paras.isEmpty()) sb.append("</tr>")
                        "w:tc" -> if (paras.isEmpty() && pendingTd.isNotEmpty()) {
                            flushTd()
                            pendingTd.removeAt(pendingTd.size - 1)
                            sb.append("</td>")
                        }
                        "w:sdtContent" -> if (sdtInline.isNotEmpty()) {
                            if (sdtInline.removeAt(sdtInline.size - 1)) sb.append("</span>") else if (sdtBlock > 0) sdtBlock--
                        }
                    }
                }
            }
        }
        return Converted(sb.toString(), listOf(PART))
    }
}

/** PPTX -> HTML: one card per slide, text addressable as `data-r="slideIndex:N"`. */
object Pptx {
    fun slidePaths(z: ZipDoc): List<String> {
        val pres = z.text("ppt/presentation.xml")
        val rels = z.rels("ppt/presentation.xml")
        val out = ArrayList<String>()
        if (pres != null) {
            val x = XmlScanner(pres)
            while (true) {
                val t = x.next()
                if (t == EOF) break
                if (t == START && x.name == "p:sldId") rels[x.attr("r:id") ?: ""]?.let { if (z.has(it)) out.add(it) }
            }
        }
        if (out.isEmpty()) {
            val re = Regex("ppt/slides/slide(\\d+)\\.xml")
            out.addAll(z.names().filter { re.matches(it) }.sortedBy { re.find(it)!!.groupValues[1].toInt() })
        }
        return out
    }

    fun toHtml(z: ZipDoc): Converted {
        val slides = slidePaths(z)
        val sb = StringBuilder()
        for ((si, path) in slides.withIndex()) {
            val xml = z.text(path) ?: continue
            val rels = z.rels(path)
            sb.append("<section class=\"slide\"><div class=\"x sn\" contenteditable=\"false\">").append(si + 1).append("</div>")
            val x = XmlScanner(xml)
            var tIdx = 0
            var pIdx = 0
            var title = false
            var inTx = false
            var pOpen = false
            var pIndex = -1
            var pLvl = 0
            var pHasText = false
            var inRPr = false
            var inT = false
            var css = ""
            var skip = 0
            fun openP() {
                if (pOpen) return
                pOpen = true
                sb.append(if (title) "<h2" else "<p")
                if (pLvl > 0 && !title) sb.append(" style=\"margin-left:").append(pLvl * 1.5).append("em\"")
            }
            while (true) {
                val t = x.next()
                if (t == EOF) break
                if (t == TEXT) {
                    if (inT && skip == 0) sb.append(esc(x.text))
                    continue
                }
                val n = x.name
                if (t == START) when (n) {
                    "mc:Fallback" -> skip++
                    "a:p" -> { pIndex = pIdx++; pOpen = false; pHasText = false; pLvl = 0 }
                    "a:t" -> {
                        val id = tIdx++
                        if (skip == 0 && pIndex >= 0) {
                            if (!pOpen) { openP(); sb.append('>') }
                            pHasText = true
                            inT = true
                            sb.append("<span data-r=\"").append(si).append(':').append(id).append('"')
                            if (css.isNotEmpty()) sb.append(" style=\"").append(css).append('"')
                            sb.append('>')
                        }
                    }
                    else -> if (skip == 0) when (n) {
                        "p:sp", "p:pic", "p:graphicFrame" -> title = false
                        "p:ph" -> { val ty = x.attr("type"); title = ty == "title" || ty == "ctrTitle" }
                        "p:txBody", "a:txBody" -> inTx = true
                        "a:pPr" -> pLvl = x.attr("lvl")?.toIntOrNull()?.coerceIn(0, 8) ?: 0
                        "a:r", "a:fld" -> css = ""
                        "a:rPr" -> {
                            inRPr = true
                            val c = StringBuilder()
                            if (x.attr("b") == "1") c.append("font-weight:bold;")
                            if (x.attr("i") == "1") c.append("font-style:italic;")
                            val ul = x.attr("u")
                            if (ul != null && ul != "none") c.append("text-decoration:underline;")
                            css = c.toString()
                        }
                        "a:srgbClr" -> if (inRPr) x.attr("val")?.takeIf { it.length == 6 && it.all { c -> c.isLetterOrDigit() } }?.let { css += "color:#$it;" }
                        "a:br" -> if (pIndex >= 0) { if (!pOpen) { openP(); sb.append('>') }; sb.append("<br>") }
                        "a:blip" -> rels[x.attr("r:embed") ?: ""]?.let {
                            if (pIndex < 0) sb.append("<img src=\"").append(RES_BASE).append(esc(it)).append("\">")
                        }
                        "a:tbl" -> sb.append("<table>")
                        "a:tr" -> sb.append("<tr>")
                        "a:tc" -> {
                            val span = x.attr("gridSpan")?.toIntOrNull() ?: 1
                            sb.append(if (span > 1) "<td colspan=\"$span\">" else "<td>")
                        }
                    }
                } else when (n) {
                    "mc:Fallback" -> if (skip > 0) skip--
                    "a:t" -> if (inT) { inT = false; sb.append("</span>") }
                    else -> if (skip == 0) when (n) {
                        "a:p" -> if (pIndex >= 0) {
                            if (!pOpen) {
                                openP()
                                sb.append(" data-e=\"").append(si).append(':').append(pIndex).append("\"><br>")
                            } else if (!pHasText) sb.append("")
                            sb.append(if (title) "</h2>" else "</p>")
                            pIndex = -1; pOpen = false
                        }
                        "a:rPr" -> inRPr = false
                        "p:txBody", "a:txBody" -> inTx = false
                        "a:tbl" -> sb.append("</table>")
                        "a:tr" -> sb.append("</tr>")
                        "a:tc" -> sb.append("</td>")
                    }
                }
            }
            sb.append("</section>")
        }
        if (slides.isEmpty()) sb.append("<p>No slides found.</p>")
        return Converted(sb.toString(), slides)
    }
}
