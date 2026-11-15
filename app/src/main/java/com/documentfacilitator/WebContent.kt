package com.documentfacilitator

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.print.PrintManager
import android.util.Base64
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.documentfacilitator.core.Converted
import com.documentfacilitator.core.Csv
import com.documentfacilitator.core.DocType
import com.documentfacilitator.core.Docx
import com.documentfacilitator.core.DocxWriter
import com.documentfacilitator.core.Epub
import com.documentfacilitator.core.Grid
import com.documentfacilitator.core.GridWriter
import com.documentfacilitator.core.Html
import com.documentfacilitator.core.Kind
import com.documentfacilitator.core.Legacy
import com.documentfacilitator.core.Odf
import com.documentfacilitator.core.OoxmlPatch
import com.documentfacilitator.core.PlainText
import com.documentfacilitator.core.Pptx
import com.documentfacilitator.core.RES_BASE
import com.documentfacilitator.core.Rtf
import com.documentfacilitator.core.Xlsx
import com.documentfacilitator.core.XlsxPatch
import com.documentfacilitator.core.ZipDoc
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/** Reflowable documents and spreadsheets, converted to HTML and shown in a locked-down, offline WebView. */
@SuppressLint("SetJavaScriptEnabled")
class WebContent(host: Host, doc: Loaded, private val prep: Prepared) : Content(host, doc) {
    class Prepared(val conv: Converted, val zip: ZipDoc?, val cls: String)

    companion object {
        private const val MAX_TEXT = 8 * 1024 * 1024

        /** Converts the document. Call off the main thread. */
        fun prepare(doc: Loaded): Prepared {
            val f = doc.file
            fun text(): String = PlainText.decode(f.inputStream().use { com.documentfacilitator.core.readAllCapped(it, MAX_TEXT) })
            return when (doc.kind) {
                Kind.DOCX -> ZipDoc(f).let { Prepared(Docx.toHtml(it), it, "doc") }
                Kind.PPTX -> ZipDoc(f).let { Prepared(Pptx.toHtml(it), it, "slides") }
                Kind.XLSX -> ZipDoc(f).let { Prepared(Xlsx.toHtml(it), it, "grid") }
                Kind.ODT -> ZipDoc(f).let { Prepared(Odf.toHtml(it), it, "doc") }
                Kind.ODP -> ZipDoc(f).let { Prepared(Odf.toHtml(it), it, "slides") }
                Kind.ODS -> ZipDoc(f).let { Prepared(Converted(Grid.toHtml(Odf.grids(it))), it, "grid") }
                Kind.EPUB -> ZipDoc(f).let { Prepared(Epub.toHtml(it), it, "doc") }
                Kind.RTF -> Prepared(Converted(Rtf.toHtml(f.inputStream().use { com.documentfacilitator.core.readAllCapped(it, MAX_TEXT) })), null, "doc")
                Kind.DOC -> Prepared(Legacy.docToHtml(f), null, "doc")
                Kind.PPT -> Prepared(Legacy.pptToHtml(f), null, "slides")
                Kind.XLS -> Prepared(Converted(Grid.toHtml(Legacy.xlsGrids(f) ?: emptyList()), note = "Legacy binary format: cell values only. Edits are saved as XLSX or CSV."), null, "grid")
                Kind.CSV -> Prepared(Converted(Grid.toHtml(listOf(Csv.parse(text(), name = doc.name.substringBeforeLast('.'))))), null, "grid")
                Kind.HTML -> Prepared(Converted(Html.body(text())), null, "doc")
                else -> Prepared(Converted(PlainText.toHtml(text())), null, "doc")
            }
        }

        fun asset(c: Context, name: String): String = c.assets.open(name).use { String(it.readBytes(), Charsets.UTF_8) }

        /** The complete viewer page. Only scripts carrying [nonce] may run; nothing can load from the network. */
        fun page(c: Context, cls: String, body: String, nonce: String, note: String? = null): String {
            val sb = StringBuilder(body.length + 40000)
            sb.append("<!DOCTYPE html><html><head><meta charset=\"utf-8\">")
            sb.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1, minimum-scale=0.25, maximum-scale=8\">")
            sb.append("<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; img-src https://df.local data:; style-src 'unsafe-inline'; font-src data:; script-src 'nonce-").append(nonce).append("'\">")
            sb.append("<style>").append(asset(c, "doc.css")).append("</style></head><body class=\"").append(cls).append("\"><div id=\"df\">")
            if (note != null) sb.append("<div class=\"x note\" contenteditable=\"false\">").append(com.documentfacilitator.core.esc(note)).append("</div>")
            sb.append(body).append("</div><script nonce=\"").append(nonce).append("\">").append(asset(c, "doc.js")).append("</script></body></html>")
            return sb.toString()
        }

