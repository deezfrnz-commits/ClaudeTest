package com.documentfacilitator.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

/** One overlay element on a page. Coordinates are page points, origin top-left, y down. */
class Mark(var kind: Int, var page: Int) {
    var x = 0f
    var y = 0f
    var w = 0f
    var h = 0f
    var color = 0xFF000000.toInt()
    /** stroke width, or font size for text */
    var size = 12f
    var text: String? = null
    var pts: FloatArray? = null
    /** id of an image stored next to the document state */
    var img: String? = null
    /** id of the detected/AcroForm field this mark fills, if any */
    var field: String? = null

    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("k", kind); o.put("p", page)
        o.put("x", x.toDouble()); o.put("y", y.toDouble()); o.put("w", w.toDouble()); o.put("h", h.toDouble())
        o.put("c", color); o.put("s", size.toDouble())
        text?.let { o.put("t", it) }
        img?.let { o.put("i", it) }
        field?.let { o.put("f", it) }
        pts?.let { p -> val a = JSONArray(); for (v in p) a.put(Math.round(v * 100) / 100.0); o.put("pts", a) }
        return o
    }

    fun copy(): Mark = fromJson(toJson())

    companion object {
        const val HIGHLIGHT = 1
        const val COVER = 2    // opaque rectangle (white-out / redaction box)
        const val BOX = 3      // outlined rectangle
        const val LINE = 4     // pts = x0,y0,x1,y1
        const val INK = 5      // pts = polyline
        const val TEXT = 6
        const val IMAGE = 7
        const val CHECK = 8

        const val ASCENT = 0.9f
        const val LEADING = 1.2f

        fun fromJson(o: JSONObject): Mark {
            val m = Mark(o.getInt("k"), o.getInt("p"))
            m.x = o.optDouble("x", 0.0).toFloat(); m.y = o.optDouble("y", 0.0).toFloat()
            m.w = o.optDouble("w", 0.0).toFloat(); m.h = o.optDouble("h", 0.0).toFloat()
            m.color = o.optInt("c", 0xFF000000.toInt()); m.size = o.optDouble("s", 12.0).toFloat()
            if (o.has("t")) m.text = o.getString("t")
            if (o.has("i")) m.img = o.getString("i")
            if (o.has("f")) m.field = o.getString("f")
            o.optJSONArray("pts")?.let { a -> m.pts = FloatArray(a.length()) { a.getDouble(it).toFloat() } }
            return m
        }

        fun listToJson(marks: List<Mark>): JSONArray { val a = JSONArray(); for (m in marks) a.put(m.toJson()); return a }
        fun listFromJson(a: JSONArray): ArrayList<Mark> { val l = ArrayList<Mark>(); for (i in 0 until a.length()) l.add(fromJson(a.getJSONObject(i))); return l }
    }
}

/** Helvetica metrics + WinAnsi encoding: the font used for text written into PDFs. */
object Helv {
    private val ascii = intArrayOf(
        278, 278, 355, 556, 556, 889, 667, 191, 333, 333, 389, 584, 278, 333, 278, 278,
        556, 556, 556, 556, 556, 556, 556, 556, 556, 556, 278, 278, 584, 584, 584, 556,
        1015, 667, 667, 722, 722, 667, 611, 778, 722, 278, 500, 667, 556, 833, 722, 778,
        667, 778, 722, 667, 611, 722, 667, 944, 667, 667, 611, 278, 278, 278, 469, 556,
        333, 556, 556, 500, 556, 556, 278, 556, 556, 222, 222, 500, 222, 833, 556, 556,
        556, 556, 333, 500, 278, 556, 500, 722, 500, 500, 500, 334, 260, 334, 584
    )
    private val cs = charset("windows-1252")

