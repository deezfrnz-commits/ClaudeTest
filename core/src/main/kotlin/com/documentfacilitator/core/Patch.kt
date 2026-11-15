package com.documentfacilitator.core

import java.io.File

/**
 * Lossless text edits for WordprocessingML ("w") and DrawingML ("a") parts: only the text of the
 * addressed runs changes, every other byte of the part is preserved.
 */
object OoxmlPatch {
    class PartEdits {
        /** index of the `t` element in the part -> new text */
        val runs = HashMap<Int, String>()
        /** index of an (originally empty) paragraph in the part -> text to put into it */
        val paras = HashMap<Int, String>()
        fun isEmpty() = runs.isEmpty() && paras.isEmpty()
    }

    fun patchPart(xml: String, prefix: String, edits: PartEdits): String {
        if (edits.isEmpty()) return xml
        val p = Regex.escape(prefix)
        val re = Regex(
            "<$p:t(\\s[^>]*?)?(?:/>|>([\\s\\S]*?)</$p:t>)" +   // 1,2: text run
                "|<$p:p(\\s[^>]*?)?(/?)>" +                    // 3,4: paragraph start
                "|</$p:p>" +
                "|<$p:endParaRPr\\b[^>]*?(/?)>"                 // 5
        )
        val out = StringBuilder(xml.length + 512)
        var last = 0
        var tIdx = 0
        var pIdx = 0
        var pending: String? = null
        var paraStart = 0
        fun run(text: String, from: Int, to: Int): String {
            // reuse the paragraph mark's run properties so typed text matches the surrounding style
            var rpr = ""
            if (prefix == "w") {
                val seg = xml.substring(from, to)
                val a = seg.indexOf("<w:rPr>")
                val b = seg.indexOf("</w:rPr>")
                if (a >= 0 && b > a) rpr = seg.substring(a, b + 8)
            }
            return "<$prefix:r>$rpr<$prefix:t${if (prefix == "w") " xml:space=\"preserve\"" else ""}>${xmlText(text)}</$prefix:t></$prefix:r>"
        }
        for (m in re.findAll(xml)) {
            val v = m.value
            when {
                v.startsWith("</") -> {
                    val txt = pending ?: continue
                    pending = null
                    out.append(xml, last, m.range.first)
                    last = m.range.first
                    out.append(run(txt, paraStart, m.range.first))
                }
                v.startsWith("<$prefix:t") -> {
                    val idx = tIdx++
                    val rep = edits.runs[idx] ?: continue
                    out.append(xml, last, m.range.first)
                    last = m.range.last + 1
                    out.append("<$prefix:t").append(if (prefix == "w") " xml:space=\"preserve\"" else "").append('>').append(xmlText(rep)).append("</$prefix:t>")
                }
                v.startsWith("<$prefix:endParaRPr") -> {
                    val txt = pending ?: continue
                    pending = null
                    out.append(xml, last, m.range.first)
                    last = m.range.first
                    val attrs = if (m.groupValues[5] == "/") v.substring(prefix.length + 12, v.length - 2) else ""
                    out.append("<a:r>").append(if (attrs.isNotBlank()) "<a:rPr$attrs/>" else "").append("<a:t>").append(xmlText(txt)).append("</a:t></a:r>")
                }
                else -> { // paragraph start
                    val idx = pIdx++
                    val txt = edits.paras[idx] ?: continue
                    if (txt.isEmpty()) continue
                    if (m.groupValues[4] == "/") {
                        out.append(xml, last, m.range.first)
                        last = m.range.last + 1
                        out.append(v, 0, v.length - 2).append('>').append(run(txt, 0, 0)).append("</$prefix:p>")
                    } else {
                        pending = txt
                        paraStart = m.range.first
                    }
                }
            }
        }
        out.append(xml, last, xml.length)
        return out.toString()
    }

    /** [edits]: part path -> edits. [prefix] is "w" for DOCX and "a" for PPTX. */
    fun patch(src: File, out: File, prefix: String, edits: Map<String, PartEdits>) {
        val rep = HashMap<String, ByteArray?>()
        ZipDoc(src).use { z ->
            for ((path, e) in edits) {
                val xml = z.text(path) ?: continue
                rep[path] = patchPart(xml, prefix, e).toByteArray(Charsets.UTF_8)
            }
        }
        ZipRewrite.copy(src, out, rep)
    }
}