        fun lockDown(w: WebView) {
            val s = w.settings
            s.javaScriptEnabled = true
            s.allowFileAccess = false
            s.allowContentAccess = false
            s.blockNetworkLoads = true
            s.setSupportZoom(true)
            s.builtInZoomControls = true
            s.displayZoomControls = false
            s.domStorageEnabled = false
            s.setGeolocationEnabled(false)
        }

        fun mimeOf(path: String) = when (path.substringAfterLast('.', "").lowercase()) {
            "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"; "gif" -> "image/gif"; "webp" -> "image/webp"
            "svg" -> "image/svg+xml"; "bmp" -> "image/bmp"
            else -> "application/octet-stream"
        }
    }

    private val web = WebView(ctx)
    override val view: View get() = web
    private var ready = false
    private val queue = ArrayList<() -> Unit>()
    private val isGrid = prep.cls == "grid"
    private var lastFind: String? = null
    private var zoom = 100

    init {
        lockDown(web)
        web.setBackgroundColor(0xFFE9EBEE.toInt())
        web.addJavascriptInterface(object {
            @JavascriptInterface
            fun dirty() { Ui.ui { host.markDirty() } }
        }, "DF")
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse? {
                val url = req.url.toString()
                if (url == "https://df.local/") return null
                if (url.startsWith(RES_BASE)) {
                    val path = try { java.net.URLDecoder.decode(url.substring(RES_BASE.length).replace("+", "%2B"), "UTF-8") } catch (_: Exception) { "" }
                    val ins = try { prep.zip?.stream(path) } catch (_: Exception) { null }
                    if (ins != null) return WebResourceResponse(mimeOf(path), null, ins)
                }
                return WebResourceResponse("text/plain", "utf-8", 404, "Not found", HashMap(), ByteArrayInputStream(ByteArray(0)))
            }
            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest) = true
            override fun onPageFinished(view: WebView, url: String?) {
                if (ready) return
                ready = true
                Ui.log("web: ${doc.kind} loaded")
                queue.forEach { it() }; queue.clear()
            }
        }
        val nonce = java.util.UUID.randomUUID().toString().replace("-", "")
        web.loadDataWithBaseURL("https://df.local/", page(ctx, prep.cls, prep.conv.html, nonce, prep.conv.note), "text/html", "utf-8", null)
    }

    private fun whenReady(a: () -> Unit) { if (ready) a() else queue.add(a) }

    private fun js(expr: String, cb: ((String?) -> Unit)? = null) = whenReady {
        web.evaluateJavascript(expr) { v ->
            if (cb != null) {
                val s = try { JSONTokener(v ?: "null").nextValue() } catch (_: Exception) { null }
                cb(if (s is String) s else if (s == null || s == JSONObject.NULL) null else s.toString())
            }
        }
    }

    override fun setMode(m: Int) {
        super.setMode(m)
        js("dfApi.setMode($m)") { n -> if (m == FILL) Ui.toast(ctx, if ((n?.toIntOrNull() ?: 0) > 0) "$n fields found" else "No fill-in fields found in this document") }
    }

    private fun exec(cmd: String, v: String? = null) = js("dfApi.exec(${JSONObject.quote(cmd)}${if (v != null) "," + JSONObject.quote(v) else ""})")

    private var colorIdx = 0

    override fun tools(): List<Tool> {
        val t = ArrayList<Tool>()
        if (mode == EDIT && !isGrid) {
            if (doc.kind != Kind.PPTX) {
                t.add(Tool("B") { exec("bold") }); t.add(Tool("I") { exec("italic") }); t.add(Tool("U") { exec("underline") })
                t.add(Tool("H1") { exec("formatBlock", "h1") }); t.add(Tool("H2") { exec("formatBlock", "h2") }); t.add(Tool("¶") { exec("formatBlock", "p") })
                t.add(Tool("• List") { exec("insertUnorderedList") }); t.add(Tool("1. List") { exec("insertOrderedList") })
                t.add(Tool("Left") { exec("justifyLeft") }); t.add(Tool("Center") { exec("justifyCenter") }); t.add(Tool("Right") { exec("justifyRight") })
                t.add(Tool("Color") { val cols = arrayOf("#d32f2f", "#1565c0", "#2e7d32", "#111111"); exec("foreColor", cols[colorIdx++ % cols.size]) })
                t.add(Tool("Mark") { exec("hiliteColor", "#ffe14d") })
                t.add(Tool("Image") { insertImage() })
            }
            t.add(Tool("Undo") { js("dfApi.undo()") }); t.add(Tool("Redo") { js("dfApi.redo()") })
        } else if (mode == FILL) {
            t.add(Tool("◀ Prev") { step(-1) }); t.add(Tool("Next ▶") { step(1) })
            t.add(Tool("Rescan") { js("dfApi.rescan()") { n -> Ui.toast(ctx, "${n ?: 0} fields") } })
        }
        return t
    }

    private fun step(d: Int) = js("dfApi.step($d)") { r ->
        when (r?.toIntOrNull() ?: 0) { 0 -> Ui.toast(ctx, "No fields found"); -1 -> Ui.toast(ctx, if (d > 0) "No more fields" else "No earlier fields") }
    }

    private fun insertImage() {
        Images.pick(host) { data ->
            Ui.bg {
                val uri = data?.data
                val bytes = try { uri?.let { u -> ctx.contentResolver.openInputStream(u)?.use { it.readBytes() } } } catch (_: Exception) { null }
                val bmp = bytes?.let { Images.decode(it, 1200) }
                if (bmp == null) { Ui.ui { Ui.toast(ctx, "That image could not be read") }; return@bg }
                val bo = ByteArrayOutputStream()
                val mime = if (bmp.hasAlpha()) { bmp.compress(Bitmap.CompressFormat.PNG, 100, bo); "image/png" } else { bmp.compress(Bitmap.CompressFormat.JPEG, 82, bo); "image/jpeg" }
                bmp.recycle()
                val url = "data:$mime;base64," + Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP)
                Ui.ui { exec("insertImage", url) }
            }
        }
    }

    override fun menu(): List<Tool> = listOf(
        Tool("Word count") { js("dfApi.stats()") { s -> try { val o = JSONObject(s ?: "{}"); Ui.info(ctx, "${o.optInt("words")} words\n${o.optInt("chars")} characters") } catch (_: Exception) {} } },
        Tool("Larger text") { zoomText(15) },
        Tool("Smaller text") { zoomText(-15) }
    )

    override fun zoomText(delta: Int) { zoom = (zoom + delta).coerceIn(50, 300); web.settings.textZoom = zoom }
    override fun setNight(on: Boolean) = js("dfApi.night($on)")

    override fun find(query: String, forward: Boolean) {
        if (query != lastFind) { lastFind = query; web.findAllAsync(query) } else web.findNext(forward)
    }

    override fun print(name: String) {
        val pm = ctx.getSystemService(Context.PRINT_SERVICE) as PrintManager
        pm.print(name, web.createPrintDocumentAdapter(name), null)
    }

    override fun snapshot(cb: (ByteArray, File?, Collection<String>) -> Unit) = js("dfApi.snapshot()") { s -> cb((s ?: "{}").toByteArray(), null, emptyList()) }

    override fun restore(payload: ByteArray, base: File?) = js("dfApi.restore(${JSONObject.quote(String(payload))})")

    override fun formats(): List<Format> {
        val docx = Format("Word document", "docx", DocType.mime(Kind.DOCX, ""))
        val html = Format("Web page", "html", "text/html")
        val txt = Format("Plain text", "txt", "text/plain")
        val xlsx = Format("Excel workbook", "xlsx", DocType.mime(Kind.XLSX, ""))
        val csv = Format("CSV (current sheet)", DocType.ext(doc.name).takeIf { it == "tsv" } ?: "csv", "text/csv")
        return when (doc.kind) {
            Kind.PPTX -> listOf(Format("PowerPoint presentation", "pptx", DocType.mime(Kind.PPTX, "")), html, txt)
            Kind.XLSX, Kind.ODS, Kind.XLS -> listOf(xlsx, csv)
            Kind.CSV -> listOf(csv, xlsx)
            Kind.HTML -> listOf(html, docx, txt)
            else -> listOf(docx, html, txt)
        }
    }

    private fun readImage(src: String): DocxWriter.Image? {
        val bytes: ByteArray? = try {
            if (src.startsWith(RES_BASE)) prep.zip?.bytes(java.net.URLDecoder.decode(src.substring(RES_BASE.length).replace("+", "%2B"), "UTF-8"), 12L * 1024 * 1024)
            else if (src.startsWith("data:")) Base64.decode(src.substring(src.indexOf(',') + 1), Base64.DEFAULT)
            else null
        } catch (_: Exception) { null }
        if (bytes == null || bytes.size < 8) return null
        val ext = when {
            bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() -> "png"
            bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "jpeg"
            bytes[0] == 0x47.toByte() && bytes[1] == 0x49.toByte() -> "gif"
            else -> return null
        }
        val o = android.graphics.BitmapFactory.Options(); o.inJustDecodeBounds = true
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        return DocxWriter.Image(bytes, ext, Math.max(1, o.outWidth), Math.max(1, o.outHeight))
    }

    private fun rebuildDocx(out: File, cb: (Boolean, String?) -> Unit, note: String?) = js("dfApi.blocks()") { s ->
        Ui.bg {
            try {
                val blocks = JSONArray(s ?: "[]")
                val images = HashMap<String, DocxWriter.Image>()
                fun scan(a: JSONArray) {
                    for (i in 0 until a.length()) {
                        val b = a.getJSONObject(i)
                        b.optJSONArray("runs")?.let { runs ->
                            for (j in 0 until runs.length()) {
                                val r = runs.getJSONObject(j)
                                val src = r.optString("img", "")
                                if (src.isNotEmpty()) {
                                    val key = "i${images.size}"
                                    val im = readImage(src)
                                    if (im != null) { images[key] = im; r.put("img", key) } else r.put("img", "")
                                }
                            }
                        }
                        b.optJSONArray("rows")?.let { rows -> for (j in 0 until rows.length()) { val row = rows.getJSONArray(j); for (k in 0 until row.length()) scan(row.getJSONArray(k)) } }
                    }
                }
                scan(blocks)
                DocxWriter.write(out, blocks, images)
                Ui.ui { cb(true, note) }
            } catch (t: Throwable) {
                Ui.log("docx rebuild failed: $t")
                Ui.ui { cb(false, t.message) }
            }
        }
    }

    private fun parsePatch(s: String?): Pair<Boolean, Map<String, OoxmlPatch.PartEdits>>? {
        val o = try { JSONObject(s ?: return null) } catch (_: Exception) { return null }
        val map = HashMap<String, OoxmlPatch.PartEdits>()
        fun put(obj: JSONObject?, run: Boolean) {
            if (obj == null) return
            for (k in obj.keys()) {
                val part = prep.conv.parts.getOrNull(k.substringBefore(':').toIntOrNull() ?: -1) ?: continue
                val idx = k.substringAfter(':').toIntOrNull() ?: continue
                val e = map.getOrPut(part) { OoxmlPatch.PartEdits() }
                if (run) e.runs[idx] = obj.getString(k) else e.paras[idx] = obj.getString(k)
            }
        }
        put(o.optJSONObject("runs"), true); put(o.optJSONObject("paras"), false)
        return Pair(o.optBoolean("ok"), map)
    }

    override fun export(ext: String, out: File, cb: (Boolean, String?) -> Unit) {
        fun fail(t: Throwable) { Ui.log("export failed: $t"); Ui.ui { cb(false, t.message ?: t.toString()) } }
        when (ext) {
            "docx", "pptx" -> {
                val own = (ext == "docx" && doc.kind == Kind.DOCX) || (ext == "pptx" && doc.kind == Kind.PPTX)
                if (!own) { rebuildDocx(out, cb, "Saved as a new Word document. Layout is simplified compared with the original ${doc.kind.label} file."); return }
                js("dfApi.patch()") { s ->
                    val p = parsePatch(s)
                    if (p != null && p.first) Ui.bg {
                        try {
                            if (p.second.isEmpty()) doc.file.copyTo(out, true) else OoxmlPatch.patch(doc.file, out, if (ext == "docx") "w" else "a", p.second)
                            Ui.ui { cb(true, null) }
                        } catch (t: Throwable) { fail(t) }
                    } else if (ext == "docx") rebuildDocx(out, cb, "The structure of the document was changed (new paragraphs, formatting or images), so it was rebuilt. Page layout, headers/footers and styles of the original are simplified.")
                    else cb(false, "Slides support text changes inside existing text boxes only. Undo the structural change, or export as HTML or text.")
                }
            }
            "xlsx", "csv", "tsv" -> js("dfApi.grid()") { s ->
                Ui.bg {
                    try {
                        val o = JSONObject(s ?: "{}")
                        val sheets = o.getJSONArray("sheets")
                        fun rows(i: Int): List<List<String>> {
                            val a = sheets.getJSONObject(i).getJSONArray("rows")
                            return List(a.length()) { r -> val row = a.getJSONArray(r); List(row.length()) { c -> row.getString(c) } }
                        }
                        if (ext != "xlsx") {
                            val i = o.optInt("active", 0).coerceIn(0, Math.max(0, sheets.length() - 1))
                            var sep = if (ext == "tsv") '\t' else ','
                            if (doc.kind == Kind.CSV && ext == "csv") sep = Csv.separator(doc.file.inputStream().use { String(com.documentfacilitator.core.readAllCapped(it, 4096)) })
                            out.writeText(if (sheets.length() == 0) "" else GridWriter.csv(rows(i), sep))
                        } else if (doc.kind == Kind.XLSX) {
                            val edits = HashMap<String, Map<String, String>>()
                            for (i in 0 until sheets.length()) {
                                val e = sheets.getJSONObject(i).getJSONObject("edits")
                                val part = prep.conv.parts.getOrNull(i) ?: continue
                                val m = HashMap<String, String>()
                                for (k in e.keys()) m[k] = e.getString(k)
                                if (m.isNotEmpty()) edits[part] = m
                            }
                            if (edits.isEmpty()) doc.file.copyTo(out, true) else XlsxPatch.patch(doc.file, out, edits)
                        } else GridWriter.xlsx(out, List(sheets.length()) { i -> Pair(sheets.getJSONObject(i).getString("name"), rows(i)) })
                        val note = if (ext == "xlsx" && doc.kind != Kind.XLSX) "Saved as a new workbook with cell values and formulas; original cell formatting is not carried over." else null
                        Ui.ui { cb(true, note) }
                    } catch (t: Throwable) { fail(t) }
                }
            }
            "html" -> js("dfApi.html()") { s ->
                Ui.bg {
                    try {
                        // inline pictures so the exported page is self-contained
                        val re = Regex("src=\"(" + Regex.escape(RES_BASE) + "[^\"]+)\"")
                        val body = re.replace(s ?: "") { m ->
                            val im = readImage(com.documentfacilitator.core.unescape(m.groupValues[1]))
                            if (im == null || im.bytes.size > 3 * 1024 * 1024) "src=\"\"" else "src=\"data:image/${im.ext};base64,${Base64.encodeToString(im.bytes, Base64.NO_WRAP)}\""
                        }
                        out.writeText(Html.document(doc.name.substringBeforeLast('.'), body))
                        Ui.ui { cb(true, null) }
                    } catch (t: Throwable) { fail(t) }
                }
            }
            else -> js("dfApi.text()") { s -> Ui.bg { try { out.writeText(s ?: ""); Ui.ui { cb(true, null) } } catch (t: Throwable) { fail(t) } } }
        }
    }

    override fun close() {
        try { prep.zip?.close() } catch (_: Exception) {}
        (web.parent as? android.view.ViewGroup)?.removeView(web)
        web.destroy()
    }
}
