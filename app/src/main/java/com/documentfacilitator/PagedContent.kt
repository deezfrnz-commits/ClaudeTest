package com.documentfacilitator

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.OverScroller
import com.documentfacilitator.core.Blank
import com.documentfacilitator.core.FieldDetector
import com.documentfacilitator.core.FormField
import com.documentfacilitator.core.Helv
import com.documentfacilitator.core.Kind
import com.documentfacilitator.core.Mark
import com.documentfacilitator.core.PdfEdit
import com.documentfacilitator.core.PdfFile
import com.documentfacilitator.core.PdfImage
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

/** Fixed-page documents: PDF (system PdfRenderer) and raster images, with an editable overlay. */
class PagedContent(host: Host, doc: Loaded) : Content(host, doc) {
    /** Something on a page the user can fill in: an AcroForm widget or a blank found on the page image. */
    private class Slot(val id: String, val page: Int, val type: Int, val x: Float, val y: Float, val w: Float, val h: Float, val acro: FormField?)

    private val isPdf = doc.kind == Kind.PDF
    private var work: File = doc.file
    private var based = false
    private var pfd: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    private var image: Bitmap? = null
    private var pageW = FloatArray(0)
    private var pageH = FloatArray(0)
    /** /Rotate of each PDF page; blank detection works on the upright page. */
    private var pageRot = IntArray(0)
    private var failed: String? = null

    private val marks = ArrayList<Mark>()
    private val fills = HashMap<String, String>()
    private var acro: List<FormField> = emptyList()
    private val blanks = HashMap<Int, List<Slot>>()
    private val detecting = HashSet<Int>()
    private val waiters = HashMap<Int, ArrayList<() -> Unit>>()
    private var scan = true
    private val undo = ArrayList<String>()
    private val redo = ArrayList<String>()

    private val thread = HandlerThread("df-render").apply { start() }
    private val rh = Handler(thread.looper)
    private val pv = PagedView(ctx)
    override val view: View get() = pv

    private var tool = HAND
    private var color = Color.BLACK
    private var hlColor = 0xFFFFE14D.toInt()
    private var stroke = 2f
    private var textSize = 12f
    private var selected: Mark? = null
    private var curSlot: Slot? = null
    private val bitmaps = HashMap<String, Bitmap?>()

    companion object {
        const val HAND = 0
        const val HL = 1
        const val PEN = 2
        const val TEXT = 3
        const val ERASE = 4
        const val RETYPE = 5
        const val BOX = 6
        const val LINE = 7
        const val CHECK = 8
        const val AREA = 5 // slot type beyond FormField types
        private val palette = intArrayOf(Color.BLACK, 0xFFD32F2F.toInt(), 0xFF1565C0.toInt(), 0xFF2E7D32.toInt(), Color.WHITE)
        private val hlPalette = intArrayOf(0xFFFFE14D.toInt(), 0xFF8CF08C.toInt(), 0xFF8CD8FF.toInt(), 0xFFFFA6C9.toInt())
    }

    init {
        rh.post { open(doc.file) }
    }

    // ---- source ------------------------------------------------------------------------------

    /** Runs on the render thread. */
    private fun open(file: File) {
        try {
            renderer?.close(); pfd?.close()
            renderer = null; pfd = null
            val w: FloatArray
            val h: FloatArray
            var fields: List<FormField> = emptyList()
            var rots = IntArray(0)
            if (isPdf) {
                val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                pfd = fd
                val r = PdfRenderer(fd)
                renderer = r
                w = FloatArray(r.pageCount); h = FloatArray(r.pageCount)
                for (i in 0 until r.pageCount) r.openPage(i).use { p -> w[i] = p.width.toFloat(); h[i] = p.height.toFloat() }
                try { PdfFile.open(file).use { pf -> fields = PdfEdit.fields(pf); rots = IntArray(pf.pages.size) { pf.pages[it].rotate } } } catch (t: Throwable) { Ui.log("form scan skipped: $t") }
            } else {
                val bmp = Images.decodeFile(file, 3000) ?: throw java.io.IOException("unsupported image")
                image = bmp
                w = floatArrayOf(bmp.width.toFloat()); h = floatArrayOf(bmp.height.toFloat())
            }
            work = file
            Ui.ui {
                pageW = w; pageH = h; acro = fields; failed = null
                pageRot = if (rots.size == w.size) rots else IntArray(w.size)
                blanks.clear(); detecting.clear(); waiters.clear()
                scan = fields.isEmpty()
                Ui.log("paged: ${doc.kind} pages=${w.size} formFields=${fields.size}")
                pv.reset()
            }
        } catch (t: Throwable) {
            Ui.log("open failed: $t")
            val msg = if (t is SecurityException) "This PDF is password-protected and cannot be opened." else "This file could not be displayed (${t.message ?: t.javaClass.simpleName})."
            Ui.ui { failed = msg; pageW = FloatArray(0); pageH = FloatArray(0); pv.reset() }
        }
    }

