package com.documentfacilitator

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.documentfacilitator.core.DocType
import com.documentfacilitator.core.DocxWriter
import com.documentfacilitator.core.Kind
import com.documentfacilitator.core.PlainText
import org.json.JSONArray
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One document with its mode switch, tool row and menu. A viewer window holds one or two of these. */
class DocPane(private val act: ViewerActivity) : LinearLayout(act), Host {
    override val activity: Activity get() = act
    var doc: Loaded? = null
        private set
    private var content: Content? = null
    var dirty = false
        private set
    private var statusText = ""
    private var findQuery: String? = null
    private var night = false

    private val title = TextView(act)
    private val modes = ArrayList<TextView>()
    private val toolRow = LinearLayout(act)
    private val toolScroll = HorizontalScrollView(act)
    private val frame = FrameLayout(act)
    private val message = TextView(act)

    companion object {
        const val AUTOSAVE = "(autosave)"
        private const val MAX_PLAIN = 2 * 1024 * 1024
    }

    init {
        orientation = VERTICAL
        setBackgroundColor(Ui.BG)
        val bar = LinearLayout(act)
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setPadding(Ui.dp(act, 4f), Ui.dp(act, 4f), Ui.dp(act, 4f), Ui.dp(act, 4f))
        val menu = Ui.toolButton(act, "≡") { showMenu() }
        menu.textSize = 18f
        bar.addView(menu)
        title.setTextColor(Ui.TEXT)
        title.textSize = 13f
        title.maxLines = 2
        title.ellipsize = TextUtils.TruncateAt.MIDDLE
        title.setPadding(Ui.dp(act, 8f), 0, Ui.dp(act, 4f), 0)
        bar.addView(title, LayoutParams(0, -2, 1f))
        for ((i, name) in arrayOf("VIEW", "EDIT", "FILL").withIndex()) {
            val b = Ui.hexButton(act, name) { setMode(i) }
            modes.add(b)
            bar.addView(b)
        }
        addView(bar, LayoutParams(-1, -2))
        toolRow.setPadding(Ui.dp(act, 4f), 0, Ui.dp(act, 4f), Ui.dp(act, 4f))
        toolScroll.isHorizontalScrollBarEnabled = false
        toolScroll.addView(toolRow)
        toolScroll.visibility = View.GONE
        addView(toolScroll, LayoutParams(-1, -2))
        message.setTextColor(Ui.DIM)
        message.gravity = Gravity.CENTER
        message.setPadding(Ui.dp(act, 24f), 0, Ui.dp(act, 24f), 0)
        frame.setBackgroundColor(0xFF2A2E33.toInt())
        frame.addView(message, FrameLayout.LayoutParams(-1, -1))
        addView(frame, LayoutParams(-1, 0, 1f))
        showMessage("No document.\nUse ≡ → Open document.")
        refreshTitle()
        Ui.setHexSelected(modes[0], true)
    }

    private fun showMessage(s: String) { message.text = s; message.visibility = View.VISIBLE }

    // ---- Host --------------------------------------------------------------------------------

    override fun request(intent: Intent, cb: (Intent?) -> Unit) = act.request(intent, cb)
    override fun markDirty() { if (!dirty) { dirty = true; refreshTitle() } }
    override fun status(msg: String) { if (msg != statusText) { statusText = msg; refreshTitle() } }