    private fun w(c: Char): Int {
        val k = c.code
        if (k in 32..126) return ascii[k - 32]
        return when (c) {
            in 'À'..'Å' -> 667; 'Æ' -> 1000; 'Ç' -> 722; in 'È'..'Ë' -> 667
            in 'Ì'..'Ï' -> 278; 'Ð', 'Ñ' -> 722; in 'Ò'..'Ö', 'Ø' -> 778
            in 'Ù'..'Ü' -> 722; 'Ý', 'Þ' -> 667; 'ß' -> 611
            'æ' -> 889; 'ç' -> 500; in 'ì'..'ï' -> 278; 'ø' -> 611; 'ý', 'ÿ' -> 500
            '•' -> 350; '—' -> 1000; '…' -> 1000; '‘', '’' -> 222; '“', '”' -> 333
            ' ' -> 278; '€' -> 556
            else -> 556
        }
    }

    fun width(s: String, size: Float): Float { var t = 0; for (c in s) t += w(c); return t * size / 1000f }

    fun canEncode(s: String): Boolean {
        val enc = cs.newEncoder()
        for (c in s) if (c != '\n' && c != '\r' && (c.code < 32 || !enc.canEncode(c))) return false
        return true
    }

    fun encode(s: String): ByteArray {
        val enc = cs.newEncoder()
        val sb = StringBuilder(s.length)
        for (c in s) sb.append(if (c.code >= 32 && enc.canEncode(c)) c else '?')
        return sb.toString().toByteArray(cs)
    }

    /** Breaks [text] into lines; wraps at [maxWidth] points when it is positive. */
    fun wrap(text: String, size: Float, maxWidth: Float): List<String> {
        val out = ArrayList<String>()
        for (para in text.replace("\r\n", "\n").replace('\r', '\n').split('\n')) {
            if (maxWidth <= 0 || width(para, size) <= maxWidth) { out.add(para); continue }
            var line = StringBuilder()
            for (word in para.split(' ')) {
                val cand = if (line.isEmpty()) word else "$line $word"
                if (width(cand, size) <= maxWidth) { line = StringBuilder(cand); continue }
                if (line.isNotEmpty()) { out.add(line.toString()); line = StringBuilder() }
                var rest = word
                while (width(rest, size) > maxWidth && rest.length > 1) { // a single word longer than the line
                    var k = rest.length - 1
                    while (k > 1 && width(rest.substring(0, k), size) > maxWidth) k--
                    out.add(rest.substring(0, k)); rest = rest.substring(k)
                }
                line.append(rest)
            }
            out.add(line.toString())
        }
        return out
    }
}

class PdfImage(val w: Int, val h: Int, val rgb: ByteArray?, val alpha: ByteArray?, val jpeg: ByteArray?)

/** An interactive form field (AcroForm widget) found in a PDF. Geometry is in view space points. */
class FormField(val id: String, val page: Int, val type: Int, val name: String) {
    var x = 0f; var y = 0f; var w = 0f; var h = 0f
    var value = ""
    var options: List<String> = emptyList()
    var multiline = false
    var readOnly = false

    companion object {
        const val TEXT = 0
        const val CHECK = 1
        const val RADIO = 2
        const val CHOICE = 3
        const val SIGN = 4
    }
}

object PdfEdit {
    private fun num(pdf: PdfFile, o: Any?): Float = when (val v = pdf.resolve(o)) { is Long -> v.toFloat(); is Double -> v.toFloat(); else -> 0f }

    private class Widget(val ref: PdfRef, val dict: PdfDict, val owner: PdfRef, val ownerDict: PdfDict, val ft: String, val ff: Int, val name: String)

