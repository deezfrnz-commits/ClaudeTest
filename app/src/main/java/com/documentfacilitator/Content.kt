package com.documentfacilitator

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View
import java.io.ByteArrayOutputStream
import java.io.File

class Tool(val label: String, val selected: Boolean = false, val action: () -> Unit)

class Format(val label: String, val ext: String, val mime: String, val note: String? = null)

/** What a document view needs from the pane that hosts it. */
interface Host {
    val activity: Activity
    fun request(intent: Intent, cb: (Intent?) -> Unit)
    fun toolsChanged()
    fun markDirty()
    fun status(msg: String)
}

/** A loaded document shown in a pane: one implementation per document family. */
abstract class Content(val host: Host, val doc: Loaded) {
    companion object {
        const val VIEW = 0
        const val EDIT = 1
        const val FILL = 2
    }

    val ctx: Context get() = host.activity
    var mode = VIEW
        private set

    abstract val view: View
    open fun setMode(m: Int) { mode = m }
    abstract fun tools(): List<Tool>
    open fun menu(): List<Tool> = emptyList()

    /** Compact description of the current edits: payload, optional replaced base file, referenced pool images. */
    abstract fun snapshot(cb: (ByteArray, File?, Collection<String>) -> Unit)
    abstract fun restore(payload: ByteArray, base: File?)

    /** Formats this document can be written as; the first one is the document's own format. */
    abstract fun formats(): List<Format>
    /** Writes the document including all edits. [cb] gets success and an optional remark for the user. */
    abstract fun export(ext: String, out: File, cb: (Boolean, String?) -> Unit)

    open fun find(query: String, forward: Boolean) { Ui.toast(ctx, "Find is not available for this document") }
    open fun print(name: String) { Ui.toast(ctx, "Printing is not available for this document") }
    open fun setNight(on: Boolean) {}
    open fun zoomText(delta: Int) {}
    open fun close() {}
}

object Images {
    /** Decodes with down-sampling so that neither side exceeds [max] by more than 2x. */
    fun decode(bytes: ByteArray, max: Int): Bitmap? {
        val o = BitmapFactory.Options()
        o.inJustDecodeBounds = true
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        if (o.outWidth <= 0) return null
        var s = 1
        while (o.outWidth / (s * 2) >= max || o.outHeight / (s * 2) >= max) s *= 2
        val o2 = BitmapFactory.Options()
        o2.inSampleSize = s
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o2)
    }

    fun decodeFile(f: File, max: Int): Bitmap? = try { decode(f.readBytes(), max) } catch (_: Throwable) { null }

    /** Reads a picked image, limits its size and stores it in the pool. Returns (id, width, height). */
    fun import(c: Context, data: Intent?, max: Int = 1600): Triple<String, Int, Int>? {
        val uri = data?.data ?: return null
        val bytes = try { c.contentResolver.openInputStream(uri)?.use { it.readBytes() } } catch (_: Exception) { null } ?: return null
        val bmp = decode(bytes, max) ?: return null
        val bo = ByteArrayOutputStream()
        val ext: String
        if (bmp.hasAlpha()) { bmp.compress(Bitmap.CompressFormat.PNG, 100, bo); ext = "png" } else { bmp.compress(Bitmap.CompressFormat.JPEG, 85, bo); ext = "jpg" }
        val id = Store.putImage(c, bo.toByteArray(), ext)
        val r = Triple(id, bmp.width, bmp.height)
        bmp.recycle()
        return r
    }

    fun pick(host: Host, cb: (Intent?) -> Unit) {
        val i = Intent(Intent.ACTION_GET_CONTENT)
        i.type = "image/*"
        i.addCategory(Intent.CATEGORY_OPENABLE)
        host.request(i, cb)
    }
}

/** Finger-drawn signature capture. Result: PNG with transparent background, cropped to the ink. */
object SignaturePad {
    private class Pad(c: Context) : View(c) {
        val path = Path()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = Ui.dp(c, 2.5f).toFloat(); strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; color = Color.BLACK }
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxX = 0f; var maxY = 0f
        var lx = 0f; var ly = 0f
        override fun onDraw(canvas: Canvas) { canvas.drawColor(Color.WHITE); canvas.drawPath(path, paint) }
        override fun onTouchEvent(e: MotionEvent): Boolean {
            parent?.requestDisallowInterceptTouchEvent(true)
            val x = e.x; val y = e.y
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { path.moveTo(x, y); path.lineTo(x + 0.1f, y + 0.1f) }
                MotionEvent.ACTION_MOVE -> path.quadTo(lx, ly, (x + lx) / 2, (y + ly) / 2)
                else -> return true
            }
            lx = x; ly = y
            minX = Math.min(minX, x); minY = Math.min(minY, y); maxX = Math.max(maxX, x); maxY = Math.max(maxY, y)
            invalidate()
            return true
        }
        fun clear() { path.rewind(); minX = Float.MAX_VALUE; minY = Float.MAX_VALUE; maxX = 0f; maxY = 0f; invalidate() }
        fun png(): ByteArray? {
            if (maxX <= minX || maxY <= minY) return null
            val pad = paint.strokeWidth * 2
            val w = Math.ceil((maxX - minX + 2 * pad).toDouble()).toInt()
            val h = Math.ceil((maxY - minY + 2 * pad).toDouble()).toInt()
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val cv = Canvas(bmp)
            cv.translate(pad - minX, pad - minY)
            cv.drawPath(path, paint)
            val bo = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.PNG, 100, bo)
            bmp.recycle()
            return bo.toByteArray()
        }
    }

    /** [cb] receives the pool id and the pixel size of the signature. */
    fun show(c: Context, cb: (String, Int, Int) -> Unit) {
        val pad = Pad(c)
        pad.minimumHeight = Ui.dp(c, 220f)
        val d = AlertDialog.Builder(c).setTitle("Sign with your finger").setView(pad)
            .setPositiveButton("Use") { _, _ ->
                val png = pad.png()
                if (png != null) {
                    val o = BitmapFactory.Options(); o.inJustDecodeBounds = true
                    BitmapFactory.decodeByteArray(png, 0, png.size, o)
                    cb(Store.putImage(c, png, "png"), o.outWidth, o.outHeight)
                }
            }
            .setNeutralButton("Clear", null).setNegativeButton("Cancel", null).create()
        d.show()
        d.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { pad.clear() }
    }
}
