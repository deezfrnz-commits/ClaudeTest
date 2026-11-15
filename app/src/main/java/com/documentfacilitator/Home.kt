package com.documentfacilitator

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.TextPaint
import android.text.TextUtils
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.documentfacilitator.core.DocType

/** Honeycomb launcher: every action and recent document is one hexagon, the rest of the comb is empty cells. */
class HexGridView(c: Context) : View(c) {
    class Tile(val label: String, val sub: String?, val accent: Boolean, val onClick: () -> Unit, val onLong: (() -> Unit)? = null)

    var tiles: List<Tile> = emptyList()
        set(v) { field = v; scroll = 0f; invalidate() }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = Ui.dp(c, 1.5f).toFloat() }
    private val big = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER }
    private val small = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val path = Path()
    private var cols = 3
    private var r = 1f
    private var scroll = 0f
    private var pressed = -1

    private val gestures = GestureDetector(c, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean { pressed = tileAt(e.x, e.y); invalidate(); return true }
        override fun onSingleTapUp(e: MotionEvent): Boolean { val i = tileAt(e.x, e.y); if (i >= 0) tiles[i].onClick(); return true }
        override fun onLongPress(e: MotionEvent) { val i = tileAt(e.x, e.y); if (i >= 0) tiles[i].onLong?.invoke() }
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            pressed = -1
            scroll = (scroll + dy).coerceIn(0f, maxScroll())
            invalidate()
            return true
        }
    })

    private fun colW() = r * 1.7320508f
    private fun rowsNeeded() = (tiles.size + cols - 1) / cols
    private fun maxScroll() = Math.max(0f, rowsNeeded() * 1.5f * r + r - height)

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        cols = (w / Ui.dp(context, 118f)).coerceIn(3, 8)
        r = w / (cols + 0.5f) / 1.7320508f
        scroll = scroll.coerceIn(0f, maxScroll())
    }

    private fun cx(row: Int, col: Int) = colW() * (col + 0.5f) + if (row % 2 == 1) colW() / 2 else 0f
    private fun cy(row: Int) = r * 1.05f + row * 1.5f * r - scroll

    private fun tileAt(x: Float, y: Float): Int {
        for (i in tiles.indices) {
            val dx = x - cx(i / cols, i % cols)
            val dy = y - cy(i / cols)
            if (dx * dx + dy * dy < r * r * 0.72f) return i
        }
        return -1
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val h = gestures.onTouchEvent(e)
        if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) { pressed = -1; invalidate() }
        return h
    }

    private fun hex(cx: Float, cy: Float, rad: Float) {
        path.rewind()
        for (k in 0 until 6) {
            val a = Math.toRadians((60.0 * k) - 90.0)
            val px = cx + rad * Math.cos(a).toFloat()
            val py = cy + rad * Math.sin(a).toFloat()
            if (k == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        path.close()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Ui.BG)
        val rad = r - Ui.dp(context, 3f)
        val rows = (height / (1.5f * r)).toInt() + 3
        val first = (scroll / (1.5f * r)).toInt() - 1
        big.textSize = r * 0.26f
        small.textSize = r * 0.155f
        for (row in first until first + rows) {
            for (col in -1..cols) {
                val x = cx(row, col)
                val y = cy(row)
                if (y < -r || y > height + r) continue
                val i = if (row >= 0 && col in 0 until cols) row * cols + col else -1
                val tile = tiles.getOrNull(i)
                hex(x, y, rad)
                if (tile == null) {
                    line.color = 0xFF22272C.toInt()
                    canvas.drawPath(path, line)
                    continue
                }
                fill.color = if (i == pressed) Ui.LINE else Ui.SURFACE
                canvas.drawPath(path, fill)
                line.color = if (tile.accent) Ui.ACCENT else Ui.LINE
                canvas.drawPath(path, line)
                big.color = if (tile.accent) Ui.ACCENT else Ui.TEXT
                val maxW = colW() * 0.78f
                if (tile.sub == null) {
                    canvas.drawText(TextUtils.ellipsize(tile.label, big, maxW, TextUtils.TruncateAt.END).toString(), x, y + big.textSize * 0.35f, big)
                } else {
                    canvas.drawText(TextUtils.ellipsize(tile.label, big, maxW, TextUtils.TruncateAt.END).toString(), x, y - r * 0.12f, big)
                    small.color = Ui.DIM
                    // up to two lines of the file name
                    val s = tile.sub
                    var cut = small.breakText(s, true, maxW, null)
                    if (cut >= s.length) canvas.drawText(s, x, y + r * 0.16f, small)
                    else {
                        val sp = s.lastIndexOf(' ', cut)
                        if (sp > cut / 2) cut = sp + 1
                        canvas.drawText(s.substring(0, cut).trim(), x, y + r * 0.16f, small)
                        canvas.drawText(TextUtils.ellipsize(s.substring(cut), small, maxW * 0.82f, TextUtils.TruncateAt.MIDDLE).toString(), x, y + r * 0.16f + small.textSize * 1.25f, small)
                    }
                }
            }
        }
    }
}

