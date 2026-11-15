package com.documentfacilitator

import android.content.Context
import android.graphics.Typeface
import android.print.PrintManager
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.text.method.KeyListener
import android.view.Gravity
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import com.documentfacilitator.core.Html
import com.documentfacilitator.core.Kind
import com.documentfacilitator.core.Markdown
import com.documentfacilitator.core.PlainText
import java.io.File

/** Plain text, source code and Markdown: a lean native editor, with a rendered preview for Markdown. */
class TextContent(host: Host, doc: Loaded, text: String, private val truncated: Boolean) : Content(host, doc) {
    private val md = doc.kind == Kind.MARKDOWN
    private val box = FrameLayout(ctx)
    private val edit = EditText(ctx)
    private var preview: WebView? = null
    private val keys: KeyListener?
    override val view: View get() = box

    private class Change(val start: Int, val before: String, val after: String)
    private val undo = ArrayList<Change>()
    private val redo = ArrayList<Change>()
    private var replaying = false
    private var removed = ""

    init {
        edit.setText(text)
        edit.typeface = Typeface.MONOSPACE
        edit.textSize = 14f
        edit.setTextColor(0xFF111111.toInt())
        edit.setBackgroundColor(0xFFFFFFFF.toInt())
        edit.gravity = Gravity.TOP or Gravity.START
        edit.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        edit.setHorizontallyScrolling(false)
        val p = Ui.dp(ctx, 12f)
        edit.setPadding(p, p, p, p * 4)
        keys = edit.keyListener
        edit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) { removed = s.subSequence(start, start + count).toString() }
            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
                if (replaying) return
                undo.add(Change(start, removed, s.subSequence(start, start + count).toString()))
                if (undo.size > 300) undo.removeAt(0)
                redo.clear()
                host.markDirty()
            }
            override fun afterTextChanged(s: Editable) {}
        })
        box.addView(edit, FrameLayout.LayoutParams(-1, -1))
        apply()
        Ui.log("text: ${doc.kind} chars=${text.length}")
    }

    private fun apply() {
        val editable = mode != VIEW && !truncated
        edit.setTextIsSelectable(!editable)
        edit.keyListener = if (editable) keys else null
        edit.isFocusable = true
        edit.isFocusableInTouchMode = true
        edit.isCursorVisible = editable
        if (md && mode == VIEW) showPreview() else { preview?.visibility = View.GONE; edit.visibility = View.VISIBLE }
    }

    private fun showPreview() {
        var w = preview
        if (w == null) {
            w = WebView(ctx)
            WebContent.lockDown(w)
            w.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, req: android.webkit.WebResourceRequest) = true
            }
            box.addView(w, FrameLayout.LayoutParams(-1, -1))
            preview = w
        }
        w.loadDataWithBaseURL("https://df.local/", WebContent.page(ctx, "doc", Markdown.toHtml(edit.text.toString()), "n" + System.nanoTime()), "text/html", "utf-8", null)
        w.visibility = View.VISIBLE
        edit.visibility = View.GONE
    }

    override fun setMode(m: Int) {
        if (m != VIEW && truncated) { Ui.toast(ctx, "This file is too large to edit here; it is shown read-only."); return }
        super.setMode(m)
        apply()
    }

    private fun replay(from: ArrayList<Change>, to: ArrayList<Change>, undoing: Boolean) {
        if (from.isEmpty()) return
        val c = from.removeAt(from.size - 1)
        replaying = true
        val e = edit.text
        if (undoing) e.replace(c.start, c.start + c.after.length, c.before) else e.replace(c.start, c.start + c.before.length, c.after)
        replaying = false
        edit.setSelection(Math.min(e.length, c.start + (if (undoing) c.before.length else c.after.length)))
        to.add(c)
        host.markDirty()
    }

    private val blank = Regex("_{3,}|\\.{6,}|\\[ ?\\]|\\[\\s{2,}\\]|\\{\\{[^{}\\n]{1,40}\\}\\}|<<[^<>\\n]{1,40}>>|\\[[A-Z][A-Za-z0-9 ,.'/\\-]{1,40}\\]|:[ \\t]*$", RegexOption.MULTILINE)

    private fun stepBlank(dir: Int) {
        val s = edit.text.toString()
        val all = blank.findAll(s).toList()
        if (all.isEmpty()) { Ui.toast(ctx, "No fill-in blanks found"); return }
        val pos = if (dir > 0) edit.selectionEnd else edit.selectionStart
        val m = if (dir > 0) all.firstOrNull { it.range.first >= pos } else all.lastOrNull { it.range.last + 1 <= pos && it.range.first < edit.selectionStart }
        if (m == null) { Ui.toast(ctx, if (dir > 0) "No more blanks" else "No earlier blanks"); return }
        edit.requestFocus()
        // a trailing "Label:" is filled by typing after it; other blanks are selected so typing replaces them
        if (m.value.startsWith(":")) edit.setSelection(m.range.last + 1) else edit.setSelection(m.range.first, m.range.last + 1)
    }

    override fun tools(): List<Tool> {
        val t = ArrayList<Tool>()
        if (mode == FILL) { t.add(Tool("◀ Prev") { stepBlank(-1) }); t.add(Tool("Next ▶") { stepBlank(1) }) }
        if (mode != VIEW) {
            t.add(Tool("Undo") { replay(undo, redo, true) }); t.add(Tool("Redo") { replay(redo, undo, false) })
            t.add(Tool("Replace…") {
                Ui.prompt(ctx, "Find", "") { a -> if (a.isNotEmpty()) Ui.prompt(ctx, "Replace \"$a\" with", "") { b ->
                    val s = edit.text.toString()
                    val n = s.split(a).size - 1
                    if (n > 0) edit.text.replace(0, s.length, s.replace(a, b))
                    Ui.toast(ctx, "$n replaced")
                } }
            })
            t.add(Tool("A−") { zoomText(-1) }); t.add(Tool("A+") { zoomText(1) })
        }
        return t
    }

    override fun menu(): List<Tool> = listOf(
        Tool("Word count") { val s = edit.text.toString(); Ui.info(ctx, "${s.trim().split(Regex("\\s+")).count { it.isNotEmpty() }} words\n${s.length} characters\n${s.count { it == '\n' } + 1} lines") },
        Tool("Larger text") { zoomText(2) }, Tool("Smaller text") { zoomText(-2) }
    )

    override fun zoomText(delta: Int) {
        val sp = (edit.textSize / ctx.resources.displayMetrics.scaledDensity + delta).coerceIn(8f, 40f)
        edit.textSize = sp
        preview?.settings?.textZoom = (sp / 14f * 100).toInt()
    }

    override fun setNight(on: Boolean) {
        edit.setTextColor(if (on) Ui.TEXT else 0xFF111111.toInt())
        edit.setBackgroundColor(if (on) Ui.BG else 0xFFFFFFFF.toInt())
        preview?.evaluateJavascript("dfApi.night($on)", null)
    }

    override fun find(query: String, forward: Boolean) {
        if (preview?.visibility == View.VISIBLE) { preview?.findAllAsync(query); return }
        val s = edit.text.toString()
        var i = if (forward) s.indexOf(query, edit.selectionEnd, true) else s.lastIndexOf(query, Math.max(0, edit.selectionStart - 1), true)
        if (i < 0) i = if (forward) s.indexOf(query, 0, true) else s.lastIndexOf(query, s.length, true)
        if (i < 0) { Ui.toast(ctx, "Not found"); return }
        edit.requestFocus()
        edit.setSelection(i, i + query.length)
    }

    override fun print(name: String) {
        val w = WebView(ctx)
        WebContent.lockDown(w)
        w.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                (ctx.getSystemService(Context.PRINT_SERVICE) as PrintManager).print(name, view.createPrintDocumentAdapter(name), null)
            }
        }
        val body = if (md) Markdown.toHtml(edit.text.toString()) else PlainText.toHtml(edit.text.toString())
        w.loadDataWithBaseURL("https://df.local/", WebContent.page(ctx, "doc", body, "n" + System.nanoTime()), "text/html", "utf-8", null)
    }

    override fun snapshot(cb: (ByteArray, File?, Collection<String>) -> Unit) = cb(edit.text.toString().toByteArray(), null, emptyList())

    override fun restore(payload: ByteArray, base: File?) {
        replaying = true
        edit.setText(String(payload))
        replaying = false
        undo.clear(); redo.clear()
        apply()
    }

    override fun formats(): List<Format> {
        val ext = com.documentfacilitator.core.DocType.ext(doc.name).ifEmpty { "txt" }
        val own = Format(if (md) "Markdown" else "Text file (.$ext)", ext, if (md) "text/markdown" else "text/plain")
        return if (md) listOf(own, Format("Web page", "html", "text/html")) else listOf(own)
    }

    override fun export(ext: String, out: File, cb: (Boolean, String?) -> Unit) {
        val s = edit.text.toString()
        Ui.bg {
            try {
                if (truncated) doc.file.copyTo(out, true)
                else out.writeText(if (ext == "html" && md) Html.document(doc.name.substringBeforeLast('.'), Markdown.toHtml(s)) else s)
                Ui.ui { cb(true, null) }
            } catch (t: Throwable) {
                Ui.ui { cb(false, t.message) }
            }
        }
    }

    override fun close() { preview?.let { box.removeView(it); it.destroy() } }
}
