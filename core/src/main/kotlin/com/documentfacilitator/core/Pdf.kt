package com.documentfacilitator.core

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.zip.Deflater
import java.util.zip.Inflater

class PdfName(val v: String) {
    override fun equals(other: Any?) = other is PdfName && other.v == v
    override fun hashCode() = v.hashCode()
    override fun toString() = "/$v"
}

class PdfRef(val num: Int, val gen: Int) {
    override fun equals(other: Any?) = other is PdfRef && other.num == num && other.gen == gen
    override fun hashCode() = num * 31 + gen
}

class PdfStr(val b: ByteArray) {
    /** Text value honouring the UTF-16BE BOM, otherwise treated as Latin-1 (close to PDFDocEncoding). */
    fun text(): String =
        if (b.size >= 2 && b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte()) String(b, 2, b.size - 2, Charsets.UTF_16BE)
        else String(b, Charsets.ISO_8859_1)

    companion object {
        fun of(s: String): PdfStr =
            if (s.all { it.code in 32..126 || it.code in 161..255 || it == '\n' || it == '\r' }) PdfStr(s.toByteArray(Charsets.ISO_8859_1))
            else PdfStr(byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + s.toByteArray(Charsets.UTF_16BE))
    }
}

class PdfDict : LinkedHashMap<String, Any?> {
    constructor() : super()
    constructor(m: Map<String, Any?>) : super(m)
}

class PdfStream(val dict: PdfDict, val off: Int, val len: Int)

class PdfException(msg: String) : Exception(msg)

private class Kw(val s: String)

private class PdfLexer(val b: ByteBuffer, var p: Int) {
    val n = b.limit()

    private fun ch(i: Int) = b.get(i).toInt() and 0xff
    private fun isWs(c: Int) = c == 32 || c == 10 || c == 13 || c == 9 || c == 12 || c == 0
    private fun isDelim(c: Int) = c == '('.code || c == ')'.code || c == '<'.code || c == '>'.code || c == '['.code || c == ']'.code || c == '{'.code || c == '}'.code || c == '/'.code || c == '%'.code

    fun ws() {
        while (p < n) {
            val c = ch(p)
            if (isWs(c)) p++
            else if (c == '%'.code) { while (p < n && ch(p) != 10 && ch(p) != 13) p++ }
            else break
        }
    }

    fun startsWith(s: String): Boolean {
        if (p + s.length > n) return false
        for (i in s.indices) if (ch(p + i) != s[i].code) return false
        return true
    }

    private fun hexVal(c: Int) = when (c) {
        in '0'.code..'9'.code -> c - '0'.code
        in 'a'.code..'f'.code -> c - 'a'.code + 10
        in 'A'.code..'F'.code -> c - 'A'.code + 10
        else -> -1
    }