    private fun widget(pdf: PdfFile, ref: PdfRef): Widget? {
        val d = pdf.resolve(ref) as? PdfDict ?: return null
        if ((d["Subtype"] as? PdfName)?.v != "Widget") return null
        var ft: String? = null
        var ff: Int? = null
        val names = ArrayList<String>()
        var owner = ref
        var ownerDict = d
        val pr = d["Parent"] as? PdfRef
        if (!d.containsKey("T") && pr != null) (pdf.resolve(pr) as? PdfDict)?.let { owner = pr; ownerDict = it }
        var cur: PdfDict? = d
        var guard = 0
        while (cur != null && guard++ < 32) {
            if (ft == null) ft = (pdf.resolve(cur["FT"]) as? PdfName)?.v
            if (ff == null) ff = (pdf.resolve(cur["Ff"]) as? Long)?.toInt()
            (pdf.resolve(cur["T"]) as? PdfStr)?.let { names.add(0, it.text()) }
            cur = pdf.resolve(cur["Parent"]) as? PdfDict
        }
        return Widget(ref, d, owner, ownerDict, ft ?: return null, ff ?: 0, names.joinToString("."))
    }

    private fun onState(pdf: PdfFile, d: PdfDict): String {
        val n = pdf.dict((pdf.dict(d["AP"]))?.get("N"))
        if (n != null) for (k in n.keys) if (k != "Off") return k
        return "Yes"
    }

    private fun inherited(pdf: PdfFile, d: PdfDict, key: String): Any? {
        var cur: PdfDict? = d
        var guard = 0
        while (cur != null && guard++ < 32) {
            cur[key]?.let { return pdf.resolve(it) }
            cur = pdf.resolve(cur["Parent"]) as? PdfDict
        }
        return null
    }

    /** Lists fillable AcroForm fields. Ids are "a<objectNumber>" of the widget. */
    fun fields(pdf: PdfFile): List<FormField> {
        val out = ArrayList<FormField>()
        if (pdf.encrypted) return out
        for ((pi, page) in pdf.pages.withIndex()) {
            val annots = pdf.resolve(page.dict["Annots"]) as? List<*> ?: continue
            for (a in annots) {
                val ref = a as? PdfRef ?: continue
                val w = try { widget(pdf, ref) } catch (_: Exception) { null } ?: continue
                val flags = (pdf.resolve(w.dict["F"]) as? Long)?.toInt() ?: 0
                if (flags and 2 != 0) continue
                val rect = pdf.resolve(w.dict["Rect"]) as? List<*> ?: continue
                if (rect.size < 4) continue
                val a0 = page.pdfToView(num(pdf, rect[0]), num(pdf, rect[1]))
                val a1 = page.pdfToView(num(pdf, rect[2]), num(pdf, rect[3]))
                val type = when (w.ft) {
                    "Tx" -> FormField.TEXT
                    "Btn" -> if (w.ff and 0x10000 != 0) continue else if (w.ff and 0x8000 != 0) FormField.RADIO else FormField.CHECK
                    "Ch" -> FormField.CHOICE
                    "Sig" -> FormField.SIGN
                    else -> continue
                }
                val f = FormField("a${ref.num}", pi, type, w.name)
                f.x = minOf(a0[0], a1[0]); f.y = minOf(a0[1], a1[1])
                f.w = Math.abs(a1[0] - a0[0]); f.h = Math.abs(a1[1] - a0[1])
                if (f.w < 1 || f.h < 1) continue
                f.readOnly = w.ff and 1 != 0
                f.multiline = type == FormField.TEXT && w.ff and 0x1000 != 0
                val v = inherited(pdf, w.dict, "V")
                when (type) {
                    FormField.CHECK, FormField.RADIO -> {
                        val st = (pdf.resolve(w.dict["AS"]) as? PdfName)?.v ?: (v as? PdfName)?.v
                        f.value = if (st != null && st != "Off" && st == onState(pdf, w.dict)) "1" else ""
                    }
                    else -> f.value = (v as? PdfStr)?.text() ?: ""
                }
                if (type == FormField.CHOICE) {
                    val opt = inherited(pdf, w.dict, "Opt") as? List<*>
                    if (opt != null) f.options = opt.mapNotNull { o ->
                        when (val r = pdf.resolve(o)) {
                            is PdfStr -> r.text()
                            is List<*> -> (pdf.resolve(r.lastOrNull()) as? PdfStr)?.text()
                            else -> null
                        }
                    }
                }
                out.add(f)
            }
        }
        return out
    }

