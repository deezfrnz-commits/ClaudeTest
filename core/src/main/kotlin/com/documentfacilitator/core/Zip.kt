package com.documentfacilitator.core

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Random-access view of a zip container (docx/xlsx/pptx/odf/epub). Entries are read lazily. */
class ZipDoc(file: File) : Closeable {
    private val zf = ZipFile(file)

    companion object {
        /** Largest XML part we are willing to hold in memory as text. */
        const val MAX_TEXT = 24L * 1024 * 1024
    }

    fun has(path: String) = zf.getEntry(path) != null
    fun names(): List<String> = zf.entries().asSequence().map { it.name }.toList()
    fun size(path: String): Long = zf.getEntry(path)?.size ?: -1

    fun stream(path: String): InputStream? {
        val e = zf.getEntry(path) ?: return null
        return zf.getInputStream(e)
    }

    fun bytes(path: String, max: Long = MAX_TEXT): ByteArray? {
        val e = zf.getEntry(path) ?: return null
        if (e.size > max) return null
        return zf.getInputStream(e).use { it.readBytes() }
    }

    fun text(path: String): String? {
        val b = bytes(path) ?: return null
        var off = 0
        if (b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte()) off = 3
        return String(b, off, b.size - off, Charsets.UTF_8)
    }

    /** Relationship id -> resolved zip path for an OOXML part. */
    fun rels(part: String): Map<String, String> {
        val slash = part.lastIndexOf('/')
        val dir = if (slash < 0) "" else part.substring(0, slash + 1)
        val xml = text(dir + "_rels/" + part.substring(slash + 1) + ".rels") ?: return emptyMap()
        val out = HashMap<String, String>()
        val x = XmlScanner(xml)
        while (true) {
            val t = x.next()
            if (t == XmlScanner.EOF) break
            if (t == XmlScanner.START && x.name.endsWith("Relationship")) {
                val id = x.attr("Id") ?: continue
                val target = x.attr("Target") ?: continue
                if (x.attr("TargetMode") == "External") continue
                out[id] = resolvePath(dir, target)
            }
        }
        return out
    }

    override fun close() = zf.close()
}

fun resolvePath(dir: String, target: String): String {
    val raw = if (target.startsWith("/")) target.substring(1) else dir + target
    val parts = ArrayList<String>()
    for (p in raw.split('/')) {
        when (p) {
            "", "." -> {}
            ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
            else -> parts.add(p)
        }
    }
    return parts.joinToString("/")
}

object ZipRewrite {
    /**
     * Copies [src] to [out], replacing entries present in [replace] (a null value drops the entry)
     * and appending entries that did not exist before.
     */
    fun copy(src: File, out: File, replace: Map<String, ByteArray?>) {
        ZipFile(src).use { zf ->
            ZipOutputStream(out.outputStream().buffered()).use { zo ->
                val done = HashSet<String>()
                val buf = ByteArray(16384)
                for (e in zf.entries()) {
                    if (!done.add(e.name)) continue
                    if (replace.containsKey(e.name)) {
                        val data = replace[e.name] ?: continue
                        zo.putNextEntry(ZipEntry(e.name))
                        zo.write(data)
                        zo.closeEntry()
                    } else {
                        zo.putNextEntry(ZipEntry(e.name))
                        zf.getInputStream(e).use { ins ->
                            while (true) {
                                val n = ins.read(buf)
                                if (n < 0) break
                                zo.write(buf, 0, n)
                            }
                        }
                        zo.closeEntry()
                    }
                }
                for ((k, v) in replace) {
                    if (v != null && done.add(k)) {
                        zo.putNextEntry(ZipEntry(k))
                        zo.write(v)
                        zo.closeEntry()
                    }
                }
            }
        }
    }

    fun write(out: File, entries: Map<String, ByteArray>) {
        ZipOutputStream(out.outputStream().buffered()).use { zo ->
            for ((k, v) in entries) {
                zo.putNextEntry(ZipEntry(k))
                zo.write(v)
                zo.closeEntry()
            }
        }
    }
}

fun readAllCapped(ins: InputStream, max: Int): ByteArray {
    val bo = ByteArrayOutputStream()
    val buf = ByteArray(16384)
    var total = 0
    while (total < max) {
        val n = ins.read(buf, 0, minOf(buf.size, max - total))
        if (n < 0) break
        bo.write(buf, 0, n)
        total += n
    }
    return bo.toByteArray()
}
