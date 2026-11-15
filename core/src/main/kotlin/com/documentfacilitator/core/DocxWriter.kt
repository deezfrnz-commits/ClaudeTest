package com.documentfacilitator.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Builds a new DOCX from the editor's block model (JSON produced by the in-app rich editor):
 * `[{t:"p"|"h1".."h6"|"li"|"table"|"pb", align, ol, lvl, runs:[{s,b,i,u,k,c,z,h,img}], rows:[[ [blocks] ]]}]`.
 * Used whenever an edit changes structure, or the source format has no lossless write-back.
 */
object DocxWriter {
    class Image(val bytes: ByteArray, val ext: String, val widthPx: Int, val heightPx: Int)

    fun write(out: File, blocks: JSONArray, images: Map<String, Image>) {
        val body = StringBuilder()
        val rels = StringBuilder()
        val media = LinkedHashMap<String, ByteArray>()
        val exts = HashSet<String>()
        var relId = 10
        var drawingId = 1
        var olNum = 2 // numId 1 = bullets; each ordered list gets its own numId
        val olIds = HashMap<Int, Int>()

        fun run(r: JSONObject): String {
            val sb = StringBuilder()
            val imgKey = r.optString("img", "")
            if (imgKey.isNotEmpty()) {
                val im = images[imgKey] ?: return ""
                val id = "rId${relId++}"
                val name = "image${media.size + 1}.${im.ext}"
                media["word/media/$name"] = im.bytes
                exts.add(im.ext)
                rels.append("<Relationship Id=\"$id\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" Target=\"media/$name\"/>")
                var w = maxOf(1, r.optInt("w", im.widthPx))
                var h = maxOf(1, r.optInt("h", im.heightPx))
                if (w > 600) { h = (h.toLong() * 600 / w).toInt().coerceAtLeast(1); w = 600 }
                val cx = w.toLong() * 9525
                val cy = h.toLong() * 9525
                val did = drawingId++
                sb.append("<w:r><w:drawing><wp:inline distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\"><wp:extent cx=\"$cx\" cy=\"$cy\"/><wp:docPr id=\"$did\" name=\"Picture $did\"/>")
                sb.append("<a:graphic xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\"><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\"><pic:pic xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\"><pic:nvPicPr><pic:cNvPr id=\"$did\" name=\"$name\"/><pic:cNvPicPr/></pic:nvPicPr><pic:blipFill><a:blip r:embed=\"$id\"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill><pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"$cx\" cy=\"$cy\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></pic:spPr></pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r>")
                return sb.toString()
            }
            val text = r.optString("s", "")
            if (text.isEmpty()) return ""
            val pr = StringBuilder()
            if (r.optBoolean("b")) pr.append("<w:b/>")
            if (r.optBoolean("i")) pr.append("<w:i/>")
            if (r.optBoolean("k")) pr.append("<w:strike/>")
            val c = r.optString("c", "")
            if (c.length == 6 && c.all { it.isLetterOrDigit() }) pr.append("<w:color w:val=\"$c\"/>")
            val z = r.optDouble("z", 0.0)
            if (z > 0) pr.append("<w:sz w:val=\"${Math.round(z * 2)}\"/>")
            if (r.optBoolean("u")) pr.append("<w:u w:val=\"single\"/>")
            val h = r.optString("h", "")
            if (h.length == 6 && h.all { it.isLetterOrDigit() }) pr.append("<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"$h\"/>")
            sb.append("<w:r>")
            if (pr.isNotEmpty()) sb.append("<w:rPr>").append(pr).append("</w:rPr>")
            val lines = text.split('\n')
            for ((i, line) in lines.withIndex()) {
                if (i > 0) sb.append("<w:br/>")
                if (line.isNotEmpty()) sb.append("<w:t xml:space=\"preserve\">").append(xmlText(line)).append("</w:t>")
            }
            sb.append("</w:r>")
            return sb.toString()
        }

        fun block(b: JSONObject, sb: StringBuilder) {
            val t = b.optString("t", "p")
            if (t == "table") {
                val rows = b.optJSONArray("rows") ?: return
                var cols = 1
                for (i in 0 until rows.length()) cols = maxOf(cols, rows.getJSONArray(i).length())
                sb.append("<w:tbl><w:tblPr><w:tblW w:w=\"5000\" w:type=\"pct\"/><w:tblBorders>")
                for (side in arrayOf("top", "left", "bottom", "right", "insideH", "insideV")) sb.append("<w:$side w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"808080\"/>")
                sb.append("</w:tblBorders></w:tblPr><w:tblGrid>")
                for (i in 0 until cols) sb.append("<w:gridCol/>")
                sb.append("</w:tblGrid>")
                for (i in 0 until rows.length()) {
                    val row = rows.getJSONArray(i)
                    sb.append("<w:tr>")
                    for (j in 0 until cols) {
                        sb.append("<w:tc><w:tcPr><w:tcW w:w=\"0\" w:type=\"auto\"/></w:tcPr>")
                        val cell = if (j < row.length()) row.optJSONArray(j) else null
                        var wrote = false
                        if (cell != null) for (k in 0 until cell.length()) {
                            val cb = cell.getJSONObject(k)
                            if (cb.optString("t") == "table") continue
                            block(cb, sb); wrote = true
                        }
                        if (!wrote) sb.append("<w:p/>")
                        sb.append("</w:tc>")
                    }
                    sb.append("</w:tr>")
                }
                sb.append("</w:tbl>")
                return
            }
            sb.append("<w:p>")
            val pr = StringBuilder()
            if (t.length == 2 && t[0] == 'h' && t[1] in '1'..'6') pr.append("<w:pStyle w:val=\"Heading${t[1]}\"/>")
            if (t == "li") {
                val lvl = b.optInt("lvl", 0).coerceIn(0, 8)
                val num = if (b.optBoolean("ol")) olIds.getOrPut(b.optInt("list", 0)) { olNum++ } else 1
                pr.append("<w:numPr><w:ilvl w:val=\"$lvl\"/><w:numId w:val=\"$num\"/></w:numPr>")
            }
            when (b.optString("align", "")) {
                "center" -> pr.append("<w:jc w:val=\"center\"/>")
                "right" -> pr.append("<w:jc w:val=\"right\"/>")
                "justify" -> pr.append("<w:jc w:val=\"both\"/>")
            }
            if (pr.isNotEmpty()) sb.append("<w:pPr>").append(pr).append("</w:pPr>")
            if (t == "pb") sb.append("<w:r><w:br w:type=\"page\"/></w:r>")
            val runs = b.optJSONArray("runs")
            if (runs != null) for (i in 0 until runs.length()) sb.append(run(runs.getJSONObject(i)))
            sb.append("</w:p>")
        }

        for (i in 0 until blocks.length()) block(blocks.getJSONObject(i), body)

        val ns = "xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\""
        val head = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
        val doc = "$head<w:document $ns><w:body>$body<w:sectPr><w:pgSz w:w=\"12240\" w:h=\"15840\"/><w:pgMar w:top=\"1440\" w:right=\"1440\" w:bottom=\"1440\" w:left=\"1440\" w:header=\"720\" w:footer=\"720\" w:gutter=\"0\"/></w:sectPr></w:body></w:document>"

        val styles = StringBuilder("$head<w:styles xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii=\"Calibri\" w:hAnsi=\"Calibri\" w:cs=\"Calibri\"/><w:sz w:val=\"22\"/></w:rPr></w:rPrDefault><w:pPrDefault><w:pPr><w:spacing w:after=\"160\" w:line=\"259\" w:lineRule=\"auto\"/></w:pPr></w:pPrDefault></w:docDefaults><w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\"><w:name w:val=\"Normal\"/></w:style>")
        val sizes = intArrayOf(32, 26, 24, 22, 22, 22)
        for (l in 1..6) styles.append("<w:style w:type=\"paragraph\" w:styleId=\"Heading$l\"><w:name w:val=\"heading $l\"/><w:basedOn w:val=\"Normal\"/><w:next w:val=\"Normal\"/><w:pPr><w:keepNext/><w:spacing w:before=\"240\" w:after=\"80\"/><w:outlineLvl w:val=\"${l - 1}\"/></w:pPr><w:rPr><w:b/><w:sz w:val=\"${sizes[l - 1]}\"/></w:rPr></w:style>")
        styles.append("</w:styles>")

        val numbering = StringBuilder("$head<w:numbering xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">")
        for (a in 0..1) {
            numbering.append("<w:abstractNum w:abstractNumId=\"$a\"><w:multiLevelType w:val=\"hybridMultilevel\"/>")
            for (l in 0..8) {
                numbering.append("<w:lvl w:ilvl=\"$l\"><w:start w:val=\"1\"/>")
                if (a == 0) numbering.append("<w:numFmt w:val=\"bullet\"/><w:lvlText w:val=\"•\"/>")
                else numbering.append("<w:numFmt w:val=\"decimal\"/><w:lvlText w:val=\"%${l + 1}.\"/>")
                numbering.append("<w:lvlJc w:val=\"left\"/><w:pPr><w:ind w:left=\"${720 * (l + 1)}\" w:hanging=\"360\"/></w:pPr></w:lvl>")
            }
            numbering.append("</w:abstractNum>")
        }
        numbering.append("<w:num w:numId=\"1\"><w:abstractNumId w:val=\"0\"/></w:num>")
        for (n in 2 until maxOf(olNum, 3)) numbering.append("<w:num w:numId=\"$n\"><w:abstractNumId w:val=\"1\"/><w:lvlOverride w:ilvl=\"0\"><w:startOverride w:val=\"1\"/></w:lvlOverride></w:num>")
        numbering.append("</w:numbering>")

        val ct = StringBuilder("$head<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/>")
        for (e in exts) ct.append("<Default Extension=\"$e\" ContentType=\"image/${if (e == "jpg") "jpeg" else e}\"/>")
        ct.append("<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/><Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/><Override PartName=\"/word/numbering.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.numbering+xml\"/></Types>")

        val entries = LinkedHashMap<String, ByteArray>()
        fun put(k: String, v: String) { entries[k] = v.toByteArray(Charsets.UTF_8) }
        put("[Content_Types].xml", ct.toString())
        put("_rels/.rels", "$head<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/></Relationships>")
        put("word/document.xml", doc)
        put("word/styles.xml", styles.toString())
        put("word/numbering.xml", numbering.toString())
        put("word/_rels/document.xml.rels", "$head<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/><Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/numbering\" Target=\"numbering.xml\"/>$rels</Relationships>")
        entries.putAll(media)
        ZipRewrite.write(out, entries)
    }
}