    fun obj(depth: Int = 0): Any? {
        if (depth > 200) throw PdfException("nesting too deep")
        ws()
        if (p >= n) throw PdfException("unexpected end")
        val c = ch(p)
        when {
            c == '/'.code -> {
                p++
                val sb = StringBuilder()
                while (p < n) {
                    val d = ch(p)
                    if (isWs(d) || isDelim(d)) break
                    if (d == '#'.code && p + 2 < n && hexVal(ch(p + 1)) >= 0 && hexVal(ch(p + 2)) >= 0) {
                        sb.append((hexVal(ch(p + 1)) * 16 + hexVal(ch(p + 2))).toChar()); p += 3
                    } else { sb.append(d.toChar()); p++ }
                }
                return PdfName(sb.toString())
            }
            c == '('.code -> {
                p++
                val bo = ByteArrayOutputStream()
                var lvl = 1
                while (p < n) {
                    var d = ch(p++)
                    if (d == '\\'.code && p < n) {
                        d = ch(p++)
                        when (d) {
                            'n'.code -> bo.write(10)
                            'r'.code -> bo.write(13)
                            't'.code -> bo.write(9)
                            'b'.code -> bo.write(8)
                            'f'.code -> bo.write(12)
                            13 -> { if (p < n && ch(p) == 10) p++ }
                            10 -> {}
                            in '0'.code..'7'.code -> {
                                var v = d - '0'.code
                                var k = 0
                                while (k < 2 && p < n && ch(p) in '0'.code..'7'.code) { v = v * 8 + (ch(p++) - '0'.code); k++ }
                                bo.write(v and 0xff)
                            }
                            else -> bo.write(d)
                        }
                    } else if (d == '('.code) { lvl++; bo.write(d) }
                    else if (d == ')'.code) { lvl--; if (lvl == 0) break; bo.write(d) }
                    else bo.write(d)
                }
                return PdfStr(bo.toByteArray())
            }
            c == '<'.code -> {
                if (p + 1 < n && ch(p + 1) == '<'.code) {
                    p += 2
                    val d = PdfDict()
                    while (true) {
                        ws()
                        if (p >= n) throw PdfException("unterminated dict")
                        if (ch(p) == '>'.code) { p += if (p + 1 < n && ch(p + 1) == '>'.code) 2 else 1; break }
                        val k = obj(depth + 1)
                        if (k !is PdfName) { if (k is Kw) break; continue }
                        val v = obj(depth + 1)
                        if (v is Kw) break
                        d[k.v] = v
                    }
                    return d
                }
                p++
                val bo = ByteArrayOutputStream()
                var hi = -1
                while (p < n) {
                    val d = ch(p++)
                    if (d == '>'.code) break
                    val h = hexVal(d)
                    if (h < 0) continue
                    if (hi < 0) hi = h else { bo.write(hi * 16 + h); hi = -1 }
                }
                if (hi >= 0) bo.write(hi * 16)
                return PdfStr(bo.toByteArray())
            }
            c == '['.code -> {
                p++
                val a = ArrayList<Any?>()
                while (true) {
                    ws()
                    if (p >= n) throw PdfException("unterminated array")
                    if (ch(p) == ']'.code) { p++; break }
                    val v = obj(depth + 1)
                    if (v is Kw) { if (v.s.isEmpty()) p++; continue }
                    a.add(v)
                }
                return a
            }
            c == '+'.code || c == '-'.code || c == '.'.code || c in '0'.code..'9'.code -> {
                val s = p
                p++
                var real = c == '.'.code
                while (p < n) {
                    val d = ch(p)
                    if (d == '.'.code) real = true else if (d !in '0'.code..'9'.code && d != '-'.code) break
                    p++
                }
                val bytes = ByteArray(p - s) { b.get(s + it) }
                val t = String(bytes, Charsets.ISO_8859_1)
                if (real) return t.toDoubleOrNull() ?: 0.0
                val v = t.toLongOrNull() ?: (t.toDoubleOrNull() ?: 0.0).toLong()
                if (c in '0'.code..'9'.code) {
                    val save = p
                    ws()
                    if (p < n && ch(p) in '0'.code..'9'.code) {
                        val gs = p
                        while (p < n && ch(p) in '0'.code..'9'.code) p++
                        val ge = p
                        ws()
                        if (p < n && ch(p) == 'R'.code && (p + 1 >= n || isWs(ch(p + 1)) || isDelim(ch(p + 1)))) {
                            p++
                            val g = String(ByteArray(ge - gs) { b.get(gs + it) }, Charsets.ISO_8859_1).toIntOrNull() ?: 0
                            return PdfRef(v.toInt(), g)
                        }
                    }
                    p = save
                }
                return v
            }
            else -> {
                val s = p
                while (p < n && !isWs(ch(p)) && !isDelim(ch(p))) p++
                if (p == s) { p++; return Kw("") }
                val t = String(ByteArray(p - s) { b.get(s + it) }, Charsets.ISO_8859_1)
                return when (t) { "true" -> true; "false" -> false; "null" -> null; else -> Kw(t) }
            }
        }
    }

    fun long(): Long? { val o = obj(); return (o as? Long) }
    fun keyword(): String? { val o = obj(); return (o as? Kw)?.s }
}

/** Parsed structure of a PDF file: cross-reference data, object access and the page list. */
class PdfFile private constructor(val file: File, private val raf: RandomAccessFile, val buf: ByteBuffer) : Closeable {
    class Page(val ref: PdfRef, val dict: PdfDict, val box: FloatArray, val rotate: Int, val resources: Any?) {
        /** Size in points as displayed (after rotation). */
        val width get() = if (rotate == 90 || rotate == 270) box[3] - box[1] else box[2] - box[0]
        val height get() = if (rotate == 90 || rotate == 270) box[2] - box[0] else box[3] - box[1]

        /** Matrix mapping view space (origin top-left, y down, points) to PDF user space. */
        fun viewToPdf(): FloatArray = when (rotate) {
            90 -> floatArrayOf(0f, 1f, 1f, 0f, box[0], box[1])
            180 -> floatArrayOf(-1f, 0f, 0f, 1f, box[2], box[1])
            270 -> floatArrayOf(0f, -1f, -1f, 0f, box[2], box[3])
            else -> floatArrayOf(1f, 0f, 0f, -1f, box[0], box[3])
        }