    override fun toolsChanged() {
        toolRow.removeAllViews()
        val c = content
        val list = ArrayList<Tool>()
        val q = findQuery
        if (q != null && c != null) {
            list.add(Tool("▲ \"${q.take(12)}\"") { c.find(q, false) })
            list.add(Tool("▼") { c.find(q, true) })
            list.add(Tool("✕") { findQuery = null; toolsChanged() })
        }
        if (c != null) list.addAll(c.tools())
        for (t in list) {
            val b = Ui.toolButton(act, t.label, t.action)
            Ui.setToolSelected(b, t.selected)
            toolRow.addView(b)
        }
        toolScroll.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun refreshTitle() {
        val d = doc
        val sb = StringBuilder()
        if (dirty) sb.append("● ")
        sb.append(d?.name ?: "DocumentFacilitator")
        if (d != null) { sb.append('\n').append(d.kind.label); if (statusText.isNotEmpty()) sb.append("  ·  ").append(statusText) }
        title.text = sb
    }

    // ---- loading -----------------------------------------------------------------------------

    fun open(uri: Uri) {
        showMessage("Opening…")
        val app = act.applicationContext
        Ui.bg {
            try {
                val l = Store.load(app, uri)
                prepare(l)
            } catch (t: Throwable) {
                Ui.log("open failed: $t")
                Ui.ui { showMessage("This document could not be opened.\n${t.message ?: ""}") }
            }
        }
    }

    fun openNew(ext: String) {
        val app = act.applicationContext
        Ui.bg {
            val f = File(Store.openDir(app), "${System.nanoTime()}.$ext")
            when (ext) {
                "docx" -> DocxWriter.write(f, JSONArray("[{\"t\":\"p\",\"runs\":[]}]"), emptyMap())
                "html" -> f.writeText("<p><br></p>")
                else -> f.writeText("")
            }
            prepare(Loaded(null, f, "Untitled.$ext", DocType.fromName("x.$ext")), true)
        }
    }

    /** Background part of opening: conversion. Then builds the view on the main thread. */
    private fun prepare(l: Loaded, fresh: Boolean = false) {
        val make: () -> Content = when {
            l.kind.family == Kind.PAGED -> ({ PagedContent(this, l) })
            l.kind.family == Kind.PLAIN || l.kind == Kind.MARKDOWN -> {
                val bytes = l.file.inputStream().use { com.documentfacilitator.core.readAllCapped(it, MAX_PLAIN + 1) }
                val trunc = bytes.size > MAX_PLAIN
                val text = PlainText.decode(if (trunc) bytes.copyOf(MAX_PLAIN) else bytes);
                { TextContent(this, l, text, trunc) }
            }
            else -> { val p = WebContent.prepare(l); { WebContent(this, l, p) } }
        }
        Ui.ui {
            if (act.isFinishing || act.isDestroyed) return@ui
            closeContent()
            val c = try { make() } catch (t: Throwable) { Ui.log("view failed: $t"); showMessage("This document could not be displayed.\n${t.message ?: ""}"); return@ui }
            doc = l; content = c; dirty = false; statusText = ""; findQuery = null
            message.visibility = View.GONE
            frame.addView(c.view, 0, FrameLayout.LayoutParams(-1, -1))
            if (night) c.setNight(true)
            l.uri?.let { Store.addRecent(act, it, l.name) }
            Ui.log("opened ${l.name} as ${l.kind}")
            setMode(if (fresh) Content.EDIT else act.startMode)
            Ui.log("ready ${l.kind} mode=${content?.mode}")
            refreshTitle()
            val auto = Store.states(act, key()).firstOrNull { it.auto }
            if (auto != null) AlertDialog.Builder(act).setMessage("Unsaved changes from your last session were kept for this document. Restore them?")
                .setPositiveButton("Restore") { _, _ -> loadState(auto) }
                .setNegativeButton("Discard") { _, _ -> Store.deleteState(act, auto) }.show()
        }
    }

    private fun closeContent() {
        val c = content ?: return
        frame.removeView(c.view)
        c.close()
        content = null
        doc?.let { d -> if (d.file.parentFile == Store.openDir(act)) d.file.delete() }
        doc = null
    }

    fun close() = closeContent()

    private fun key(): String { val d = doc; return Store.docKey(d?.uri, d?.name ?: "") }

    fun setMode(m: Int) {
        val c = content ?: return
        c.setMode(m)
        for ((i, b) in modes.withIndex()) Ui.setHexSelected(b, i == c.mode)
        toolsChanged()
    }

    // ---- states ------------------------------------------------------------------------------

    fun saveState(name: String, auto: Boolean, done: (() -> Unit)? = null) {
        val c = content ?: return
        val d = doc ?: return
        val k = key()
        val app = act.applicationContext
        c.snapshot { payload, base, images ->
            Ui.bg {
                try {
                    Store.saveState(app, k, d, name, auto, payload, base, images)
                    Ui.ui {
                        if (!auto) { dirty = false; refreshTitle(); Ui.toast(app, "State \"$name\" saved in the app"); Store.states(app, k).firstOrNull { it.auto }?.let { Store.deleteState(app, it) } }
                        done?.invoke()
                    }
                } catch (t: Throwable) {
                    Ui.log("save state failed: $t")
                    Ui.ui { Ui.toast(app, "Could not save the state") }
                }
            }
        }
    }

    private fun loadState(s: StateInfo) {
        val c = content ?: return
        val app = act.applicationContext
        Ui.bg {
            try {
                val payload = Store.statePayload(app, s)
                val base = Store.stateBase(app, s)
                Ui.ui { c.restore(payload, base); dirty = true; refreshTitle(); toolsChanged(); Ui.toast(app, "\"${s.name}\" loaded. Use Export copy to download it.") }
            } catch (t: Throwable) {
                Ui.ui { Ui.toast(app, "That state could not be read") }
            }
        }
    }

    private fun showStates() {
        val list = Store.states(act, key())
        if (list.isEmpty()) { Ui.info(act, "No saved states for this document yet.\n\nSave state keeps the current edits (for example a highlighted or draft version) inside the app without touching the file on the phone."); return }
        val fmt = SimpleDateFormat("d MMM HH:mm", Locale.getDefault())
        Ui.choose(act, "Saved states", list.map { "${it.name}\n${fmt.format(Date(it.time))} · ${Ui.size(it.bytes)}" }) { i ->
            val s = list[i]
            Ui.choose(act, s.name, listOf("Load", "Load and export…", "Rename", "Delete")) { a ->
                when (a) {
                    0 -> loadState(s)
                    1 -> { loadState(s); Ui.later(600) { exportCopy() } }
                    2 -> Ui.prompt(act, "Rename state", s.name) { n -> if (n.isNotBlank()) Store.renameState(act, s, n.trim()) }
                    3 -> Ui.confirm(act, "Delete state \"${s.name}\"?", "Delete") { Store.deleteState(act, s) }
                }
            }
        }
    }

    // ---- export ------------------------------------------------------------------------------

    private fun runExport(f: Format, target: Uri, after: (() -> Unit)? = null) {
        val c = content ?: return
        val app = act.applicationContext
        val tmp = Store.tmp(app, "export-${System.nanoTime()}.${f.ext}")
        c.export(f.ext, tmp) { ok, note ->
            if (!ok) { tmp.delete(); Ui.info(act, "Export failed.\n${note ?: ""}"); return@export }
            Ui.bg {
                try {
                    (app.contentResolver.openOutputStream(target, "wt") ?: throw java.io.IOException("destination not writable")).use { o -> tmp.inputStream().use { it.copyTo(o, 65536) } }
                    Ui.ui {
                        dirty = false; refreshTitle()
                        Store.states(app, key()).firstOrNull { it.auto }?.let { Store.deleteState(app, it) }
                        if (note != null) Ui.info(act, "Saved.\n\n$note") else Ui.toast(app, "Saved")
                        after?.invoke()
                    }
                } catch (t: Throwable) {
                    Ui.log("write failed: $t")
                    Ui.ui { Ui.info(act, "The file could not be written there (${t.message}). Try Export copy to another location.") }
                } finally {
                    tmp.delete()
                }
            }
        }
    }

    private fun pickFormat(cb: (Format) -> Unit) {
        val fs = content?.formats() ?: return
        if (fs.size == 1) cb(fs[0]) else Ui.choose(act, "Export as", fs.map { it.label + " (." + it.ext + ")" }) { cb(fs[it]) }
    }

    fun exportCopy() {
        val d = doc ?: return
        pickFormat { f ->
            val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
            i.addCategory(Intent.CATEGORY_OPENABLE)
            i.type = f.mime
            i.putExtra(Intent.EXTRA_TITLE, d.name.substringBeforeLast('.') + "." + f.ext)
            request(i) { data -> data?.data?.let { runExport(f, it) } }
        }
    }

    private fun overwrite() {
        val d = doc ?: return
        val f = content?.formats()?.firstOrNull() ?: return
        val uri = d.uri
        val sameType = DocType.fromName("x." + f.ext) == d.kind || (d.kind == Kind.TEXT || d.kind == Kind.UNKNOWN)
        if (uri == null || !sameType) { Ui.info(act, "This document cannot be written back in its original format. Use Export copy instead."); return }
        Ui.confirm(act, "Replace \"${d.name}\" on the phone with the current version?\n\nTip: Save state first if you may want the earlier version back.", "Overwrite") { runExport(f, uri) }
    }

    private fun share() {
        val d = doc ?: return
        val c = content ?: return
        pickFormat { f ->
            val dir = File(act.cacheDir, "share").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val out = File(dir, (d.name.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9 ._-]"), "_").ifEmpty { "document" }) + "." + f.ext)
            c.export(f.ext, out) { ok, note ->
                if (!ok) { Ui.info(act, "Could not prepare the file.\n${note ?: ""}"); return@export }
                val i = Intent(Intent.ACTION_SEND)
                i.type = f.mime
                i.putExtra(Intent.EXTRA_STREAM, Uri.parse("content://${act.packageName}.share/${Uri.encode(out.name)}"))
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                try { act.startActivity(Intent.createChooser(i, "Share document")) } catch (_: Exception) { Ui.toast(act, "No app can receive this file") }
            }
        }
    }

    // ---- menu --------------------------------------------------------------------------------

    private fun showMenu() {
        val items = ArrayList<Tool>()
        val c = content
        val d = doc
        items.add(Tool("Open document…") { guard { act.pick { open(it) } } })
        if (c != null && d != null) {
            items.add(Tool("Save state…") { Ui.prompt(act, "Name this state", if (dirty) "Draft" else "Copy", hint = "e.g. Highlighted, Draft 2") { n -> if (n.isNotBlank()) saveState(n.trim(), false) } })
            items.add(Tool("States…") { showStates() })
            items.add(Tool("Export copy to phone…") { exportCopy() })
            if (d.uri != null) items.add(Tool("Overwrite original") { overwrite() })
            items.add(Tool("Share…") { share() })
            items.add(Tool("Print…") { try { c.print(d.name) } catch (t: Throwable) { Ui.toast(act, "Printing is not available") } })
            items.add(Tool("Find…") { Ui.prompt(act, "Find", findQuery ?: "") { q -> if (q.isNotEmpty()) { findQuery = q; toolsChanged(); c.find(q, true) } } })
            items.addAll(c.menu())
            items.add(Tool(if (night) "Day colours" else "Night colours") { night = !night; c.setNight(night) })
        }
        items.add(Tool(if (act.paneCount() < 2) "Open second document (split view)" else "Close this pane") { if (act.paneCount() < 2) act.addPane() else guard { act.closePane(this) } })
        if (d?.uri != null) items.add(Tool("Open in separate window") { act.newWindow(d.uri) })
        if (d != null) items.add(Tool("Document info") {
            Ui.info(act, "${d.name}\nType: ${d.kind.label}\nSize: ${Ui.size(d.file.length())}\nSaved states: ${Store.states(act, key()).count { !it.auto }}\n\nEverything is processed on this device. The app has no network permission.")
        })
        Ui.choose(act, null, items.map { it.label }) { items[it].action() }
    }

    /** Runs [go] directly, or after asking what to do with unsaved edits. */
    fun guard(go: () -> Unit) {
        if (!dirty || content == null) { go(); return }
        AlertDialog.Builder(act).setMessage("\"${doc?.name}\" has changes that are not exported.")
            .setPositiveButton("Keep as state") { _, _ -> saveState("Draft", false) { go() } }
            .setNegativeButton("Discard") { _, _ -> discard(); go() }
            .setNeutralButton("Cancel", null).show()
    }

    fun discard() {
        dirty = false
        Store.states(act, key()).firstOrNull { it.auto }?.let { Store.deleteState(act, it) }
    }
}

class ViewerActivity : Activity() {
    companion object {
        const val EXTRA_URIS = "uris"
        const val EXTRA_NEW = "new"
        /** Optional start mode (0 view, 1 edit, 2 fill); used by automated tests and shortcuts. */
        const val EXTRA_MODE = "mode"
    }

    private lateinit var root: LinearLayout
    private val panes = ArrayList<DocPane>()
    private var divider: View? = null
    private val callbacks = HashMap<Int, (Intent?) -> Unit>()
    private var nextCode = 100
    private var split = 0.5f
    private var wideNow = false
    val startMode: Int get() = intent.getIntExtra(EXTRA_MODE, 0).coerceIn(0, 2)

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        root = LinearLayout(this)
        root.setBackgroundColor(Ui.BG)
        root.setOnApplyWindowInsetsListener { v, insets ->
            v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            insets
        }
        setContentView(root)
        val uris = ArrayList<Uri>()
        intent.getStringArrayListExtra(EXTRA_URIS)?.forEach { uris.add(Uri.parse(it)) }
        if (uris.isEmpty()) intent.getStringArrayExtra(EXTRA_URIS)?.forEach { uris.add(Uri.parse(it)) }
        if (uris.isEmpty()) intent.data?.let { uris.add(it) }
        if (uris.isEmpty() && intent.action == Intent.ACTION_SEND) (intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))?.let { uris.add(it) }
        Ui.log("viewer create ${System.identityHashCode(this)} uris=$uris restored=${b != null}")
        val first = newPane()
        val fresh = intent.getStringExtra(EXTRA_NEW)
        if (fresh != null) first.openNew(fresh)
        else if (uris.isNotEmpty()) {
            first.open(uris[0])
            if (uris.size > 1) newPane().open(uris[1])
        }
        arrange()
    }