    /** Renders part of a page; [m] maps page units to bitmap pixels. Render thread only. */
    private fun renderInto(page: Int, bmp: Bitmap, clip: Rect?, m: Matrix) {
        val r = renderer
        if (r != null) {
            if (page >= r.pageCount) return
            r.openPage(page).use { it.render(bmp, clip, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) }
        } else {
            val src = image ?: return
            val cv = Canvas(bmp)
            if (clip != null) cv.clipRect(clip)
            cv.drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG))
        }
    }

    override fun close() {
        rh.post {
            try { renderer?.close(); pfd?.close() } catch (_: Throwable) {}
            renderer = null; pfd = null; image?.recycle(); image = null
            thread.quitSafely()
        }
        pv.release()
    }

    // ---- edits -------------------------------------------------------------------------------

    private fun stateJson(): String = JSONObject().put("marks", Mark.listToJson(marks)).put("fills", JSONObject(fills as Map<*, *>)).toString()

    private fun loadJson(s: String) {
        val o = JSONObject(s)
        marks.clear(); marks.addAll(Mark.listFromJson(o.optJSONArray("marks") ?: JSONArray()))
        fills.clear()
        o.optJSONObject("fills")?.let { f -> for (k in f.keys()) fills[k] = f.getString(k) }
        selected = null
        pv.invalidate()
    }

    private fun change(block: () -> Unit) {
        undo.add(stateJson())
        if (undo.size > 60) undo.removeAt(0)
        redo.clear()
        block()
        host.markDirty()
        pv.invalidate()
    }

    private fun undoRedo(from: ArrayList<String>, to: ArrayList<String>) {
        if (from.isEmpty()) return
        to.add(stateJson())
        loadJson(from.removeAt(from.size - 1))
        host.markDirty()
        host.toolsChanged()
    }

    override fun setMode(m: Int) {
        super.setMode(m)
        tool = HAND; selected = null; curSlot = null
        pv.invalidate()
    }

    private fun pick(t: Int) { tool = t; selected = null; host.toolsChanged(); pv.invalidate() }

    override fun tools(): List<Tool> {
        val t = ArrayList<Tool>()
        when (mode) {
            EDIT -> {
                t.add(Tool("Select", tool == HAND) { pick(HAND) })
                t.add(Tool("Highlight", tool == HL) { pick(HL) })
                t.add(Tool("Pen", tool == PEN) { pick(PEN) })
                t.add(Tool("Text", tool == TEXT) { pick(TEXT) })
                t.add(Tool("Replace", tool == RETYPE) { pick(RETYPE) })
                t.add(Tool("Erase", tool == ERASE) { pick(ERASE) })
                t.add(Tool("Box", tool == BOX) { pick(BOX) })
                t.add(Tool("Line", tool == LINE) { pick(LINE) })
                t.add(Tool("✓", tool == CHECK) { pick(CHECK) })
                t.add(Tool("Image") { addImage(null) })
                t.add(Tool("Sign") { addSignature(null) })
                t.add(Tool("Color") { cycleColor() })
                t.add(Tool("A−") { resize(-1) })
                t.add(Tool("A+") { resize(1) })
                if (selected != null) {
                    if (selected?.kind == Mark.TEXT) t.add(Tool("Edit text") { editText(selected!!) })
                    t.add(Tool("Delete") { val s = selected; if (s != null) change { marks.remove(s); selected = null }; host.toolsChanged() })
                }
                t.add(Tool("Undo") { undoRedo(undo, redo) })
                t.add(Tool("Redo") { undoRedo(redo, undo) })
            }
            FILL -> {
                t.add(Tool("◀ Prev") { stepSlot(-1) })
                t.add(Tool("Next ▶") { stepSlot(1) })
                t.add(Tool(if (acro.isEmpty()) "Scan page" else "Scan for more", scan) { scan = !scan; pv.invalidate(); host.toolsChanged() })
                t.add(Tool("Sign") { addSignature(null) })
                t.add(Tool("Undo") { undoRedo(undo, redo) })
                t.add(Tool("Redo") { undoRedo(redo, undo) })
            }
        }
        return t
    }

    override fun menu(): List<Tool> {
        val t = ArrayList<Tool>()
        t.add(Tool("Go to page…") {
            Ui.prompt(ctx, "Go to page (1–${pageW.size})", "${pv.currentPage() + 1}") { s -> s.trim().toIntOrNull()?.let { pv.scrollToPage(it - 1) } }
        })
        if (isPdf) {
            t.add(Tool("Rotate this page") { restructure("Rotate page ${pv.currentPage() + 1}?") { pdf, out -> PdfEdit.rotate(pdf, out, listOf(pv.currentPage()), 90) } })
            t.add(Tool("Delete this page") { restructure("Delete page ${pv.currentPage() + 1}?") { pdf, out -> PdfEdit.delete(pdf, out, listOf(pv.currentPage())) } })
        }
        return t
    }

    private fun cycleColor() {
        val sel = selected
        if (tool == HL || sel?.kind == Mark.HIGHLIGHT) {
            hlColor = hlPalette[(hlPalette.indexOf(hlColor) + 1) % hlPalette.size]
            if (sel != null) change { sel.color = hlColor }
        } else {
            color = palette[(palette.indexOf(color) + 1) % palette.size]
            if (sel != null) change { sel.color = color }
        }
        pv.flashColor(if (tool == HL || sel?.kind == Mark.HIGHLIGHT) hlColor else color)
    }

    private fun resize(d: Int) {
        val sel = selected
        if (sel != null) change {
            when (sel.kind) {
                Mark.TEXT -> sel.size = (sel.size + d).coerceIn(5f, 72f)
                Mark.IMAGE, Mark.CHECK -> { val f = if (d > 0) 1.15f else 0.87f; sel.w *= f; sel.h *= f }
                else -> sel.size = (sel.size + d * 0.5f).coerceIn(0.5f, 12f)
            }
        } else if (tool == TEXT || tool == RETYPE) { textSize = (textSize + d).coerceIn(5f, 72f); Ui.toast(ctx, "Text size ${textSize.toInt()} pt") }
        else { stroke = (stroke + d * 0.5f).coerceIn(0.5f, 12f); Ui.toast(ctx, "Stroke $stroke pt") }
    }

    private fun editText(m: Mark) {
        Ui.prompt(ctx, "Text", m.text ?: "", true) { s -> change { if (s.isEmpty()) { marks.remove(m); selected = null } else m.text = s } }
    }

    private fun place(w: Float, h: Float, slot: Slot?): FloatArray {
        if (slot != null) {
            val f = Math.min(slot.w / w, slot.h / h)
            return floatArrayOf(slot.page.toFloat(), slot.x + (slot.w - w * f) / 2, slot.y + (slot.h - h * f) / 2, w * f, h * f)
        }
        val p = pv.currentPage()
        val c = pv.viewCenterOnPage(p)
        val f = Math.min(1f, Math.min(pageW[p] * 0.4f / w, pageH[p] * 0.3f / h))
        return floatArrayOf(p.toFloat(), c[0] - w * f / 2, c[1] - h * f / 2, w * f, h * f)
    }

    private fun addImageMark(id: String, pxW: Int, pxH: Int, slot: Slot?) {
        if (pageW.isEmpty()) return
        // imported pixels are treated as 96 dpi on PDF pages
        val k = if (isPdf) 0.75f else 1f
        val r = place(pxW * k, pxH * k, slot)
        change {
            if (slot != null) marks.removeAll { it.field == slot.id }
            val m = Mark(Mark.IMAGE, r[0].toInt())
            m.x = r[1]; m.y = r[2]; m.w = r[3]; m.h = r[4]; m.img = id; m.field = slot?.id
            marks.add(m)
            if (mode == EDIT) { tool = HAND; selected = m }
        }
        host.toolsChanged()
    }

    private fun addImage(slot: Slot?) {
        Images.pick(host) { data ->
            Ui.bg {
                val r = Images.import(ctx.applicationContext, data)
                Ui.ui { if (r == null) Ui.toast(ctx, "That image could not be read") else addImageMark(r.first, r.second, r.third, slot) }
            }
        }
    }

    private fun addSignature(slot: Slot?) {
        SignaturePad.show(ctx) { id, w, h -> val s = 200f / Math.max(w, 1); addImageMarkScaled(id, w * s, h * s, slot) }
    }

    private fun addImageMarkScaled(id: String, w: Float, h: Float, slot: Slot?) {
        if (pageW.isEmpty()) return
        val k = if (isPdf) 1f else pageW[0] / 612f
        val r = place(w * k, h * k, slot)
        change {
            if (slot != null) marks.removeAll { it.field == slot.id }
            val m = Mark(Mark.IMAGE, r[0].toInt())
            m.x = r[1]; m.y = r[2]; m.w = r[3]; m.h = r[4]; m.img = id; m.field = slot?.id
            marks.add(m)
            if (mode == EDIT) { tool = HAND; selected = m }
        }
        host.toolsChanged()
    }

    /** Structural PDF change: pending overlay edits are baked in first, then the page tree is rewritten. */
    private fun restructure(question: String, op: (PdfFile, File) -> Unit) {
        if (pageW.isEmpty()) return
        Ui.confirm(ctx, "$question\n\nPending edits are merged into the working copy first. The original file is not touched until you export or overwrite it.") {
            val cur = work
            val prepared = prepareMarks()
            val f = HashMap(fills)
            rh.post {
                try {
                    var src = cur
                    if (prepared.isNotEmpty() || f.isNotEmpty()) {
                        val baked = File(Store.openDir(ctx), "${System.nanoTime()}.pdf")
                        PdfFile.open(src).use { PdfEdit.apply(it, baked, prepared, ::pdfImage, f) }
                        src = baked
                    }
                    val out = File(Store.openDir(ctx), "${System.nanoTime()}.pdf")
                    PdfFile.open(src).use { op(it, out) }
                    if (src != cur && src != doc.file) src.delete()
                    Ui.ui { marks.clear(); fills.clear(); undo.clear(); redo.clear(); selected = null; based = true; host.markDirty() }
                    open(out)
                    if (cur != doc.file) cur.delete()
                } catch (t: Throwable) {
                    Ui.log("restructure failed: $t")
                    Ui.ui { Ui.info(ctx, "This PDF's structure cannot be changed (${t.message}).") }
                }
            }
        }
    }

    // ---- fill mode ---------------------------------------------------------------------------

    private fun slots(page: Int): List<Slot> {
        val out = ArrayList<Slot>()
        for (f in acro) if (f.page == page && !f.readOnly) out.add(Slot(f.id, page, f.type, f.x, f.y, f.w, f.h, f))
        if (scan) {
            val found = blanks[page]
            if (found == null) detect(page)
            else for (b in found) {
                if (out.none { a -> a.acro != null && a.x < b.x + b.w && b.x < a.x + a.w && a.y < b.y + b.h && b.y < a.y + a.h }) out.add(b)
            }
        }
        return out
    }

    private fun detect(page: Int, done: (() -> Unit)? = null) {
        if (page < 0 || page >= pageW.size) { done?.invoke(); return }
        if (done != null) waiters.getOrPut(page) { ArrayList() }.add(done)
        if (!detecting.add(page)) return
        val pw = pageW[page]
        val ph = pageH[page]
        rh.post {
            var res: List<Slot> = emptyList()
            try {
                val ppp = Math.min(1000f / pw, Math.min(1400f / ph, if (isPdf) 110f / 72f else 1f))
                val w = Math.max(32, (pw * ppp).toInt())
                val h = Math.max(32, (ph * ppp).toInt())
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(Color.WHITE)
                val m = Matrix(); m.setScale(ppp, ppp)
                renderInto(page, bmp, null, m)
                // detect on the upright page (text horizontal), then map results back to the view
                val rot = pageRot.getOrElse(page) { 0 }
                val up = if (rot == 0) bmp else Bitmap.createBitmap(bmp, 0, 0, w, h, Matrix().apply { postRotate(-rot.toFloat()) }, false).also { bmp.recycle() }
                val uw = up.width; val uh = up.height
                val back = Matrix()
                back.postRotate(rot.toFloat())
                val ob = RectF(0f, 0f, uw.toFloat(), uh.toFloat()); back.mapRect(ob)
                back.postTranslate(-ob.left, -ob.top)
                val px = IntArray(uw * uh)
                up.getPixels(px, 0, uw, 0, 0, uw, uh)
                up.recycle()
                val lum = ByteArray(uw * uh)
                for (i in px.indices) { val c = px[i]; lum[i] = ((((c shr 16) and 0xff) * 3 + ((c shr 8) and 0xff) * 6 + (c and 0xff)) / 10).toByte() }
                val unit = if (isPdf) ppp else Math.max(w, h) / 1000f * (100f / 72f)
                res = FieldDetector.detect(lum, uw, uh, unit).map { blank ->
                    val r = RectF(blank.x.toFloat(), blank.y.toFloat(), (blank.x + blank.w).toFloat(), (blank.y + blank.h).toFloat())
                    back.mapRect(r)
                    val b = com.documentfacilitator.core.Blank(blank.kind, r.left.toInt(), r.top.toInt(), r.width().toInt(), r.height().toInt())
                    val type = when (b.kind) { Blank.CHECK -> FormField.CHECK; Blank.AREA -> AREA; else -> FormField.TEXT }
                    Slot("d$page:${b.x}:${b.y}", page, type, b.x / ppp, b.y / ppp, b.w / ppp, b.h / ppp, null)
                }
            } catch (t: Throwable) {
                Ui.log("detect failed: $t")
            }
            val r = res
            Ui.ui {
                blanks[page] = r; detecting.remove(page)
                Ui.log("fill: page ${page + 1} blanks=${r.size}")
                pv.invalidate()
                waiters.remove(page)?.forEach { it() }
            }
        }
    }

    private fun slotValue(s: Slot): String {
        if (s.acro != null) return fills[s.id] ?: s.acro.value
        val m = marks.firstOrNull { it.field == s.id } ?: return ""
        return when (m.kind) { Mark.TEXT -> m.text ?: ""; else -> "1" }
    }

    private fun stepSlot(dir: Int) {
        if (pageW.isEmpty()) return
        val cur = curSlot
        var page = cur?.page ?: pv.currentPage()
        fun tryPage(tries: Int) {
            val list = slots(page)
            if (scan && blanks[page] == null) { detect(page) { tryPage(tries) }; if (detecting.contains(page)) return }
            val idx = if (cur != null && cur.page == page) list.indexOfFirst { it.id == cur.id } else if (dir > 0) -1 else list.size
            val next = idx + dir
            if (next in list.indices) { val s = list[next]; curSlot = s; pv.reveal(s.page, s.x, s.y, s.w, s.h); editSlot(s); return }
            page += dir
            if (page !in pageW.indices || tries > 40) { Ui.toast(ctx, if (dir > 0) "No more fields" else "No earlier fields"); return }
            tryPage(tries + 1)
        }
        tryPage(0)
    }

    private fun setSlotText(s: Slot, text: String) {
        change {
            if (s.acro != null) fills[s.id] = text
            else {
                marks.removeAll { it.field == s.id }
                if (text.isNotEmpty()) {
                    val m = Mark(Mark.TEXT, s.page)
                    m.field = s.id; m.text = text; m.color = Color.BLACK
                    if (s.type == AREA || s.h > 30f * unit(s.page)) {
                        m.size = 11f * unit(s.page); m.x = s.x + 3f * unit(s.page); m.y = s.y + 3f * unit(s.page); m.w = s.w - 6f * unit(s.page)
                    } else {
                        var size = Math.min(s.h * 0.8f, 12f * unit(s.page)).coerceAtLeast(5f * unit(s.page))
                        while (size > 5f * unit(s.page) && Helv.width(text, size) > s.w - 4f) size -= 0.5f
                        m.size = size; m.x = s.x + 2f * unit(s.page); m.y = s.y + s.h - size * 1.2f; m.w = 0f
                    }
                    marks.add(m)
                }
            }
        }
    }

    /** Size of one typographic point in page units (images use pixels as units). */
    private fun unit(page: Int) = if (isPdf) 1f else Math.max(pageW[page], pageH[page]) / 1000f * (100f / 72f)

    private fun editSlot(s: Slot) {
        curSlot = s
        pv.invalidate()
        val a = s.acro
        when (s.type) {
            FormField.CHECK -> change {
                if (a != null) fills[s.id] = if (slotValue(s) == "1") "" else "1"
                else if (!marks.removeAll { it.field == s.id }) {
                    val m = Mark(Mark.CHECK, s.page); m.x = s.x; m.y = s.y; m.w = s.w; m.h = s.h; m.field = s.id; marks.add(m)
                }
            }
            FormField.RADIO -> change { for (f in acro) if (f.name == a?.name && f.type == FormField.RADIO) fills[f.id] = if (f.id == s.id) "1" else "" }
            FormField.CHOICE -> {
                val opts = a?.options ?: emptyList()
                if (opts.isEmpty()) Ui.prompt(ctx, a?.name ?: "Value", slotValue(s)) { v -> setSlotText(s, v) }
                else Ui.choose(ctx, a?.name, opts + "(clear)") { i -> setSlotText(s, if (i < opts.size) opts[i] else "") }
            }
            FormField.SIGN, AREA -> Ui.choose(ctx, if (s.type == AREA) "Fill this area with" else "Signature field", listOf("Draw signature", "Insert image", "Type text", "Clear")) { i ->
                when (i) {
                    0 -> addSignature(s)
                    1 -> addImage(s)
                    2 -> Ui.prompt(ctx, "Text", marks.firstOrNull { it.field == s.id && it.kind == Mark.TEXT }?.text ?: "", true) { v ->
                        if (a != null) { // a signature widget cannot hold text: type onto the page instead
                            change {
                                marks.removeAll { it.field == s.id }
                                if (v.isNotEmpty()) { val m = Mark(Mark.TEXT, s.page); m.field = s.id; m.text = v; m.size = 11f; m.x = s.x + 3; m.y = s.y + 3; m.w = s.w - 6; marks.add(m) }
                            }
                        } else setSlotText(s, v)
                    }
                    3 -> change { marks.removeAll { it.field == s.id } }
                }
            }
            else -> Ui.prompt(ctx, a?.name?.ifEmpty { null } ?: "Fill in", slotValue(s), a?.multiline == true || s.h > 30f * unit(s.page)) { v -> setSlotText(s, v) }
        }
    }

    // ---- state / export ------------------------------------------------------------------------

    override fun snapshot(cb: (ByteArray, File?, Collection<String>) -> Unit) {
        cb(stateJson().toByteArray(), if (based) work else null, marks.mapNotNull { it.img }.toSet())
    }

    override fun restore(payload: ByteArray, base: File?) {
        val json = String(payload)
        undo.clear(); redo.clear()
        if (base != null) {
            val copy = File(Store.openDir(ctx), "${System.nanoTime()}.pdf")
            rh.post { base.copyTo(copy, true); open(copy); Ui.ui { based = true; loadJson(json) } }
        } else if (based) {
            rh.post { open(doc.file); Ui.ui { based = false; loadJson(json) } }
        } else loadJson(json)
    }

    override fun formats(): List<Format> =
        if (isPdf) listOf(Format("PDF", "pdf", "application/pdf"))
        else {
            val jpg = doc.name.lowercase().let { it.endsWith(".jpg") || it.endsWith(".jpeg") }
            listOf(if (jpg) Format("JPEG image", "jpg", "image/jpeg") else Format("PNG image", "png", "image/png"), Format("PDF", "pdf", "application/pdf"))
        }

    private fun pdfImage(id: String): PdfImage? {
        val f = Store.imageFile(ctx, id)
        if (!f.exists()) return null
        if (id.endsWith(".jpg")) {
            val o = android.graphics.BitmapFactory.Options(); o.inJustDecodeBounds = true
            android.graphics.BitmapFactory.decodeFile(f.path, o)
            if (o.outWidth > 0) return PdfImage(o.outWidth, o.outHeight, null, null, f.readBytes())
        }
        val bmp = Images.decodeFile(f, 2000) ?: return null
        val w = bmp.width; val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        bmp.recycle()
        val rgb = ByteArray(w * h * 3)
        val alpha = ByteArray(w * h)
        var opaque = true
        for (i in px.indices) {
            val c = px[i]
            rgb[i * 3] = (c shr 16).toByte(); rgb[i * 3 + 1] = (c shr 8).toByte(); rgb[i * 3 + 2] = c.toByte()
            val a = c ushr 24
            alpha[i] = a.toByte()
            if (a != 255) opaque = false
        }
        return PdfImage(w, h, rgb, if (opaque) null else alpha, null)
    }

    /** Marks ready for the PDF writer: text outside the built-in font's repertoire becomes an image. */
    private fun prepareMarks(): List<Mark> {
        val out = ArrayList<Mark>()
        for (m in marks) {
            val t = m.text
            if (m.kind == Mark.TEXT && t != null && !Helv.canEncode(t)) {
                val k = 4f
                val paint = Paint(Paint.ANTI_ALIAS_FLAG)
                paint.textSize = m.size * k; paint.color = m.color; paint.typeface = Typeface.SANS_SERIF
                val lines = t.split('\n')
                var wpx = 1f
                for (l in lines) wpx = Math.max(wpx, paint.measureText(l))
                val hpx = lines.size * m.size * Mark.LEADING * k + m.size * k * 0.3f
                val bmp = Bitmap.createBitmap(Math.ceil(wpx.toDouble()).toInt().coerceIn(1, 4000), Math.ceil(hpx.toDouble()).toInt().coerceIn(1, 4000), Bitmap.Config.ARGB_8888)
                val cv = Canvas(bmp)
                for ((i, l) in lines.withIndex()) cv.drawText(l, 0f, (m.size * Mark.ASCENT + i * m.size * Mark.LEADING) * k, paint)
                val bo = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.PNG, 100, bo)
                val im = Mark(Mark.IMAGE, m.page)
                im.x = m.x; im.y = m.y; im.w = bmp.width / k; im.h = bmp.height / k
                im.img = Store.putImage(ctx, bo.toByteArray(), "png")
                bmp.recycle()
                out.add(im)
            } else out.add(m)
        }
        return out
    }

    override fun export(ext: String, out: File, cb: (Boolean, String?) -> Unit) {
        if (pageW.isEmpty()) { cb(false, failed ?: "Nothing to export"); return }
        val f = HashMap(fills)
        val src = work
        val snapshot = marks.map { it.copy() }
        rh.post {
            try {
                var note: String? = null
                if (isPdf) {
                    val prepared = prepareMarks()
                    if (prepared.isEmpty() && f.isEmpty()) src.copyTo(out, true)
                    else try {
                        PdfFile.open(src).use { PdfEdit.apply(it, out, prepared, ::pdfImage, f) }
                    } catch (t: Throwable) {
                        Ui.log("vector save unavailable ($t); flattening")
                        flattenPdf(out, snapshot)
                        note = "This PDF could not be updated in place, so the pages were saved as images with your edits."
                    }
                } else if (ext == "pdf") flattenPdf(out, snapshot)
                else {
                    val base = image ?: throw java.io.IOException("no image")
                    val bmp = base.copy(Bitmap.Config.ARGB_8888, true)
                    val cv = Canvas(bmp)
                    for (m in snapshot) drawMark(cv, m, 1f)
                    FileOutputStream(out).use { bmp.compress(if (ext == "jpg") Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG, 92, it) }
                    bmp.recycle()
                }
                val n = note
                Ui.ui { cb(true, n) }
            } catch (t: Throwable) {
                Ui.log("export failed: $t")
                Ui.ui { cb(false, t.message ?: t.toString()) }
            }
        }
    }

    /** Fallback writer: every page as a 150-dpi picture with the overlay drawn on top. Render thread. */
    private fun flattenPdf(out: File, list: List<Mark>) {
        val pd = PdfDocument()
        try {
            for (i in pageW.indices) {
                val k = if (isPdf) 1f else Math.min(1f, 842f / Math.max(pageW[i], pageH[i]))
                val pw = Math.max(1, Math.round(pageW[i] * k))
                val ph = Math.max(1, Math.round(pageH[i] * k))
                val page = pd.startPage(PdfDocument.PageInfo.Builder(pw, ph, i + 1).create())
                val s = if (isPdf) 150f / 72f else 1f / k
                val bw = Math.max(1, Math.round(pw * s)).coerceAtMost(3000)
                val bh = Math.max(1, Math.round(ph * s)).coerceAtMost(4200)
                val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(Color.WHITE)
                val m = Matrix(); m.setScale(bw / pageW[i], bh / pageH[i])
                renderInto(i, bmp, null, m)
                page.canvas.drawBitmap(bmp, null, RectF(0f, 0f, pw.toFloat(), ph.toFloat()), Paint(Paint.FILTER_BITMAP_FLAG))
                bmp.recycle()
                page.canvas.save()
                page.canvas.scale(k, k)
                for (mk in list) if (mk.page == i) drawMark(page.canvas, mk, 1f)
                for (f in acro) if (f.page == i) drawFill(page.canvas, f)
                page.canvas.restore()
                pd.finishPage(page)
            }
            FileOutputStream(out).use { pd.writeTo(it) }
        } finally {
            pd.close()
        }
    }

    override fun print(name: String) {
        val tmp = Store.tmp(ctx, "print.pdf")
        export("pdf", tmp) { ok, _ ->
            if (!ok) { Ui.toast(ctx, "Could not prepare the document for printing"); return@export }
            val pages = pageW.size
            val pm = ctx.getSystemService(Context.PRINT_SERVICE) as PrintManager
            pm.print(name, object : PrintDocumentAdapter() {
                override fun onLayout(o: PrintAttributes?, n: PrintAttributes?, cs: CancellationSignal?, cb: LayoutResultCallback, extras: Bundle?) {
                    cb.onLayoutFinished(PrintDocumentInfo.Builder(name).setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).setPageCount(pages).build(), true)
                }
                override fun onWrite(pagesR: Array<out PageRange>?, dest: ParcelFileDescriptor, cs: CancellationSignal?, cb: WriteResultCallback) {
                    try {
                        FileOutputStream(dest.fileDescriptor).use { o -> tmp.inputStream().use { it.copyTo(o) } }
                        cb.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
                    } catch (t: Throwable) { cb.onWriteFailed(t.message) }
                }
            }, null)
        }
    }

    override fun find(query: String, forward: Boolean) {
        if (!isPdf || Build.VERSION.SDK_INT < 35) { Ui.toast(ctx, if (isPdf) "Searching inside PDFs needs Android 15 or newer" else "Images contain no searchable text"); return }
        val start = pv.currentPage() + (if (pv.hits.isNotEmpty()) (if (forward) 1 else -1) else 0)
        val n = pageW.size
        rh.post {
            var found = -1
            var rects: List<RectF> = emptyList()
            try {
                val r = renderer
                if (r != null) for (k in 0 until n) {
                    val p = (((start + (if (forward) k else -k)) % n) + n) % n
                    val res = r.openPage(p).use { it.searchText(query) }
                    if (res.isNotEmpty()) { found = p; rects = res.flatMap { it.bounds }; break }
                }
            } catch (t: Throwable) {
                Ui.log("search failed: $t")
            }
            Ui.ui {
                if (found < 0) { pv.hits = emptyList(); Ui.toast(ctx, "Not found") }
                else { pv.hits = rects; pv.hitPage = found; val f = rects[0]; pv.reveal(found, f.left, f.top, f.width(), f.height()) }
                pv.invalidate()
            }
        }
    }

    override fun setNight(on: Boolean) { pv.night = on; pv.invalidate() }

    // ---- drawing ------------------------------------------------------------------------------

    private val mp = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mpath = Path()

    private fun markBitmap(id: String): Bitmap? {
        if (!bitmaps.containsKey(id)) {
            if (bitmaps.size > 12) { bitmaps.values.forEach { it?.recycle() }; bitmaps.clear() }
            bitmaps[id] = Images.decodeFile(Store.imageFile(ctx, id), 1024)
        }
        return bitmaps[id]
    }

    /** Draws a mark in page units; [k] is unused scaling hook kept at 1 (the canvas is already scaled). */
    private fun drawMark(cv: Canvas, m: Mark, k: Float) {
        mp.reset(); mp.isAntiAlias = true
        mp.color = m.color
        when (m.kind) {
            Mark.HIGHLIGHT -> { mp.style = Paint.Style.FILL; mp.alpha = 102; mp.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.MULTIPLY); cv.drawRect(m.x, m.y, m.x + m.w, m.y + m.h, mp); mp.xfermode = null }
            Mark.COVER -> { mp.style = Paint.Style.FILL; cv.drawRect(m.x, m.y, m.x + m.w, m.y + m.h, mp) }
            Mark.BOX -> { mp.style = Paint.Style.STROKE; mp.strokeWidth = m.size * k; cv.drawRect(m.x, m.y, m.x + m.w, m.y + m.h, mp) }
            Mark.LINE, Mark.INK -> {
                val p = m.pts ?: return
                if (p.size < 4) return
                mp.style = Paint.Style.STROKE; mp.strokeWidth = m.size * k; mp.strokeCap = Paint.Cap.ROUND; mp.strokeJoin = Paint.Join.ROUND
                mpath.rewind(); mpath.moveTo(p[0], p[1])
                var i = 2
                while (i + 1 < p.size) { mpath.lineTo(p[i], p[i + 1]); i += 2 }
                cv.drawPath(mpath, mp)
            }
            Mark.CHECK -> {
                mp.style = Paint.Style.STROKE; mp.strokeWidth = Math.max(1f, Math.min(m.w, m.h) * 0.12f); mp.strokeCap = Paint.Cap.ROUND; mp.strokeJoin = Paint.Join.ROUND
                mpath.rewind(); mpath.moveTo(m.x + m.w * 0.2f, m.y + m.h * 0.55f); mpath.lineTo(m.x + m.w * 0.42f, m.y + m.h * 0.78f); mpath.lineTo(m.x + m.w * 0.82f, m.y + m.h * 0.24f)
                cv.drawPath(mpath, mp)
            }
            Mark.TEXT -> {
                val t = m.text ?: return
                mp.style = Paint.Style.FILL; mp.textSize = m.size; mp.typeface = Typeface.SANS_SERIF
                for ((i, line) in Helv.wrap(t, m.size, m.w).withIndex()) {
                    if (line.isEmpty()) continue
                    // match the width the PDF font will have so line ends agree between screen and file
                    val nat = mp.measureText(line)
                    mp.textScaleX = if (nat > 0 && Helv.canEncode(line)) (Helv.width(line, m.size) / nat).coerceIn(0.8f, 1.25f) else 1f
                    cv.drawText(line, m.x, m.y + m.size * Mark.ASCENT + i * m.size * Mark.LEADING, mp)
                    mp.textScaleX = 1f
                }
            }
            Mark.IMAGE -> {
                val b = m.img?.let { markBitmap(it) }
                if (b != null) { mp.isFilterBitmap = true; cv.drawBitmap(b, null, RectF(m.x, m.y, m.x + m.w, m.y + m.h), mp) }
                else { mp.style = Paint.Style.STROKE; mp.strokeWidth = 1f; mp.color = Color.GRAY; cv.drawRect(m.x, m.y, m.x + m.w, m.y + m.h, mp) }
            }
        }
    }

    /** Shows a pending AcroForm value on top of the widget's old appearance. */
    private fun drawFill(cv: Canvas, f: FormField) {
        val v = fills[f.id] ?: return
        mp.reset(); mp.isAntiAlias = true
        mp.style = Paint.Style.FILL; mp.color = 0xFFF4F7FF.toInt()
        cv.drawRect(f.x + 1, f.y + 1, f.x + f.w - 1, f.y + f.h - 1, mp)
        mp.color = Color.BLACK
        when (f.type) {
            FormField.CHECK, FormField.RADIO -> if (v == "1") {
                mp.style = Paint.Style.STROKE; mp.strokeWidth = Math.max(1f, Math.min(f.w, f.h) * 0.12f); mp.strokeCap = Paint.Cap.ROUND
                mpath.rewind(); mpath.moveTo(f.x + f.w * 0.2f, f.y + f.h * 0.55f); mpath.lineTo(f.x + f.w * 0.42f, f.y + f.h * 0.78f); mpath.lineTo(f.x + f.w * 0.82f, f.y + f.h * 0.24f)
                cv.drawPath(mpath, mp)
            }
            else -> {
                mp.typeface = Typeface.SANS_SERIF
                if (f.multiline) {
                    mp.textSize = 10f
                    for ((i, line) in Helv.wrap(v, 10f, f.w - 4).withIndex()) cv.drawText(line, f.x + 2, f.y + 2 + 9f + i * 11.5f, mp)
                } else {
                    var size = (f.h - 4f).coerceIn(4f, 12f)
                    while (size > 4f && Helv.width(v, size) > f.w - 4) size -= 0.5f
                    mp.textSize = size
                    cv.drawText(v.replace('\n', ' '), f.x + 2, f.y + f.h - (f.h - size * 0.72f) / 2f, mp)
                }
            }
        }
    }

    private fun bounds(m: Mark): RectF {
        val p = m.pts
        if ((m.kind == Mark.INK || m.kind == Mark.LINE) && p != null && p.size >= 2) {
            val r = RectF(p[0], p[1], p[0], p[1])
            var i = 2
            while (i + 1 < p.size) { r.union(p[i], p[i + 1]); i += 2 }
            r.inset(-m.size - 3, -m.size - 3)
            return r
        }
        if (m.kind == Mark.TEXT) {
            val lines = Helv.wrap(m.text ?: "", m.size, m.w)
            var w = 4f
            for (l in lines) w = Math.max(w, Helv.width(l, m.size))
            return RectF(m.x, m.y, m.x + w, m.y + lines.size * m.size * Mark.LEADING)
        }
        return RectF(m.x, m.y, m.x + m.w, m.y + m.h)
    }

    private fun move(m: Mark, dx: Float, dy: Float) {
        m.x += dx; m.y += dy
        val p = m.pts
        if (p != null) { var i = 0; while (i + 1 < p.size) { p[i] += dx; p[i + 1] += dy; i += 2 } }
    }

    // ---- the scrolling page view ----------------------------------------------------------------

    private inner class PagedView(c: Context) : View(c) {
        var night = false
        var hits: List<RectF> = emptyList()
        var hitPage = -1
        private var zoom = 1f
        private var sx = 0f
        private var sy = 0f
        private var top = FloatArray(0)
        private var s1 = FloatArray(0)
        private var total = 0f
        private val pad = Ui.dp(c, 8f).toFloat()
        private val base = LinkedHashMap<Int, Bitmap>(8, 0.75f, true)
        private val pending = HashSet<Int>()
        private var patch: Bitmap? = null
        private var patchZoom = 0f
        private var patchSx = 0f
        private var patchSy = 0f
        private var patchBusy = false
        private val scroller = OverScroller(c)
        private val bp = Paint(Paint.FILTER_BITMAP_FLAG)
        private val nightFilter = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(-1f, 0f, 0f, 0f, 255f, 0f, -1f, 0f, 0f, 255f, 0f, 0f, -1f, 0f, 255f, 0f, 0f, 0f, 1f, 0f)))
        private val op = Paint(Paint.ANTI_ALIAS_FLAG)
        private var live: Mark? = null
        private var downX = 0f
        private var downY = 0f
        private var drawing = false
        private var dragging = false
        private var multi = false
        private var flash = 0
        private var flashUntil = 0L
        private val budget = Math.max(24, (Runtime.getRuntime().maxMemory() / (1024 * 1024) / 5).toInt()) * 1024L * 1024L
        private val patchTask = Runnable { renderPatch() }

        private val scale = ScaleGestureDetector(c, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                val nz = (zoom * d.scaleFactor).coerceIn(0.3f, 8f)
                val f = nz / zoom
                // keep the content under the fingers in place
                sx = (sx + d.focusX - offX()) * f - d.focusX + offXFor(nz)
                sy = (sy + d.focusY) * f - d.focusY
                zoom = nz
                clamp(); changed()
                return true
            }
        })

        private val gestures = GestureDetector(c, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean { scroller.forceFinished(true); return true }
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                if (drawing) return true
                val sel = selected
                if (dragging && sel != null && sel.page < s1.size) {
                    val s = s1[sel.page] * zoom
                    move(sel, -dx / s, -dy / s)
                    invalidate()
                    return true
                }
                sx += dx; sy += dy
                clamp(); changed()
                return true
            }
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (drawing || dragging) return true
                scroller.fling(sx.toInt(), sy.toInt(), -vx.toInt(), -vy.toInt(), 0, maxX().toInt(), 0, maxY().toInt())
                postInvalidateOnAnimation()
                return true
            }
            override fun onSingleTapUp(e: MotionEvent): Boolean { if (mode != VIEW) tap(e.x, e.y); return true }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (mode != VIEW) return false
                val nz = if (zoom < 1.4f) 2.2f else 1f
                val f = nz / zoom
                sx = (sx + e.x - offX()) * f - e.x + offXFor(nz); sy = (sy + e.y) * f - e.y
                zoom = nz; clamp(); changed()
                return true
            }
        })

        fun release() {
            Ui.cancel(patchTask)
            base.values.forEach { it.recycle() }; base.clear()
            patch?.recycle(); patch = null
            bitmaps.values.forEach { it?.recycle() }; bitmaps.clear()
        }

        fun reset() {
            base.values.forEach { it.recycle() }; base.clear(); pending.clear()
            patchZoom = 0f
            layoutPages()
            clamp(); changed()
            host.toolsChanged()
        }

        private fun layoutPages() {
            val n = pageW.size
            top = FloatArray(n); s1 = FloatArray(n)
            var acc = pad
            val avail = Math.max(1f, width - 2 * pad)
            for (i in 0 until n) {
                s1[i] = avail / pageW[i]
                // a single image should fit the window entirely
                if (!isPdf && height > 0) s1[i] = Math.min(s1[i], (height - 2 * pad) / pageH[i])
                top[i] = acc
                acc += pageH[i] * s1[i] + pad
            }
            total = acc
        }

        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
            val anchor = if (total > 0) sy / (total * zoom) else 0f
            base.values.forEach { it.recycle() }; base.clear(); pending.clear()
            patch?.recycle(); patch = null; patchZoom = 0f
            layoutPages()
            sy = anchor * total * zoom
            clamp(); changed()
        }

        private fun contentW() = width * zoom
        private fun offXFor(z: Float) = if (z < 1f) (width - width * z) / 2 else 0f
        private fun offX() = offXFor(zoom)
        private fun maxX() = Math.max(0f, contentW() - width)
        private fun maxY() = Math.max(0f, total * zoom - height)
        private fun clamp() { sx = sx.coerceIn(0f, maxX()); sy = sy.coerceIn(0f, maxY()) }
        private fun left(i: Int) = offX() - sx + (pad + (width - 2 * pad - pageW[i] * s1[i]) / 2) * zoom
        private fun topOf(i: Int) = top[i] * zoom - sy
        private fun ps(i: Int) = s1[i] * zoom

        private fun changed() {
            if (pageW.isNotEmpty()) host.status("${currentPage() + 1} / ${pageW.size}")
            Ui.cancel(patchTask)
            Ui.later(140, patchTask)
            invalidate()
        }

        fun currentPage(): Int {
            val mid = sy + height / 2f
            for (i in top.indices) if (mid < (top[i] + pageH[i] * s1[i] + pad) * zoom) return i
            return Math.max(0, top.size - 1)
        }

        fun scrollToPage(i: Int) {
            if (top.isEmpty()) return
            sy = (top[i.coerceIn(0, top.size - 1)] - pad) * zoom
            clamp(); changed()
        }

        fun viewCenterOnPage(i: Int): FloatArray =
            floatArrayOf(((width / 2f - left(i)) / ps(i)).coerceIn(0f, pageW[i]), ((height / 2f - topOf(i)) / ps(i)).coerceIn(0f, pageH[i]))

        /** Scrolls so that the given page rectangle is comfortably visible. */
        fun reveal(page: Int, x: Float, y: Float, w: Float, h: Float) {
            if (page !in top.indices) return
            val s = ps(page)
            val cy = top[page] * zoom + (y + h / 2) * s
            val cx = (pad + (width - 2 * pad - pageW[page] * s1[page]) / 2) * zoom + (x + w / 2) * s
            if (cy - sy < height * 0.15f || cy - sy > height * 0.6f) sy = cy - height * 0.35f
            if (zoom > 1f && (cx - sx < width * 0.1f || cx - sx > width * 0.9f)) sx = cx - width / 2f
            clamp(); changed()
        }

        fun flashColor(c: Int) { flash = c; flashUntil = System.currentTimeMillis() + 700; invalidate(); postInvalidateDelayed(720) }

        private fun pageAt(y: Float): Int {
            for (i in top.indices) if (y < topOf(i) + pageH[i] * ps(i) + pad * zoom / 2) return i
            return top.size - 1
        }

        private fun requestBase(i: Int) {
            if (pending.contains(i) || width <= 0) return
            pending.add(i)
            val w = Math.max(16, Math.min(Math.round(pageW[i] * s1[i]), 1400))
            val h = Math.max(16, Math.round(w * pageH[i] / pageW[i]))
            rh.post {
                var bmp: Bitmap? = null
                try {
                    bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    val m = Matrix(); m.setScale(w / pageW[i], h / pageH[i])
                    renderInto(i, bmp, null, m)
                } catch (t: Throwable) {
                    Ui.log("render page $i failed: $t")
                }
                val b = bmp
                Ui.ui {
                    pending.remove(i)
                    if (b != null && i < pageW.size) {
                        base.put(i, b)?.recycle()
                        var bytes = 0L
                        for (v in base.values) bytes += v.byteCount
                        val it = base.entries.iterator()
                        while (bytes > budget && base.size > 2 && it.hasNext()) { val e = it.next(); if (e.key == i) continue; bytes -= e.value.byteCount; e.value.recycle(); it.remove() }
                        invalidate()
                    } else b?.recycle()
                }
            }
        }

        /** Sharp rendering of exactly what is on screen, used when zoomed in beyond the cached page bitmaps. */
        private fun renderPatch() {
            if (zoom <= 1.15f || patchBusy || width <= 0 || height <= 0 || pageW.isEmpty()) return
            if (patchZoom == zoom && patchSx == sx && patchSy == sy) return
            patchBusy = true
            val z = zoom; val px = sx; val py = sy
            val vw = width; val vh = height
            val jobs = ArrayList<Pair<Int, FloatArray>>()
            for (i in pageW.indices) {
                val t = topOf(i); val s = ps(i)
                if (t + pageH[i] * s < 0 || t > vh) continue
                jobs.add(Pair(i, floatArrayOf(left(i), t, s)))
            }
            rh.post {
                var bmp: Bitmap? = null
                try {
                    bmp = Bitmap.createBitmap(vw, vh, Bitmap.Config.ARGB_8888)
                    for ((i, g) in jobs) {
                        val clip = Rect(Math.max(0, g[0].toInt()), Math.max(0, g[1].toInt()), Math.min(vw, Math.ceil((g[0] + pageW[i] * g[2]).toDouble()).toInt()), Math.min(vh, Math.ceil((g[1] + pageH[i] * g[2]).toDouble()).toInt()))
                        if (clip.width() <= 0 || clip.height() <= 0) continue
                        Canvas(bmp).apply { clipRect(clip); drawColor(Color.WHITE) }
                        val m = Matrix(); m.setScale(g[2], g[2]); m.postTranslate(g[0], g[1])
                        renderInto(i, bmp, clip, m)
                    }
                } catch (t: Throwable) {
                    Ui.log("patch failed: $t"); bmp?.recycle(); bmp = null
                }
                val b = bmp
                Ui.ui {
                    patchBusy = false
                    if (b != null) { patch?.recycle(); patch = b; patchZoom = z; patchSx = px; patchSy = py; invalidate() }
                    if (zoom != z || sx != px || sy != py) { Ui.cancel(patchTask); Ui.later(60, patchTask) }
                }
            }
        }

        override fun computeScroll() {
            if (scroller.computeScrollOffset()) {
                sx = scroller.currX.toFloat(); sy = scroller.currY.toFloat()
                clamp(); changed()
            }
        }

        override fun onDraw(cv: Canvas) {
            cv.drawColor(0xFF2A2E33.toInt())
            val msg = failed
            if (pageW.isEmpty()) {
                op.reset(); op.isAntiAlias = true; op.color = Ui.DIM; op.textSize = Ui.dp(context, 14f).toFloat(); op.textAlign = Paint.Align.CENTER
                cv.drawText(msg ?: "Loading…", width / 2f, height / 2f, op)
                return
            }
            bp.colorFilter = if (night) nightFilter else null
            val usePatch = patch != null && patchZoom == zoom && zoom > 1.15f
            for (i in pageW.indices) {
                val t = topOf(i); val s = ps(i); val l = left(i)
                val b = t + pageH[i] * s
                if (b < 0 || t > height) continue
                val dst = RectF(l, t, l + pageW[i] * s, b)
                val bmp = base[i]
                if (bmp != null && !bmp.isRecycled) cv.drawBitmap(bmp, null, dst, bp)
                else { op.reset(); op.color = if (night) Color.BLACK else Color.WHITE; cv.drawRect(dst, op); requestBase(i) }
            }
            val p = patch
            if (usePatch && p != null && !p.isRecycled) cv.drawBitmap(p, patchSx - sx, patchSy - sy, bp)
            for (i in pageW.indices) {
                val t = topOf(i); val s = ps(i); val l = left(i)
                if (t + pageH[i] * s < 0 || t > height) continue
                cv.save()
                cv.clipRect(l, t, l + pageW[i] * s, t + pageH[i] * s)
                cv.translate(l, t); cv.scale(s, s)
                for (f in acro) if (f.page == i) drawFill(cv, f)
                for (m in marks) if (m.page == i) drawMark(cv, m, 1f)
                live?.let { if (it.page == i) drawMark(cv, it, 1f) }
                if (i == hitPage) { op.reset(); op.style = Paint.Style.FILL; op.color = 0x66FF9800; for (r in hits) cv.drawRect(r, op) }
                if (mode == FILL) {
                    for (sl in slots(i)) {
                        val cur = curSlot?.id == sl.id
                        val filled = slotValue(sl).isNotEmpty()
                        op.reset(); op.isAntiAlias = true
                        op.style = Paint.Style.FILL; op.color = if (filled) 0x1400A050 else 0x30F2B33D
                        cv.drawRect(sl.x, sl.y, sl.x + sl.w, sl.y + sl.h, op)
                        op.style = Paint.Style.STROKE; op.strokeWidth = (if (cur) 2.5f else 1.2f) / s * resources.displayMetrics.density
                        op.color = if (cur) 0xFFE65100.toInt() else if (filled) 0xFF2E7D32.toInt() else Ui.ACCENT
                        cv.drawRect(sl.x, sl.y, sl.x + sl.w, sl.y + sl.h, op)
                    }
                }
                val sel = selected
                if (sel != null && sel.page == i) {
                    op.reset(); op.style = Paint.Style.STROKE; op.strokeWidth = 1.5f / s * resources.displayMetrics.density; op.color = 0xFF1E88E5.toInt()
                    val r = bounds(sel); r.inset(-2f, -2f)
                    cv.drawRect(r, op)
                }
                cv.restore()
            }
            if (System.currentTimeMillis() < flashUntil) {
                op.reset(); op.isAntiAlias = true; op.style = Paint.Style.FILL; op.color = flash
                val r = Ui.dp(context, 18f).toFloat()
                cv.drawCircle(width - r * 1.6f, r * 1.6f, r, op)
                op.style = Paint.Style.STROKE; op.color = Ui.LINE; op.strokeWidth = 2f
                cv.drawCircle(width - r * 1.6f, r * 1.6f, r, op)
            }
        }

        private fun toPage(x: Float, y: Float, i: Int) = floatArrayOf((x - left(i)) / ps(i), (y - topOf(i)) / ps(i))

        private fun tap(x: Float, y: Float) {
            if (pageW.isEmpty()) return
            val i = pageAt(y)
            val p = toPage(x, y, i)
            if (mode == FILL) {
                val slop = 4f / ps(i) * resources.displayMetrics.density
                val hit = slots(i).filter { p[0] >= it.x - slop && p[0] <= it.x + it.w + slop && p[1] >= it.y - slop && p[1] <= it.y + it.h + slop }.minByOrNull { it.w * it.h }
                if (hit != null) editSlot(hit)
                return
            }
            when (tool) {
                HAND -> {
                    val hit = marks.lastOrNull { it.page == i && bounds(it).contains(p[0], p[1]) }
                    if (hit != null && hit === selected && hit.kind == Mark.TEXT) editText(hit)
                    selected = hit
                    host.toolsChanged(); invalidate()
                }
                TEXT -> Ui.prompt(context, "Add text", "", true) { s ->
                    if (s.isNotEmpty()) change {
                        val m = Mark(Mark.TEXT, i); m.x = p[0]; m.y = p[1] - textSize * unit(i) * 0.6f; m.size = textSize * unit(i); m.color = color; m.text = s
                        marks.add(m)
                    }
                }
                CHECK -> change {
                    val sz = 12f * unit(i)
                    val m = Mark(Mark.CHECK, i); m.x = p[0] - sz / 2; m.y = p[1] - sz / 2; m.w = sz; m.h = sz; m.color = color
                    marks.add(m)
                }
            }
        }

        /** Colour of the page just outside a rectangle, so a cover box blends into its surroundings. */
        private fun sampleAround(i: Int, r: RectF): Int {
            val b = base[i] ?: return Color.WHITE
            if (b.isRecycled) return Color.WHITE
            val kx = b.width / pageW[i]; val ky = b.height / pageH[i]
            var rs = 0L; var gs = 0L; var bs = 0L; var n = 0
            val pts = arrayOf(floatArrayOf(r.left - 2, r.top - 2), floatArrayOf(r.right + 2, r.top - 2), floatArrayOf(r.left - 2, r.bottom + 2), floatArrayOf(r.right + 2, r.bottom + 2), floatArrayOf(r.centerX(), r.top - 2), floatArrayOf(r.centerX(), r.bottom + 2))
            for (pt in pts) {
                val x = (pt[0] * kx).toInt(); val y = (pt[1] * ky).toInt()
                if (x < 0 || y < 0 || x >= b.width || y >= b.height) continue
                val c = b.getPixel(x, y)
                rs += Color.red(c); gs += Color.green(c); bs += Color.blue(c); n++
            }
            return if (n == 0) Color.WHITE else Color.rgb((rs / n).toInt(), (gs / n).toInt(), (bs / n).toInt())
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (pageW.isEmpty()) return true
            scale.onTouchEvent(e)
            if (e.pointerCount > 1) { multi = true; if (drawing) { drawing = false; live = null; invalidate() } }
            val draws = mode == EDIT && (tool == HL || tool == PEN || tool == ERASE || tool == RETYPE || tool == BOX || tool == LINE)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    multi = false; downX = e.x; downY = e.y
                    parent?.requestDisallowInterceptTouchEvent(true)
                    dragging = false
                    val sel = selected
                    if (mode == EDIT && tool == HAND && sel != null) {
                        val p = toPage(e.x, e.y, sel.page.coerceIn(0, pageW.size - 1))
                        val r = bounds(sel); r.inset(-6f, -6f)
                        if (r.contains(p[0], p[1])) { dragging = true; undo.add(stateJson()); redo.clear() }
                    }
                    if (draws) {
                        val i = pageAt(e.y)
                        val p = toPage(e.x, e.y, i)
                        val m = Mark(when (tool) { HL -> Mark.HIGHLIGHT; PEN -> Mark.INK; BOX -> Mark.BOX; LINE -> Mark.LINE; else -> Mark.COVER }, i)
                        m.color = when (tool) { HL -> hlColor; ERASE, RETYPE -> Color.WHITE; else -> color }
                        m.size = stroke * unit(i)
                        m.x = p[0]; m.y = p[1]
                        if (tool == PEN || tool == LINE) m.pts = floatArrayOf(p[0], p[1], p[0], p[1])
                        live = m; drawing = true
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    val m = live
                    if (drawing && m != null && !multi) {
                        val p = toPage(e.x, e.y, m.page)
                        when (m.kind) {
                            Mark.INK -> { val o = m.pts!!; if (Math.abs(p[0] - o[o.size - 2]) + Math.abs(p[1] - o[o.size - 1]) > 0.7f * unit(m.page)) m.pts = o + floatArrayOf(p[0], p[1]) }
                            Mark.LINE -> { m.pts!![2] = p[0]; m.pts!![3] = p[1] }
                            else -> { m.w = p[0] - m.x; m.h = p[1] - m.y }
                        }
                        invalidate()
                        return true
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val m = live
                    if (drawing && m != null && e.actionMasked == MotionEvent.ACTION_UP && !multi) {
                        drawing = false; live = null
                        if (m.kind != Mark.INK && m.kind != Mark.LINE) {
                            if (m.w < 0) { m.x += m.w; m.w = -m.w }
                            if (m.h < 0) { m.y += m.h; m.h = -m.h }
                        }
                        val moved = Math.abs(e.x - downX) + Math.abs(e.y - downY) > Ui.dp(context, 6f)
                        if (moved) {
                            if (tool == ERASE || tool == RETYPE) m.color = sampleAround(m.page, RectF(m.x, m.y, m.x + m.w, m.y + m.h))
                            change { marks.add(m) }
                            if (tool == RETYPE) Ui.prompt(context, "Replacement text", "", true) { s ->
                                if (s.isNotEmpty()) change {
                                    val t = Mark(Mark.TEXT, m.page)
                                    var size = Math.min(m.h * 0.8f, 14f * unit(m.page)).coerceAtLeast(5f * unit(m.page))
                                    if (!s.contains('\n')) while (size > 5f * unit(m.page) && Helv.width(s, size) > m.w) size -= 0.5f
                                    t.size = size; t.x = m.x + 1; t.y = m.y + Math.max(0f, (m.h - size * Mark.LEADING) / 2); t.color = color; t.text = s
                                    if (s.contains('\n') || Helv.width(s, size) > m.w) t.w = m.w
                                    marks.add(t)
                                }
                            }
                        }
                        invalidate()
                        return true
                    }
                    if (dragging) { dragging = false; host.markDirty() }
                    drawing = false; live = null
                }
            }
            if (!drawing && !scale.isInProgress) gestures.onTouchEvent(e)
            return true
        }
    }
}