        fun pdfToView(px: Float, py: Float): FloatArray = when (rotate) {
            90 -> floatArrayOf(py - box[1], px - box[0])
            180 -> floatArrayOf(box[2] - px, py - box[1])
            270 -> floatArrayOf(box[3] - py, box[2] - px)
            else -> floatArrayOf(px - box[0], box[3] - py)
        }
    }

    private var offs = LongArray(0)   // >=0 offset, -1 unknown, -2 free, -3 in object stream
    private var gens = IntArray(0)
    private var stmNum = IntArray(0)
    private var stmIdx = IntArray(0)
    private val cache = HashMap<Int, Any?>()
    private val stmCache = LinkedHashMap<Int, Pair<ByteBuffer, IntArray>>()
    var trailer = PdfDict()
        private set
    var startXref = -1L
        private set
    var usesXrefStream = false
        private set
    var repaired = false
        private set
    val encrypted get() = trailer.containsKey("Encrypt")
    var size = 0
        private set
    val pages = ArrayList<Page>()

    companion object {
        fun open(file: File): PdfFile {
            val raf = RandomAccessFile(file, "r")
            try {
                if (raf.length() > Int.MAX_VALUE) throw PdfException("file too large")
                val buf = raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length())
                val f = PdfFile(file, raf, buf)
                f.load()
                return f
            } catch (e: Throwable) {
                raf.close()
                throw if (e is PdfException) e else PdfException(e.toString())
            }
        }
    }

    override fun close() = raf.close()

    private fun ensure(n: Int) {
        if (n <= offs.size) return
        if (n > 8_000_000) throw PdfException("object count too large")
        val old = offs.size
        val cap = maxOf(n, old * 2, 64)
        offs = offs.copyOf(cap); gens = gens.copyOf(cap); stmNum = stmNum.copyOf(cap); stmIdx = stmIdx.copyOf(cap)
        for (i in old until cap) offs[i] = -1
    }

    private fun indexOf(pat: String, from: Int, to: Int): Int {
        val n = minOf(to, buf.limit()) - pat.length
        var i = maxOf(0, from)
        val first = pat[0].code.toByte()
        while (i <= n) {
            if (buf.get(i) == first) {
                var k = 1
                while (k < pat.length && buf.get(i + k) == pat[k].code.toByte()) k++
                if (k == pat.length) return i
            }
            i++
        }
        return -1
    }

    private fun lastIndexOf(pat: String, from: Int): Int {
        var i = minOf(from, buf.limit() - pat.length)
        while (i >= 0) {
            var k = 0
            while (k < pat.length && buf.get(i + k) == pat[k].code.toByte()) k++
            if (k == pat.length) return i
            i--
        }
        return -1
    }

    private fun load() {
        try {
            val sx = lastIndexOf("startxref", buf.limit())
            if (sx < 0) throw PdfException("no startxref")
            val lx = PdfLexer(buf, sx + 9)
            startXref = lx.long() ?: throw PdfException("bad startxref")
            var off = startXref
            val seen = HashSet<Long>()
            var first = true
            while (off >= 0 && off < buf.limit() && seen.add(off)) {
                val t = readXrefSection(off.toInt(), first)
                if (first) { trailer = t; first = false }
                (t["XRefStm"] as? Long)?.let { if (seen.add(it) && it in 0 until buf.limit()) readXrefSection(it.toInt(), false) }
                off = (t["Prev"] as? Long) ?: -1
            }
            if (resolve(trailer["Root"]) !is PdfDict) throw PdfException("no catalog")
            loadPages()
            if (pages.isEmpty()) throw PdfException("no pages")
        } catch (e: Exception) {
            repair()
        }
        size = maxOf((trailer["Size"] as? Long)?.toInt() ?: 0, lastUsed() + 1)
    }

    private fun lastUsed(): Int { var i = offs.size - 1; while (i >= 0 && offs[i] == -1L) i--; return i }

    private fun readXrefSection(off: Int, newest: Boolean): PdfDict {
        val lx = PdfLexer(buf, off)
        lx.ws()
        if (lx.startsWith("xref")) {
            lx.p += 4
            while (true) {
                lx.ws()
                if (lx.startsWith("trailer")) { lx.p += 7; break }
                val start = lx.long() ?: throw PdfException("bad xref")
                val count = lx.long() ?: throw PdfException("bad xref")
                if (count < 0 || start < 0 || start + count > 8_000_000) throw PdfException("bad xref")
                ensure((start + count).toInt())
                for (i in 0 until count.toInt()) {
                    val o = lx.long() ?: throw PdfException("bad xref entry")
                    val g = lx.long() ?: throw PdfException("bad xref entry")
                    val k = lx.keyword() ?: throw PdfException("bad xref entry")
                    val num = (start + i).toInt()
                    if (offs[num] == -1L) {
                        if (k == "n") { offs[num] = o; gens[num] = g.toInt() } else offs[num] = -2
                    }
                }
            }
            return lx.obj() as? PdfDict ?: throw PdfException("bad trailer")
        }
        // cross-reference stream
        lx.long() ?: throw PdfException("bad xref stream")
        lx.long(); lx.keyword()
        val d = lx.obj() as? PdfDict ?: throw PdfException("bad xref stream")
        val st = streamAfter(lx, d)
        val data = decode(st)
        if (newest) usesXrefStream = true
        val w = (d["W"] as? List<*>)?.map { (it as? Long)?.toInt() ?: 0 } ?: throw PdfException("no W")
        if (w.size < 3) throw PdfException("bad W")
        val sz = (d["Size"] as? Long)?.toInt() ?: 0
        val index = (d["Index"] as? List<*>)?.map { (it as? Long)?.toInt() ?: 0 } ?: listOf(0, sz)
        var p = 0
        val rec = w[0] + w[1] + w[2]
        var k = 0
        while (k + 1 < index.size) {
            val start = index[k]
            val count = index[k + 1]
            if (start < 0 || count < 0) throw PdfException("bad Index")
            ensure(start + count)
            for (i in 0 until count) {
                if (p + rec > data.size) break
                fun field(len: Int): Long { var v = 0L; for (j in 0 until len) v = (v shl 8) or (data[p++].toLong() and 0xff); return v }
                val type = if (w[0] == 0) 1L else field(w[0])
                val f2 = field(w[1])
                val f3 = field(w[2])
                val num = start + i
                if (offs[num] == -1L) when (type) {
                    0L -> offs[num] = -2
                    1L -> { offs[num] = f2; gens[num] = f3.toInt() }
                    2L -> { offs[num] = -3; stmNum[num] = f2.toInt(); stmIdx[num] = f3.toInt() }
                }
            }
            k += 2
        }
        return d
    }

    /** Last-resort recovery for files with a damaged cross-reference: scan for "N G obj". */
    private fun repair() {
        repaired = true
        offs = LongArray(0); gens = IntArray(0); stmNum = IntArray(0); stmIdx = IntArray(0)
        cache.clear(); pages.clear()
        val n = buf.limit()
        var i = 0
        var root: PdfRef? = null
        var tr: PdfDict? = null
        while (true) {
            val o = indexOf(" obj", i, n)
            if (o < 0) break
            i = o + 4
            // walk back over "num gen"
            var e = o
            var s = e - 1
            while (s >= 0 && buf.get(s) in '0'.code.toByte()..'9'.code.toByte()) s--
            if (s == e - 1 || s < 0) continue
            val gen = String(ByteArray(e - s - 1) { buf.get(s + 1 + it) }).toIntOrNull() ?: continue
            e = s
            while (e >= 0 && (buf.get(e).toInt() == 32 || buf.get(e).toInt() == 10 || buf.get(e).toInt() == 13)) e--
            s = e
            while (s >= 0 && buf.get(s) in '0'.code.toByte()..'9'.code.toByte()) s--
            if (s == e) continue
            val num = String(ByteArray(e - s) { buf.get(s + 1 + it) }).toIntOrNull() ?: continue
            if (num < 0 || num > 8_000_000) continue
            ensure(num + 1)
            offs[num] = (s + 1).toLong(); gens[num] = gen
        }
        var t = lastIndexOf("trailer", n)
        while (t >= 0 && tr == null) {
            try { (PdfLexer(buf, t + 7).obj() as? PdfDict)?.let { if (it["Root"] is PdfRef) tr = it } } catch (_: Exception) {}
            t = if (t > 0) lastIndexOf("trailer", t - 1) else -1
        }
        if (tr == null) {
            for (num in offs.indices) {
                if (offs[num] < 0) continue
                val o = try { getObject(num) } catch (_: Exception) { null }
                val d = (o as? PdfDict) ?: (o as? PdfStream)?.dict ?: continue
                val type = (d["Type"] as? PdfName)?.v
                if (type == "XRef" && d["Root"] is PdfRef) {
                    tr = d
                    try { // objects in object streams are only reachable through the xref stream
                        val data = decode(o as PdfStream)
                        val w = (d["W"] as List<*>).map { (it as Long).toInt() }
                        val index = (d["Index"] as? List<*>)?.map { (it as Long).toInt() } ?: listOf(0, (d["Size"] as Long).toInt())
                        var p = 0
                        var k = 0
                        while (k + 1 < index.size) {
                            ensure(index[k] + index[k + 1])
                            for (j in 0 until index[k + 1]) {
                                if (p + w[0] + w[1] + w[2] > data.size) break
                                fun field(len: Int): Long { var v = 0L; for (q in 0 until len) v = (v shl 8) or (data[p++].toLong() and 0xff); return v }
                                val type2 = if (w[0] == 0) 1L else field(w[0])
                                val f2 = field(w[1]); val f3 = field(w[2])
                                val nn = index[k] + j
                                if (type2 == 2L && offs[nn] == -1L) { offs[nn] = -3; stmNum[nn] = f2.toInt(); stmIdx[nn] = f3.toInt() }
                            }
                            k += 2
                        }
                    } catch (_: Exception) {}
                } else if (type == "Catalog") root = PdfRef(num, gens[num])
            }
        }
        trailer = tr ?: PdfDict().also { if (root != null) it["Root"] = root }
        if (resolve(trailer["Root"]) !is PdfDict) throw PdfException("not a readable PDF")
        loadPages()
        if (pages.isEmpty()) throw PdfException("no pages")
    }

    private fun streamAfter(lx: PdfLexer, d: PdfDict): PdfStream {
        lx.ws()
        if (!lx.startsWith("stream")) throw PdfException("stream expected")
        var p = lx.p + 6
        if (p < buf.limit() && buf.get(p).toInt() == 13) p++
        if (p < buf.limit() && buf.get(p).toInt() == 10) p++
        var len = (resolve(d["Length"]) as? Long)?.toInt() ?: -1
        var ok = false
        if (len >= 0 && p + len <= buf.limit()) {
            val l2 = PdfLexer(buf, p + len)
            l2.ws()
            ok = l2.startsWith("endstream")
        }
        if (!ok) {
            val e = indexOf("endstream", p, buf.limit())
            if (e < 0) throw PdfException("unterminated stream")
            len = e - p
            if (len > 0 && buf.get(p + len - 1).toInt() == 10) len--
            if (len > 0 && buf.get(p + len - 1).toInt() == 13) len--
        }
        return PdfStream(d, p, len)
    }

    fun gen(num: Int) = if (num < gens.size && num >= 0) gens[num] else 0

    fun getObject(num: Int): Any? {
        if (num < 0 || num >= offs.size) return null
        if (cache.containsKey(num)) return cache[num]
        val o: Any? = when {
            offs[num] >= 0 -> {
                val lx = PdfLexer(buf, offs[num].toInt())
                lx.long(); lx.long(); lx.keyword()
                val v = lx.obj()
                if (v is PdfDict) {
                    lx.ws()
                    if (lx.startsWith("stream")) streamAfter(lx, v) else v
                } else if (v is Kw) null else v
            }
            offs[num] == -3L -> fromObjStm(stmNum[num], stmIdx[num], num)
            else -> null
        }
        if (cache.size > 20000) cache.clear()
        cache[num] = o
        return o
    }

    private fun fromObjStm(stm: Int, idx: Int, num: Int): Any? {
        var e = stmCache[stm]
        if (e == null) {
            val s = getObject(stm) as? PdfStream ?: return null
            val data = decode(s)
            val n = (s.dict["N"] as? Long)?.toInt() ?: return null
            val first = (s.dict["First"] as? Long)?.toInt() ?: return null
            val bb = ByteBuffer.wrap(data)
            val lx = PdfLexer(bb, 0)
            val table = IntArray(n * 2)
            for (i in 0 until n) {
                table[i * 2] = (lx.long() ?: return null).toInt()
                table[i * 2 + 1] = first + (lx.long() ?: return null).toInt()
            }
            e = Pair(bb, table)
            if (stmCache.size >= 8) stmCache.remove(stmCache.keys.first())
            stmCache[stm] = e
        }
        val t = e.second
        var i = idx
        if (i < 0 || i * 2 >= t.size || t[i * 2] != num) {
            i = -1
            for (k in 0 until t.size / 2) if (t[k * 2] == num) { i = k; break }
            if (i < 0) return null
        }
        val v = PdfLexer(e.first, t[i * 2 + 1]).obj()
        return if (v is Kw) null else v
    }

    fun resolve(o: Any?): Any? {
        var v = o
        var guard = 0
        while (v is PdfRef && guard++ < 32) v = getObject(v.num)
        return if (v is PdfRef) null else v
    }

    fun dict(o: Any?): PdfDict? = when (val v = resolve(o)) { is PdfDict -> v; is PdfStream -> v.dict; else -> null }

    fun raw(s: PdfStream): ByteArray {
        val out = ByteArray(maxOf(0, s.len))
        val d = buf.duplicate()
        d.position(s.off)
        d.get(out)
        return out
    }

    fun decode(s: PdfStream): ByteArray {
        var data = raw(s)
        val f = resolve(s.dict["Filter"]) ?: return data
        val filters = if (f is List<*>) f.map { resolve(it) } else listOf(f)
        val pr = resolve(s.dict["DecodeParms"] ?: s.dict["DP"])
        val parms = if (pr is List<*>) pr.map { resolve(it) } else listOf(pr)
        for ((i, fl) in filters.withIndex()) {
            when ((fl as? PdfName)?.v) {
                "FlateDecode", "Fl" -> {
                    data = inflate(data)
                    val dp = parms.getOrNull(i) as? PdfDict
                    val pred = (dp?.get("Predictor") as? Long)?.toInt() ?: 1
                    if (pred >= 10) {
                        val cols = (dp?.get("Columns") as? Long)?.toInt() ?: 1
                        val colors = (dp?.get("Colors") as? Long)?.toInt() ?: 1
                        val bpc = (dp?.get("BitsPerComponent") as? Long)?.toInt() ?: 8
                        data = unPng(data, cols, colors, bpc)
                    } else if (pred == 2) throw PdfException("TIFF predictor not supported")
                }
                else -> throw PdfException("unsupported filter $fl")
            }
        }
        return data
    }

    private fun inflate(d: ByteArray): ByteArray {
        val inf = Inflater()
        inf.setInput(d)
        val bo = ByteArrayOutputStream(d.size * 3)
        val tmp = ByteArray(16384)
        try {
            while (!inf.finished()) {
                val n = inf.inflate(tmp)
                if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break
                bo.write(tmp, 0, n)
                if (bo.size() > 256 * 1024 * 1024) throw PdfException("stream too large")
            }
        } catch (e: java.util.zip.DataFormatException) {
            if (bo.size() == 0) throw PdfException("corrupt stream")
        } finally {
            inf.end()
        }
        return bo.toByteArray()
    }

    private fun unPng(d: ByteArray, cols: Int, colors: Int, bpc: Int): ByteArray {
        val bpp = maxOf(1, colors * bpc / 8)
        val row = (cols * colors * bpc + 7) / 8
        val rows = d.size / (row + 1)
        val out = ByteArray(rows * row)
        for (r in 0 until rows) {
            val t = d[r * (row + 1)].toInt()
            val src = r * (row + 1) + 1
            val dst = r * row
            for (i in 0 until row) {
                val x = d[src + i].toInt() and 0xff
                val a = if (i >= bpp) out[dst + i - bpp].toInt() and 0xff else 0
                val b = if (r > 0) out[dst - row + i].toInt() and 0xff else 0
                val c = if (r > 0 && i >= bpp) out[dst - row + i - bpp].toInt() and 0xff else 0
                val v = when (t) {
                    1 -> x + a
                    2 -> x + b
                    3 -> x + (a + b) / 2
                    4 -> {
                        val pp = a + b - c
                        val pa = Math.abs(pp - a); val pb = Math.abs(pp - b); val pc = Math.abs(pp - c)
                        x + (if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c)
                    }
                    else -> x
                }
                out[dst + i] = v.toByte()
            }
        }
        return out
    }

    private fun num(o: Any?): Float? = when (val v = resolve(o)) { is Long -> v.toFloat(); is Double -> v.toFloat(); else -> null }

    private fun box(o: Any?): FloatArray? {
        val a = resolve(o) as? List<*> ?: return null
        if (a.size < 4) return null
        val v = FloatArray(4) { num(a[it]) ?: return null }
        return floatArrayOf(minOf(v[0], v[2]), minOf(v[1], v[3]), maxOf(v[0], v[2]), maxOf(v[1], v[3]))
    }

    private fun loadPages() {
        pages.clear()
        val root = dict(trailer["Root"]) ?: return
        val top = root["Pages"] as? PdfRef ?: return
        class Node(val ref: PdfRef, val media: FloatArray?, val crop: FloatArray?, val rot: Int, val res: Any?)
        val stack = ArrayList<Node>()
        stack.add(Node(top, null, null, 0, null))
        val seen = HashSet<Int>()
        while (stack.isNotEmpty()) {
            val nd = stack.removeAt(stack.size - 1)
            if (!seen.add(nd.ref.num)) continue
            val d = resolve(nd.ref) as? PdfDict ?: continue
            val media = box(d["MediaBox"]) ?: nd.media
            val crop = box(d["CropBox"]) ?: nd.crop
            val rot = (resolve(d["Rotate"]) as? Long)?.toInt() ?: nd.rot
            val res = d["Resources"] ?: nd.res
            val kids = resolve(d["Kids"]) as? List<*>
            if (kids != null && (d["Type"] as? PdfName)?.v != "Page") {
                for (i in kids.indices.reversed()) (kids[i] as? PdfRef)?.let { stack.add(Node(it, media, crop, rot, res)) }
            } else {
                val m = media ?: floatArrayOf(0f, 0f, 612f, 792f)
                var b = m
                if (crop != null) {
                    val x0 = maxOf(m[0], crop[0]); val y0 = maxOf(m[1], crop[1]); val x1 = minOf(m[2], crop[2]); val y1 = minOf(m[3], crop[3])
                    if (x1 > x0 && y1 > y0) b = floatArrayOf(x0, y0, x1, y1)
                }
                pages.add(Page(nd.ref, d, b, ((rot % 360) + 360) % 360 / 90 * 90, res))
                if (pages.size > 100_000) return
            }
        }
    }
}