    private fun newPane(): DocPane { val p = DocPane(this); panes.add(p); return p }
    fun paneCount() = panes.size

    /** Lays the panes out side by side on wide windows and stacked on tall ones, with a draggable divider. */
    private fun arrange() {
        Ui.log("arrange panes=${panes.size}")
        root.removeAllViews()
        val cfg = resources.configuration
        val wide = cfg.screenWidthDp > cfg.screenHeightDp
        wideNow = wide
        root.orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        for ((i, p) in panes.withIndex()) {
            if (i == 1) {
                val d = View(this)
                d.setBackgroundColor(Ui.ACCENT)
                val th = Ui.dp(this, 6f)
                d.setOnTouchListener { _, e ->
                    if (e.actionMasked == MotionEvent.ACTION_MOVE || e.actionMasked == MotionEvent.ACTION_UP) {
                        val loc = IntArray(2); root.getLocationOnScreen(loc)
                        val f = if (wide) (e.rawX - loc[0]) / Math.max(1, root.width) else (e.rawY - loc[1]) / Math.max(1, root.height)
                        split = f.coerceIn(0.15f, 0.85f)
                        (panes[0].layoutParams as LinearLayout.LayoutParams).weight = split
                        (panes[1].layoutParams as LinearLayout.LayoutParams).weight = 1f - split
                        root.requestLayout()
                    }
                    true
                }
                root.addView(d, if (wide) LinearLayout.LayoutParams(th, -1) else LinearLayout.LayoutParams(-1, th))
                divider = d
            }
            val w = if (panes.size == 1) 1f else if (i == 0) split else 1f - split
            root.addView(p, if (wide) LinearLayout.LayoutParams(0, -1, w) else LinearLayout.LayoutParams(-1, 0, w))
        }
    }

