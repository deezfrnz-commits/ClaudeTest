package com.documentfacilitator

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.documentfacilitator.core.DocType
import com.documentfacilitator.core.Kind
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** A document copied into the app's cache so it can be read with random access. */
class Loaded(val uri: Uri?, val file: File, val name: String, val kind: Kind)

/** One saved alternate state (draft, highlighted copy, ...) of a document. */
class StateInfo(val docKey: String, val id: String, val name: String, val time: Long, val bytes: Long, val auto: Boolean)

/**
 * App-private persistence: recent documents, alternate document states and the image pool used by
 * annotations. States store only what changed (overlay marks, edited text/HTML, gzip-compressed);
 * the original document is never duplicated unless its pages were restructured.
 */
object Store {
    private const val PREFS = "df"
    private const val MAX_RECENT = 18

    fun sha(s: String): String {
        val d = MessageDigest.getInstance("SHA-1").digest(s.toByteArray())
        val sb = StringBuilder()
        for (i in 0 until 10) sb.append(String.format("%02x", d[i]))
        return sb.toString()
    }

    fun gzip(b: ByteArray): ByteArray {
        val bo = ByteArrayOutputStream(b.size / 3 + 64)
        GZIPOutputStream(bo).use { it.write(b) }
        return bo.toByteArray()
    }

    fun gunzip(b: ByteArray): ByteArray = GZIPInputStream(ByteArrayInputStream(b)).use { it.readBytes() }

    // ---- opening ---------------------------------------------------------------------------