    private fun rgb(c: Int) = "${PdfUpdate.fmt(((c shr 16) and 0xff) / 255.0)} ${PdfUpdate.fmt(((c shr 8) and 0xff) / 255.0)} ${PdfUpdate.fmt((c and 0xff) / 255.0)}"
    private fun f(v: Float) = PdfUpdate.fmt(v.toDouble())

    private fun textOp(bo: ByteArrayOutputStream, bytes: ByteArray) {
        PdfUpdate.ser(PdfStr(bytes), bo)
        bo.write(" Tj\n".toByteArray())
    }

    private fun imageObject(up: PdfUpdate, im: PdfImage): PdfRef {
        val d = PdfDict()
        d["Type"] = PdfName("XObject"); d["Subtype"] = PdfName("Image")
        d["Width"] = im.w.toLong(); d["Height"] = im.h.toLong()
        d["ColorSpace"] = PdfName("DeviceRGB"); d["BitsPerComponent"] = 8L
        if (im.alpha != null) {
            val m = PdfDict()
            m["Type"] = PdfName("XObject"); m["Subtype"] = PdfName("Image")
            m["Width"] = im.w.toLong(); m["Height"] = im.h.toLong()
            m["ColorSpace"] = PdfName("DeviceGray"); m["BitsPerComponent"] = 8L
            d["SMask"] = up.addStream(m, im.alpha)
        }
        return if (im.jpeg != null) { d["Filter"] = PdfName("DCTDecode"); up.addStream(d, im.jpeg, false) }
        else up.addStream(d, im.rgb ?: ByteArray(im.w * im.h * 3))
    }

    private fun unique(d: Map<String, Any?>, base: String): String { var n = base; var i = 0; while (d.containsKey(n)) n = base + (i++); return n }