class HomeActivity : Activity() {
    private lateinit var grid: HexGridView
    private val pickOne = 1
    private val pickTwo = 2

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Ui.BG)
        val head = TextView(this)
        head.text = "DOCUMENT FACILITATOR"
        head.setTextColor(Ui.DIM)
        head.textSize = 12f
        head.letterSpacing = 0.2f
        head.gravity = Gravity.CENTER
        head.setPadding(0, Ui.dp(this, 10f), 0, Ui.dp(this, 6f))
        root.addView(head)
        grid = HexGridView(this)
        root.addView(grid, LinearLayout.LayoutParams(-1, 0, 1f))
        root.setOnApplyWindowInsetsListener { v, insets ->
            v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            insets
        }
        setContentView(root)
        Ui.bg { Store.sweep(applicationContext) }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val t = ArrayList<HexGridView.Tile>()
        t.add(HexGridView.Tile("OPEN", null, true, { pick(false) }))
        t.add(HexGridView.Tile("SPLIT", "two documents", true, { pick(true) }))
        t.add(HexGridView.Tile("NEW", null, true, { newDoc() }))
        t.add(HexGridView.Tile("STATES", "saved versions", true, { states() }))
        for (r in Store.recents(this)) {
            t.add(HexGridView.Tile(DocType.fromName(r.name).label, r.name, false, { open(listOf(Uri.parse(r.uri))) }, {
                Ui.confirm(this, "Remove \"${r.name}\" from recent documents?", "Remove") { Store.removeRecent(this, r.uri); refresh() }
            }))
        }
        grid.tiles = t
    }

    private fun pick(two: Boolean) {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "*/*"
        if (two) i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        try {
            startActivityForResult(i, if (two) pickTwo else pickOne)
            if (two) Ui.toast(this, "Select two documents")
        } catch (e: Exception) {
            Ui.toast(this, "No file picker available")
        }
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (res != RESULT_OK || data == null) return
        val uris = ArrayList<Uri>()
        data.clipData?.let { cd -> for (i in 0 until cd.itemCount) uris.add(cd.getItemAt(i).uri) }
        if (uris.isEmpty()) data.data?.let { uris.add(it) }
        for (u in uris) persist(this, u)
        if (uris.isNotEmpty()) open(uris.take(2))
    }

    private fun open(uris: List<Uri>) {
        val i = Intent(this, ViewerActivity::class.java)
        i.putExtra(ViewerActivity.EXTRA_URIS, ArrayList(uris.map { it.toString() }))
        startActivity(i)
    }

    private fun newDoc() {
        val kinds = listOf("Text note (.txt)" to "txt", "Markdown (.md)" to "md", "Rich document (.docx)" to "docx", "Web page (.html)" to "html", "Spreadsheet (.csv)" to "csv")
        Ui.choose(this, "New document", kinds.map { it.first }) { k ->
            val i = Intent(this, ViewerActivity::class.java)
            i.putExtra(ViewerActivity.EXTRA_NEW, kinds[k].second)
            startActivity(i)
        }
    }

    private fun states() {
        val docs = Store.allDocs(this)
        if (docs.isEmpty()) { Ui.info(this, "No saved states yet.\n\nOpen a document, edit or annotate it, then use Menu → Save state to keep that version inside the app. States can be exported to the phone later."); return }
        Ui.choose(this, "Documents with saved states", docs.map { "${it.name}\n${it.count} state(s) · ${Ui.size(it.bytes)}" }) { k ->
            val d = docs[k]
            if (d.uri.isEmpty()) Ui.toast(this, "This document was never saved to the phone; its states were kept only while it was open.")
            else open(listOf(Uri.parse(d.uri)))
        }
    }

    companion object {
        fun persist(c: Context, u: Uri) {
            try {
                c.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            } catch (_: Exception) {
                try { c.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            }
        }
    }
}