    fun addPane() {
        if (panes.size >= 2) return
        Ui.log("addPane: picking")
        pick { uri -> Ui.log("addPane: got $uri"); val p = newPane(); arrange(); p.open(uri) }
    }

    fun closePane(p: DocPane) {
        p.close()
        if (panes.size <= 1) { finish(); return }
        panes.remove(p)
        arrange()
    }

    fun pick(cb: (Uri) -> Unit) {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "*/*"
        request(i) { data -> data?.data?.let { HomeActivity.persist(this, it); cb(it) } }
    }

    fun newWindow(uri: Uri) {
        val i = Intent(this, ViewerActivity::class.java)
        i.data = uri
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK or Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(i)
    }

    fun request(intent: Intent, cb: (Intent?) -> Unit) {
        val code = nextCode++
        callbacks[code] = cb
        try {
            startActivityForResult(intent, code)
        } catch (e: Exception) {
            callbacks.remove(code)
            Ui.toast(this, "No app available for this action")
        }
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        Ui.log("result req=$req res=$res known=${callbacks.containsKey(req)} activity=${System.identityHashCode(this)}")
        val cb = callbacks.remove(req) ?: return
        if (res == RESULT_OK) cb(data)
    }

    override fun onConfigurationChanged(c: Configuration) {
        super.onConfigurationChanged(c)
        if ((c.screenWidthDp > c.screenHeightDp) != wideNow) root.post { arrange() }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val d = panes.firstOrNull { it.dirty }
        if (d == null) { finish(); return }
        d.guard { onBackPressed() }
    }