    /**
     * Writes [marks] and AcroForm [fills] (field id -> value) into a copy of the PDF as an
     * incremental update: the original bytes, text and vector content stay untouched.
     */
    fun apply(pdf: PdfFile, out: File, marks: List<Mark>, images: (String) -> PdfImage?, fills: Map<String, String>) {
        val up = PdfUpdate(pdf)
        var font: PdfRef? = null
        fun helv(): PdfRef = font ?: up.add(PdfDict().also {
            it["Type"] = PdfName("Font"); it["Subtype"] = PdfName("Type1"); it["BaseFont"] = PdfName("Helvetica"); it["Encoding"] = PdfName("WinAnsiEncoding")
        }).also { font = it }
        var gs: PdfRef? = null
        val imgRefs = HashMap<String, PdfRef?>()
        var qRef: PdfRef? = null

        for ((pi, list) in marks.groupBy { it.page }.toSortedMap()) {
            val page = pdf.pages.getOrNull(pi) ?: continue
            val pd = up.edit(page.ref) ?: continue
            val res = PdfDict(pdf.resolve(pd["Resources"] ?: page.resources) as? PdfDict ?: PdfDict())
            fun sub(key: String): PdfDict { val d = PdfDict(pdf.resolve(res[key]) as? PdfDict ?: PdfDict()); res[key] = d; return d }
            var fontName: String? = null
            var gsName: String? = null
            val imgNames = HashMap<String, String>()
            val bo = ByteArrayOutputStream()
            fun w(s: String) = bo.write(s.toByteArray(Charsets.ISO_8859_1))
            val m = page.viewToPdf()
            w("Q\nq\n${f(m[0])} ${f(m[1])} ${f(m[2])} ${f(m[3])} ${f(m[4])} ${f(m[5])} cm\n")
            for (mk in list) {
                when (mk.kind) {
                    Mark.HIGHLIGHT -> {
                        if (gsName == null) {
                            val g = gs ?: up.add(PdfDict().also { it["Type"] = PdfName("ExtGState"); it["ca"] = 0.4; it["BM"] = PdfName("Multiply") }).also { gs = it }
                            val d = sub("ExtGState"); gsName = unique(d, "DFg"); d[gsName] = g
                        }
                        w("q /$gsName gs ${rgb(mk.color)} rg ${f(mk.x)} ${f(mk.y)} ${f(mk.w)} ${f(mk.h)} re f Q\n")
                    }
                    Mark.COVER -> w("${rgb(mk.color)} rg ${f(mk.x)} ${f(mk.y)} ${f(mk.w)} ${f(mk.h)} re f\n")
                    Mark.BOX -> w("${rgb(mk.color)} RG ${f(mk.size)} w ${f(mk.x)} ${f(mk.y)} ${f(mk.w)} ${f(mk.h)} re S\n")
                    Mark.LINE, Mark.INK -> {
                        val p = mk.pts ?: continue
                        if (p.size < 4) continue
                        w("q 1 J 1 j ${rgb(mk.color)} RG ${f(mk.size)} w ${f(p[0])} ${f(p[1])} m\n")
                        var i = 2
                        while (i + 1 < p.size) { w("${f(p[i])} ${f(p[i + 1])} l\n"); i += 2 }
                        w("S Q\n")
                    }
                    Mark.CHECK -> {
                        val lw = maxOf(1f, minOf(mk.w, mk.h) * 0.12f)
                        w("q 1 J 1 j ${rgb(mk.color)} RG ${f(lw)} w ${f(mk.x + mk.w * 0.2f)} ${f(mk.y + mk.h * 0.55f)} m ${f(mk.x + mk.w * 0.42f)} ${f(mk.y + mk.h * 0.78f)} l ${f(mk.x + mk.w * 0.82f)} ${f(mk.y + mk.h * 0.24f)} l S Q\n")
                    }
                    Mark.TEXT -> {
                        val t = mk.text ?: continue
                        if (fontName == null) { val d = sub("Font"); fontName = unique(d, "DFh"); d[fontName] = helv() }
                        val lines = Helv.wrap(t, mk.size, mk.w)
                        w("BT /$fontName ${f(mk.size)} Tf ${rgb(mk.color)} rg\n")
                        for ((i, line) in lines.withIndex()) {
                            if (line.isEmpty()) continue
                            w("1 0 0 -1 ${f(mk.x)} ${f(mk.y + mk.size * Mark.ASCENT + i * mk.size * Mark.LEADING)} Tm ")
                            textOp(bo, Helv.encode(line))
                        }
                        w("ET\n")
                    }
                    Mark.IMAGE -> {
                        val id = mk.img ?: continue
                        val ref = imgRefs.getOrPut(id) { images(id)?.let { imageObject(up, it) } } ?: continue
                        val name = imgNames.getOrPut(id) { val d = sub("XObject"); val n = unique(d, "DFi"); d[n] = ref; n }
                        w("q ${f(mk.w)} 0 0 ${f(-mk.h)} ${f(mk.x)} ${f(mk.y + mk.h)} cm /$name Do Q\n")
                    }
                }
            }
            w("Q\n")
            val content = up.addStream(PdfDict(), bo.toByteArray())
            val old = pd["Contents"]
            val arr = ArrayList<Any?>()
            val q = qRef ?: up.addStream(PdfDict(), "q\n".toByteArray(), false).also { qRef = it }
            arr.add(q)
            when (val r = if (old is PdfRef && pdf.resolve(old) is List<*>) pdf.resolve(old) else old) {
                is List<*> -> arr.addAll(r)
                null -> {}
                else -> arr.add(r)
            }
            arr.add(content)
            pd["Contents"] = arr
            pd["Resources"] = res
        }

        if (fills.isNotEmpty()) fill(pdf, up, fills, ::helv)
        up.write(out)
    }

