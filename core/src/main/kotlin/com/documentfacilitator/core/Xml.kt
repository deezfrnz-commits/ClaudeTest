package com.documentfacilitator.core

/** Minimal, allocation-light XML pull scanner. Behaves identically on Android and the JVM. */
class XmlScanner(private val s: String) {
    companion object {
        const val EOF = 0
        const val START = 1
        const val END = 2
        const val TEXT = 3
    }

    var pos = 0
    var name = ""
    var text = ""
    var selfClosing = false
    var tagStart = 0
    var tagEnd = 0
    private var attrFrom = 0
    private var attrTo = 0
    private var pendingEnd = false

    fun next(): Int {
        if (pendingEnd) {
            pendingEnd = false
            return END
        }
        val n = s.length
        while (true) {
            if (pos >= n) return EOF
            if (s[pos] != '<') {
                var e = s.indexOf('<', pos)
                if (e < 0) e = n
                text = unescape(s.substring(pos, e))
                pos = e
                return TEXT
            }
            if (s.startsWith("<!--", pos)) {
                val e = s.indexOf("-->", pos + 4)
                pos = if (e < 0) n else e + 3
                continue
            }
            if (s.startsWith("<![CDATA[", pos)) {
                val e = s.indexOf("]]>", pos + 9)
                val ee = if (e < 0) n else e
                text = s.substring(pos + 9, ee)
                pos = minOf(n, ee + 3)
                return TEXT
            }
            if (s.startsWith("<?", pos)) {
                val e = s.indexOf("?>", pos + 2)
                pos = if (e < 0) n else e + 2
                continue
            }
            if (s.startsWith("<!", pos)) {
                var e = s.indexOf('>', pos)
                val b = s.indexOf('[', pos)
                if (b in 0 until (if (e < 0) n else e)) {
                    val c = s.indexOf("]>", b)
                    e = if (c < 0) -1 else c + 1
                }
                pos = if (e < 0) n else e + 1
                continue
            }
            tagStart = pos
            if (pos + 1 < n && s[pos + 1] == '/') {
                var e = s.indexOf('>', pos)
                if (e < 0) e = n
                name = s.substring(pos + 2, e).trim()
                pos = minOf(n, e + 1)
                tagEnd = pos
                return END
            }
            var i = pos + 1
            while (i < n && !s[i].isWhitespace() && s[i] != '>' && s[i] != '/') i++
            name = s.substring(pos + 1, i)
            attrFrom = i
            var q = '\u0000'
            while (i < n) {
                val c = s[i]
                if (q != '\u0000') {
                    if (c == q) q = '\u0000'
                } else if (c == '"' || c == '\'') q = c
                else if (c == '>') break
                i++
            }
            if (i > n) i = n
            selfClosing = i - 1 >= attrFrom && s[i - 1] == '/'
            attrTo = if (selfClosing) i - 1 else i
            pos = minOf(n, i + 1)
            tagEnd = pos
            if (selfClosing) pendingEnd = true
            return START
        }
    }

    /** Attribute of the current START tag, or null. */
    fun attr(want: String): String? {
        var i = attrFrom
        val end = attrTo
        while (i < end) {
            while (i < end && s[i].isWhitespace()) i++
            val ns = i
            while (i < end && s[i] != '=' && !s[i].isWhitespace()) i++
            val an = s.substring(ns, i)
            while (i < end && s[i] != '"' && s[i] != '\'') i++
            if (i >= end) return null
            val q = s[i]
            val vs = i + 1
            var ve = s.indexOf(q, vs)
            if (ve < 0 || ve > end) ve = end
            if (an == want) return unescape(s.substring(vs, ve))
            i = ve + 1
        }
        return null
    }

    /** Raw attribute text of the current START tag (without the name and closing slash). */
    fun rawAttrs(): String = s.substring(attrFrom, attrTo)
}

fun unescape(t: String): String {
    if (t.indexOf('&') < 0) return t
    val sb = StringBuilder(t.length)
    var i = 0
    while (i < t.length) {
        val c = t[i]
        if (c == '&') {
            val e = t.indexOf(';', i)
            if (e > i && e - i <= 10) {
                val ent = t.substring(i + 1, e)
                val rep: String? = when (ent) {
                    "amp" -> "&"
                    "lt" -> "<"
                    "gt" -> ">"
                    "quot" -> "\""
                    "apos" -> "'"
                    "nbsp" -> " "
                    else -> if (ent.startsWith("#")) {
                        try {
                            val cp = if (ent.startsWith("#x") || ent.startsWith("#X")) ent.substring(2).toInt(16) else ent.substring(1).toInt()
                            String(Character.toChars(cp))
                        } catch (_: Exception) {
                            null
                        }
                    } else null
                }
                if (rep != null) {
                    sb.append(rep)
                    i = e + 1
                    continue
                }
            }
        }
        sb.append(c)
        i++
    }
    return sb.toString()
}

fun esc(t: String): String {
    var need = false
    for (c in t) if (c == '&' || c == '<' || c == '>' || c == '"') { need = true; break }
    if (!need) return t
    val sb = StringBuilder(t.length + 16)
    for (c in t) when (c) {
        '&' -> sb.append("&amp;")
        '<' -> sb.append("&lt;")
        '>' -> sb.append("&gt;")
        '"' -> sb.append("&quot;")
        else -> sb.append(c)
    }
    return sb.toString()
}

/** Escapes text for XML, dropping characters that are illegal in XML 1.0. */
fun xmlText(t: String): String {
    val sb = StringBuilder(t.length + 16)
    for (c in t) when {
        c == '&' -> sb.append("&amp;")
        c == '<' -> sb.append("&lt;")
        c == '>' -> sb.append("&gt;")
        c == '"' -> sb.append("&quot;")
        c == '\n' || c == '\r' -> sb.append(' ')
        c < ' ' && c != '\t' -> {}
        else -> sb.append(c)
    }
    return sb.toString()
}