    fun displayName(c: Context, uri: Uri): String {
        var name: String? = null
        if (uri.scheme == "content") {
            try {
                c.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cur ->
                    if (cur.moveToFirst()) name = cur.getString(0)
                }
            } catch (_: Exception) {
            }
        }
        return name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "document"
    }

    fun openDir(c: Context) = File(c.cacheDir, "open").apply { mkdirs() }

    /** Copies the document behind [uri] into the cache and identifies its type. Call off the main thread. */
    fun load(c: Context, uri: Uri): Loaded {
        val name = displayName(c, uri)
        val f = File(openDir(c), "${System.nanoTime()}.${DocType.ext(name).ifEmpty { "bin" }}")
        val ins = (if (uri.scheme == "file") java.io.FileInputStream(uri.path!!) else c.contentResolver.openInputStream(uri))
            ?: throw java.io.IOException("cannot open $name")
        ins.use { i -> f.outputStream().use { o -> i.copyTo(o, 65536) } }
        return Loaded(uri, f, name, DocType.detect(f, name))
    }

    /** Removes stale working copies and unreferenced pool images. */
    fun sweep(c: Context) {
        val old = System.currentTimeMillis() - 2L * 24 * 3600 * 1000
        for (dir in arrayOf(openDir(c), File(c.cacheDir, "share"), File(c.cacheDir, "tmp"))) dir.listFiles()?.forEach { if (it.lastModified() < old) it.delete() }
        val used = HashSet<String>()
        statesRoot(c).listFiles()?.forEach { d -> d.listFiles()?.forEach { f -> if (f.name.endsWith(".img")) used.addAll(f.readLines()) } }
        imageDir(c).listFiles()?.forEach { if (it.name !in used && it.lastModified() < old) it.delete() }
    }

    // ---- recents ---------------------------------------------------------------------------

    class Recent(val uri: String, val name: String)

    fun recents(c: Context): List<Recent> {
        val out = ArrayList<Recent>()
        try {
            val a = JSONArray(c.getSharedPreferences(PREFS, 0).getString("recent", "[]"))
            for (i in 0 until a.length()) { val o = a.getJSONObject(i); out.add(Recent(o.getString("u"), o.getString("n"))) }
        } catch (_: Exception) {
        }
        return out
    }

    private fun saveRecents(c: Context, list: List<Recent>) {
        val a = JSONArray()
        for (r in list.take(MAX_RECENT)) a.put(JSONObject().put("u", r.uri).put("n", r.name))
        c.getSharedPreferences(PREFS, 0).edit().putString("recent", a.toString()).apply()
    }

    fun addRecent(c: Context, uri: Uri, name: String) {
        val list = ArrayList(recents(c).filter { it.uri != uri.toString() })
        list.add(0, Recent(uri.toString(), name))
        saveRecents(c, list)
    }

    fun removeRecent(c: Context, uri: String) = saveRecents(c, recents(c).filter { it.uri != uri })

    fun pref(c: Context, key: String, def: Boolean) = c.getSharedPreferences(PREFS, 0).getBoolean(key, def)
    fun setPref(c: Context, key: String, v: Boolean) = c.getSharedPreferences(PREFS, 0).edit().putBoolean(key, v).apply()

    // ---- image pool ------------------------------------------------------------------------

    fun imageDir(c: Context) = File(c.filesDir, "img").apply { mkdirs() }

    /** Stores image bytes once (content-addressed) and returns their id. */
    fun putImage(c: Context, bytes: ByteArray, ext: String): String {
        val d = MessageDigest.getInstance("SHA-1").digest(bytes)
        val sb = StringBuilder()
        for (i in 0 until 12) sb.append(String.format("%02x", d[i]))
        val id = "$sb.$ext"
        val f = File(imageDir(c), id)
        if (!f.exists()) f.writeBytes(bytes)
        return id
    }

    fun imageFile(c: Context, id: String) = File(imageDir(c), id.replace("/", ""))

    // ---- states ----------------------------------------------------------------------------

    fun statesRoot(c: Context) = File(c.filesDir, "states").apply { mkdirs() }
    fun docKey(uri: Uri?, name: String) = sha(uri?.toString() ?: "new:$name")
    private fun docDir(c: Context, key: String) = File(statesRoot(c), key)

    fun states(c: Context, key: String): List<StateInfo> {
        val out = ArrayList<StateInfo>()
        docDir(c, key).listFiles()?.forEach { f ->
            if (!f.name.endsWith(".json") || f.name == "meta.json") return@forEach
            try {
                val o = JSONObject(f.readText())
                val id = f.name.removeSuffix(".json")
                var bytes = File(f.parentFile, "$id.dat").length() + File(f.parentFile, "$id.base").length()
                File(f.parentFile, "$id.img").takeIf { it.exists() }?.readLines()?.forEach { bytes += imageFile(c, it).length() }
                out.add(StateInfo(key, id, o.getString("name"), o.getLong("time"), bytes, o.optBoolean("auto")))
            } catch (_: Exception) {
            }
        }
        return out.sortedByDescending { it.time }
    }

    /**
     * Saves a state. [payload] is the compact change set; [base] is only given when the page
     * structure itself changed and the working file must be kept; [images] are pool ids to retain.
     */
    fun saveState(c: Context, key: String, doc: Loaded, name: String, auto: Boolean, payload: ByteArray, base: File?, images: Collection<String>): StateInfo {
        val dir = docDir(c, key).apply { mkdirs() }
        File(dir, "meta.json").writeText(JSONObject().put("uri", doc.uri?.toString() ?: "").put("name", doc.name).put("kind", doc.kind.name).toString())
        val existing = states(c, key).firstOrNull { it.name == name }
        val id = existing?.id ?: java.lang.Long.toString(System.currentTimeMillis(), 36)
        File(dir, "$id.dat").writeBytes(gzip(payload))
        val bf = File(dir, "$id.base")
        if (base != null) base.copyTo(bf, true) else bf.delete()
        val imf = File(dir, "$id.img")
        if (images.isNotEmpty()) imf.writeText(images.joinToString("\n")) else imf.delete()
        val now = System.currentTimeMillis()
        File(dir, "$id.json").writeText(JSONObject().put("name", name).put("time", now).put("auto", auto).toString())
        return StateInfo(key, id, name, now, 0, auto)
    }

    fun statePayload(c: Context, s: StateInfo): ByteArray = gunzip(File(docDir(c, s.docKey), "${s.id}.dat").readBytes())
    fun stateBase(c: Context, s: StateInfo): File? = File(docDir(c, s.docKey), "${s.id}.base").takeIf { it.exists() }

    fun deleteState(c: Context, s: StateInfo) {
        val dir = docDir(c, s.docKey)
        for (ext in arrayOf("json", "dat", "base", "img")) File(dir, "${s.id}.$ext").delete()
        if (states(c, s.docKey).isEmpty()) dir.deleteRecursively()
    }

    fun renameState(c: Context, s: StateInfo, name: String) {
        val f = File(docDir(c, s.docKey), "${s.id}.json")
        f.writeText(JSONObject(f.readText()).put("name", name).put("auto", false).toString())
    }

    class DocStates(val key: String, val uri: String, val name: String, val count: Int, val bytes: Long)

    fun allDocs(c: Context): List<DocStates> {
        val out = ArrayList<DocStates>()
        statesRoot(c).listFiles()?.forEach { d ->
            try {
                val o = JSONObject(File(d, "meta.json").readText())
                val st = states(c, d.name)
                if (st.isNotEmpty()) out.add(DocStates(d.name, o.getString("uri"), o.getString("name"), st.size, st.sumOf { it.bytes }))
            } catch (_: Exception) {
            }
        }
        return out.sortedBy { it.name.lowercase() }
    }

    fun tmp(c: Context, name: String): File = File(File(c.cacheDir, "tmp").apply { mkdirs() }, name)
}