    private fun appearance(up: PdfUpdate, font: PdfRef, w: Float, h: Float, text: String, da: String?, multiline: Boolean, align: Int, bg: String?, bc: String?): PdfRef {
        var size = da?.let { Regex("([0-9.]+)\\s+Tf").find(it)?.groupValues?.get(1)?.toFloatOrNull() } ?: 0f
        val pad = 2f
        val bo = ByteArrayOutputStream()
        fun wr(s: String) = bo.write(s.toByteArray(Charsets.ISO_8859_1))
        if (bg != null) wr("$bg rg 0 0 ${f(w)} ${f(h)} re f\n")
        if (bc != null) wr("$bc RG 1 w 0.5 0.5 ${f(w - 1)} ${f(h - 1)} re S\n")
        wr("/Tx BMC\nq\n1 1 ${f(w - 2)} ${f(h - 2)} re W n\nBT\n")
        val lines: List<String>
        if (multiline) {
            if (size <= 0f) size = 10f
            lines = Helv.wrap(text, size, w - 2 * pad)
        } else {
            val one = text.replace('\n', ' ')
            if (size <= 0f) {
                size = (h - 4f).coerceIn(4f, 12f)
                while (size > 4f && Helv.width(one, size) > w - 2 * pad) size -= 0.5f
            }
            lines = listOf(one)
        }
        wr("/Helv ${f(size)} Tf 0 g\n")
        for ((i, line) in lines.withIndex()) {
            val lw = Helv.width(line, size)
            val x = when (align) { 1 -> (w - lw) / 2; 2 -> w - pad - lw; else -> pad }
            val y = if (multiline) h - pad - size * 0.9f - i * size * 1.15f else (h - size * 0.72f) / 2f
            wr("1 0 0 1 ${f(x)} ${f(y)} Tm ")
            textOp(bo, Helv.encode(line))
        }
        wr("ET\nQ\nEMC\n")
        val d = PdfDict()
        d["Type"] = PdfName("XObject"); d["Subtype"] = PdfName("Form")
        d["BBox"] = arrayListOf<Any?>(0L, 0L, w.toDouble(), h.toDouble())
        d["Resources"] = PdfDict().also { r -> r["Font"] = PdfDict().also { it["Helv"] = font } }
        return up.addStream(d, bo.toByteArray())
    }