    override fun onStop() {
        super.onStop()
        // keep unexported work across process death as a restorable autosave state
        if (!isFinishing) for (p in panes) if (p.dirty) p.saveState(DocPane.AUTOSAVE, true)
    }

    override fun onDestroy() {
        super.onDestroy()
        Ui.log("viewer destroy ${System.identityHashCode(this)} finishing=$isFinishing")
        for (p in panes) p.close()
    }
}

/** Hands exported files in cache/share to other apps (Share…), read-only. */
class ShareProvider : ContentProvider() {
    private fun file(uri: Uri): File? {
        val name = uri.lastPathSegment ?: return null
        if (name.contains('/') || name.contains("..")) return null
        val f = File(File(context!!.cacheDir, "share"), name)
        return if (f.isFile) f else null
    }

    override fun onCreate() = true
    override fun getType(uri: Uri): String = file(uri)?.let { DocType.mime(DocType.fromName(it.name), it.name) } ?: "application/octet-stream"
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? =
        ParcelFileDescriptor.open(file(uri) ?: throw java.io.FileNotFoundException(), ParcelFileDescriptor.MODE_READ_ONLY)

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor? {
        val f = file(uri) ?: return null
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val c = MatrixCursor(cols)
        c.addRow(cols.map { if (it == OpenableColumns.DISPLAY_NAME) f.name else if (it == OpenableColumns.SIZE) f.length() else null })
        return c
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
}