/** Collects new and changed objects and appends them to the file as an incremental update. */
class PdfUpdate(val pdf: PdfFile) {
    private val out = java.util.TreeMap<Int, ByteArray>()
    private val dirty = LinkedHashMap<Int, PdfDict>()
    private var next = pdf.size

    fun alloc() = PdfRef(next++, 0)

    /** Mutable copy of an existing dictionary object; it is written when the update is saved. */
    fun edit(ref: PdfRef): PdfDict? {
        dirty[ref.num]?.let { return it }
        val d = pdf.resolve(ref) as? PdfDict ?: return null
        val c = PdfDict(d)
        dirty[ref.num] = c
        return c
    }

    fun put(ref: PdfRef, o: Any?) {
        val bo = ByteArrayOutputStream()
        ser(o, bo)
        out[ref.num] = bo.toByteArray()
    }

    fun add(o: Any?): PdfRef { val r = alloc(); put(r, o); return r }

    fun addStream(dict: PdfDict, data: ByteArray, compress: Boolean = true): PdfRef {
        val r = alloc()
        var body = data
        val d = PdfDict(dict)
        if (compress && !d.containsKey("Filter")) {
            val def = Deflater(Deflater.DEFAULT_COMPRESSION)
            def.setInput(data); def.finish()
            val bo = ByteArrayOutputStream(data.size / 2 + 64)
            val tmp = ByteArray(16384)
            while (!def.finished()) bo.write(tmp, 0, def.deflate(tmp))
            def.end()
            body = bo.toByteArray()
            d["Filter"] = PdfName("FlateDecode")
        }
        d["Length"] = body.size.toLong()
        val bo = ByteArrayOutputStream(body.size + 128)
        ser(d, bo)
        bo.write("\nstream\n".toByteArray())
        bo.write(body)
        bo.write("\nendstream".toByteArray())
        out[r.num] = bo.toByteArray()
        return r
    }

