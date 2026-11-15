package com.documentfacilitator

import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

/** Shared palette, threading and small widget helpers. Everything is drawn in code: no layout or image resources. */
object Ui {
    const val BG = 0xFF101214.toInt()
    const val SURFACE = 0xFF1B1F23.toInt()
    const val LINE = 0xFF3A4148.toInt()
    const val TEXT = 0xFFE6E9EC.toInt()
    const val DIM = 0xFF8C959F.toInt()
    const val ACCENT = 0xFFF2B33D.toInt()
    const val TAG = "DocFacilitator"

    private val main = Handler(Looper.getMainLooper())
    private val pool = Executors.newSingleThreadExecutor()

    fun bg(work: () -> Unit) {
        pool.execute {
            try {
                work()
            } catch (t: Throwable) {
                Log.e(TAG, "background task failed", t)
            }
        }
    }

    fun ui(work: () -> Unit) { main.post(work) }
    fun later(ms: Long, work: Runnable) { main.postDelayed(work, ms) }
    fun cancel(work: Runnable) { main.removeCallbacks(work) }

    fun dp(c: Context, v: Float): Int = Math.round(v * c.resources.displayMetrics.density)
    fun toast(c: Context, msg: String) { Toast.makeText(c, msg, Toast.LENGTH_SHORT).show() }
    fun log(msg: String) { Log.i(TAG, msg) }

    /** Path of a flat-top hexagon inscribed in the given box. */
    fun hexPath(path: Path, l: Float, t: Float, r: Float, b: Float) {
        val w = r - l
        val cy = (t + b) / 2
        val inset = w * 0.22f
        path.rewind()
        path.moveTo(l, cy); path.lineTo(l + inset, t); path.lineTo(r - inset, t)
        path.lineTo(r, cy); path.lineTo(r - inset, b); path.lineTo(l + inset, b); path.close()
    }

    class HexDrawable(var fill: Int, var stroke: Int, private val strokePx: Float) : Drawable() {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val path = Path()
        override fun draw(canvas: Canvas) {
            val b = bounds
            val h = strokePx / 2
            hexPath(path, b.left + h, b.top + h, b.right - h, b.bottom - h)
            p.style = Paint.Style.FILL; p.color = fill
            canvas.drawPath(path, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = strokePx; p.color = stroke
            canvas.drawPath(path, p)
        }
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(cf: ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    /** Hexagonal toggle used for the three document modes. */
    fun hexButton(c: Context, label: String, onClick: () -> Unit): TextView {
        val t = TextView(c)
        t.text = label
        t.textSize = 11f
        t.typeface = Typeface.DEFAULT_BOLD
        t.gravity = Gravity.CENTER
        t.setTextColor(TEXT)
        t.background = HexDrawable(SURFACE, LINE, dp(c, 1.5f).toFloat())
        t.layoutParams = ViewGroup.MarginLayoutParams(dp(c, 58f), dp(c, 34f)).also { it.leftMargin = dp(c, 2f) }
        t.setOnClickListener { onClick() }
        return t
    }

    fun setHexSelected(t: TextView, on: Boolean) {
        val d = t.background as? HexDrawable ?: return
        d.fill = if (on) ACCENT else SURFACE
        d.stroke = if (on) ACCENT else LINE
        t.setTextColor(if (on) BG else TEXT)
        t.invalidate()
    }

    /** Plain rectangular tool button. */
    fun toolButton(c: Context, label: String, onClick: () -> Unit): TextView {
        val t = TextView(c)
        t.text = label
        t.textSize = 12f
        t.gravity = Gravity.CENTER
        t.setTextColor(TEXT)
        t.setPadding(dp(c, 10f), 0, dp(c, 10f), 0)
        t.minWidth = dp(c, 40f)
        t.layoutParams = ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(c, 36f)).also { it.rightMargin = dp(c, 2f) }
        t.setBackgroundColor(SURFACE)
        t.setOnClickListener { onClick() }
        return t
    }

    fun setToolSelected(t: TextView, on: Boolean) {
        t.setBackgroundColor(if (on) ACCENT else SURFACE)
        t.setTextColor(if (on) BG else TEXT)
    }

    fun prompt(c: Context, title: String, initial: String, multiline: Boolean = false, hint: String? = null, cb: (String) -> Unit) {
        val e = EditText(c)
        e.setText(initial)
        e.hint = hint
        e.inputType = if (multiline) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        if (multiline) { e.minLines = 3; e.gravity = Gravity.TOP or Gravity.START }
        e.setSelection(0, e.text.length)
        val box = FrameLayout(c)
        val pad = dp(c, 20f)
        box.setPadding(pad, dp(c, 8f), pad, 0)
        box.addView(e)
        val d = AlertDialog.Builder(c).setTitle(title).setView(box)
            .setPositiveButton("OK") { _, _ -> cb(e.text.toString()) }
            .setNegativeButton("Cancel", null).create()
        d.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        d.show()
        e.requestFocus()
    }

    fun choose(c: Context, title: String?, items: List<String>, cb: (Int) -> Unit) {
        AlertDialog.Builder(c).setTitle(title).setItems(items.toTypedArray()) { _, i -> cb(i) }.show()
    }

    fun confirm(c: Context, msg: String, ok: String = "OK", cb: () -> Unit) {
        AlertDialog.Builder(c).setMessage(msg).setPositiveButton(ok) { _, _ -> cb() }.setNegativeButton("Cancel", null).show()
    }

    fun info(c: Context, msg: String) {
        AlertDialog.Builder(c).setMessage(msg).setPositiveButton("OK", null).show()
    }

    fun size(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> String.format("%.1f MB", bytes / 1048576.0)
    }

    fun gone(v: View, gone: Boolean) { v.visibility = if (gone) View.GONE else View.VISIBLE }
}