    private fun fill(pdf: PdfFile, up: PdfUpdate, fills: Map<String, String>, helv: () -> PdfRef) {
        var needAppearances = false
        for ((id, value) in fills) {
            val n = id.removePrefix("a").toIntOrNull() ?: continue
            val ref = PdfRef(n, pdf.gen(n))
            val w = widget(pdf, ref) ?: continue
            when (w.ft) {
                "Btn" -> {
                    if (w.ff and 0x10000 != 0) continue
                    val on = onState(pdf, w.dict)
                    val radio = w.ff and 0x8000 != 0
                    if (radio) {
                        if (value.isEmpty()) {
                            up.edit(ref)?.put("AS", PdfName("Off"))
                            val od = up.edit(w.owner)
                            if (od != null && (od["V"] as? PdfName)?.v == on) od["V"] = PdfName("Off")
                            continue
                        }
                        up.edit(w.owner)?.put("V", PdfName(on))
                        val kids = pdf.resolve(w.ownerDict["Kids"]) as? List<*>
                        if (kids != null && w.owner != ref) for (k in kids) {
                            val kr = k as? PdfRef ?: continue
                            // an explicit fill for a sibling is handled by its own entry
                            if (kr != ref && fills["a${kr.num}"].isNullOrEmpty()) up.edit(kr)?.put("AS", PdfName("Off"))
                        }
                        up.edit(ref)?.put("AS", PdfName(on))
                    } else {
                        val st = PdfName(if (value.isEmpty()) "Off" else on)
                        up.edit(w.owner)?.put("V", st)
                        up.edit(ref)?.put("AS", st)
                    }
                }
                "Tx", "Ch" -> {
                    up.edit(w.owner)?.put("V", PdfStr.of(value))
                    val targets = ArrayList<PdfRef>()
                    val kids = pdf.resolve(w.ownerDict["Kids"]) as? List<*>
                    if (w.owner != ref && kids != null) kids.forEach { k -> (k as? PdfRef)?.let { targets.add(it) } } else targets.add(ref)
                    val encodable = Helv.canEncode(value)
                    if (!encodable) needAppearances = true
                    for (t in targets) {
                        val td = up.edit(t) ?: continue
                        if (!encodable) { td.remove("AP"); continue }
                        val rect = pdf.resolve(td["Rect"]) as? List<*> ?: continue
                        if (rect.size < 4) continue
                        val rw = Math.abs(num(pdf, rect[2]) - num(pdf, rect[0]))
                        val rh = Math.abs(num(pdf, rect[3]) - num(pdf, rect[1]))
                        val da = (inherited(pdf, td, "DA") as? PdfStr)?.text()
                        val q = (inherited(pdf, td, "Q") as? Long)?.toInt() ?: 0
                        val mk = pdf.resolve(td["MK"]) as? PdfDict
                        fun col(key: String): String? {
                            val a = pdf.resolve(mk?.get(key)) as? List<*> ?: return null
                            val v = a.map { num(pdf, it) }
                            return when (v.size) {
                                1 -> "${f(v[0])} ${f(v[0])} ${f(v[0])}"
                                3 -> "${f(v[0])} ${f(v[1])} ${f(v[2])}"
                                else -> null
                            }
                        }
                        val ap = appearance(up, helv(), rw, rh, value, da, w.ft == "Tx" && w.ff and 0x1000 != 0, q, col("BG"), col("BC"))
                        td["AP"] = PdfDict().also { it["N"] = ap }
                    }
                }
            }
        }
        // XFA would shadow the values we just wrote; drop it so every reader uses the AcroForm data.
        val rootRef = pdf.trailer["Root"] as? PdfRef ?: return
        val root = pdf.resolve(rootRef) as? PdfDict ?: return
        val af = root["AcroForm"]
        val afDict = pdf.resolve(af) as? PdfDict ?: return
        if (afDict.containsKey("XFA") || needAppearances) {
            val target = if (af is PdfRef) up.edit(af) else PdfDict(afDict).also { up.edit(rootRef)?.put("AcroForm", it) }
            target?.remove("XFA")
            if (needAppearances) target?.put("NeedAppearances", true)
        }
    }

    /** Adds [delta] degrees (multiple of 90) to the given pages. */
    fun rotate(pdf: PdfFile, out: File, pageIdx: Collection<Int>, delta: Int) {
        val up = PdfUpdate(pdf)
        for (i in pageIdx) {
            val p = pdf.pages.getOrNull(i) ?: continue
            up.edit(p.ref)?.put("Rotate", ((((p.rotate + delta) % 360) + 360) % 360).toLong())
        }
        up.write(out)
    }

    /** Removes pages from the page tree (their objects stay in the file, unreferenced). */
    fun delete(pdf: PdfFile, out: File, pageIdx: Collection<Int>) {
        val set = pageIdx.filter { it in pdf.pages.indices }.toSet()
        if (set.isEmpty() || set.size >= pdf.pages.size) throw PdfException("cannot delete every page")
        val up = PdfUpdate(pdf)
        for (i in set) {
            val p = pdf.pages[i]
            var parentRef = p.dict["Parent"] as? PdfRef ?: continue
            val parent = up.edit(parentRef) ?: continue
            val kids = ArrayList(pdf.resolve(parent["Kids"]) as? List<*> ?: continue)
            kids.remove(p.ref)
            parent["Kids"] = kids
            var cur: PdfDict? = parent
            var guard = 0
            while (cur != null && guard++ < 64) {
                val c = (pdf.resolve(cur["Count"]) as? Long) ?: 1L
                cur["Count"] = maxOf(0L, c - 1)
                parentRef = cur["Parent"] as? PdfRef ?: break
                cur = up.edit(parentRef)
            }
        }
        up.write(out)
    }
}