    fun isEmpty() = out.isEmpty() && dirty.isEmpty()

    fun write(dest: File) {
        if (pdf.repaired || pdf.encrypted) throw PdfException("this PDF cannot be updated in place")
        for ((num, d) in dirty) { val bo = ByteArrayOutputStream(); ser(d, bo); out[num] = bo.toByteArray() }
        dirty.clear()
        dest.outputStream().buffered(65536).use { os ->
            var pos = 0L
            pdf.file.inputStream().use { ins ->
                val tmp = ByteArray(65536)
                while (true) { val n = ins.read(tmp); if (n < 0) break; os.write(tmp, 0, n); pos += n }
            }
            fun w(s: String) { val b = s.toByteArray(Charsets.ISO_8859_1); os.write(b); pos += b.size }
            w("\n")
            val offsets = java.util.TreeMap<Int, Long>()
            for ((num, body) in out) {
                offsets[num] = pos
                w("$num ${pdf.gen(num)} obj\n")
                os.write(body); pos += body.size
                w("\nendobj\n")
            }
            val tr = PdfDict()
            for (k in arrayOf("Root", "Info", "ID")) pdf.trailer[k]?.let { tr[k] = it }
            tr["Prev"] = pdf.startXref
            val xrefPos = pos
            if (pdf.usesXrefStream) {
                val self = next++
                offsets[self] = xrefPos
                tr["Type"] = PdfName("XRef")
                tr["Size"] = next.toLong()
                tr["W"] = arrayListOf<Any?>(1L, 4L, 2L)
                val index = ArrayList<Any?>()
                val data = ByteArrayOutputStream()
                var runStart = -1
                var prev = -2
                var count = 0
                for ((num, off) in offsets) {
                    if (num != prev + 1) { if (runStart >= 0) { index.add(runStart.toLong()); index.add(count.toLong()) }; runStart = num; count = 0 }
                    count++; prev = num
                    data.write(1)
                    data.write((off shr 24).toInt()); data.write((off shr 16).toInt()); data.write((off shr 8).toInt()); data.write(off.toInt())
                    val g = pdf.gen(num)
                    data.write(g shr 8); data.write(g)
                }
                index.add(runStart.toLong()); index.add(count.toLong())
                tr["Index"] = index
                tr["Length"] = data.size().toLong()
                w("$self 0 obj\n")
                val bo = ByteArrayOutputStream(); ser(tr, bo); os.write(bo.toByteArray())
                w("\nstream\n"); os.write(data.toByteArray()); w("\nendstream\nendobj\n")
            } else {
                tr["Size"] = next.toLong()
                val sb = StringBuilder("xref\n")
                val nums = offsets.keys.toList()
                var i = 0
                while (i < nums.size) {
                    var j = i
                    while (j + 1 < nums.size && nums[j + 1] == nums[j] + 1) j++
                    sb.append(nums[i]).append(' ').append(j - i + 1).append('\n')
                    for (k in i..j) sb.append(String.format("%010d %05d n \n", offsets[nums[k]], pdf.gen(nums[k])))
                    i = j + 1
                }
                sb.append("trailer\n")
                w(sb.toString())
                val bo = ByteArrayOutputStream(); ser(tr, bo); os.write(bo.toByteArray())
                w("\n")
            }
            w("startxref\n$xrefPos\n%%EOF\n")
        }
    }

    companion object {
        fun fmt(v: Double): String {
            if (v == Math.rint(v) && Math.abs(v) < 1e12) return v.toLong().toString()
            val s = String.format(java.util.Locale.US, "%.4f", v)
            return s.trimEnd('0').trimEnd('.')
        }

        fun fmt(v: Float) = fmt(v.toDouble())

        fun ser(o: Any?, bo: ByteArrayOutputStream) {
            when (o) {
                null -> bo.write("null".toByteArray())
                is Boolean -> bo.write(o.toString().toByteArray())
                is Long -> bo.write(o.toString().toByteArray())
                is Int -> bo.write(o.toString().toByteArray())
                is Double -> bo.write(fmt(o).toByteArray())
                is Float -> bo.write(fmt(o.toDouble()).toByteArray())
                is PdfRef -> bo.write("${o.num} ${o.gen} R".toByteArray())
                is PdfName -> {
                    bo.write('/'.code)
                    for (b in o.v.toByteArray(Charsets.ISO_8859_1)) {
                        val c = b.toInt() and 0xff
                        if (c < 33 || c > 126 || "()<>[]{}/%#".indexOf(c.toChar()) >= 0) bo.write(String.format("#%02X", c).toByteArray()) else bo.write(c)
                    }
                }
                is PdfStr -> {
                    bo.write('('.code)
                    for (b in o.b) {
                        val c = b.toInt() and 0xff
                        when {
                            c == '('.code || c == ')'.code || c == '\\'.code -> { bo.write('\\'.code); bo.write(c) }
                            c < 32 || c > 126 -> bo.write(String.format("\\%03o", c).toByteArray())
                            else -> bo.write(c)
                        }
                    }
                    bo.write(')'.code)
                }
                is Map<*, *> -> {
                    bo.write("<<".toByteArray())
                    for ((k, v) in o) {
                        ser(PdfName(k as String), bo)
                        bo.write(' '.code)
                        ser(v, bo)
                        bo.write(' '.code)
                    }
                    bo.write(">>".toByteArray())
                }
                is List<*> -> {
                    bo.write('['.code)
                    for ((i, v) in o.withIndex()) { if (i > 0) bo.write(' '.code); ser(v, bo) }
                    bo.write(']'.code)
                }
                is PdfStream -> throw PdfException("stream cannot be serialized inline")
                else -> throw PdfException("cannot serialize ${o.javaClass}")
            }
        }
    }
}
