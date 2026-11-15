package com.documentfacilitator.core

import java.io.File
import java.util.zip.ZipFile

/** Families: 0 = fixed pages (PDF, images), 1 = reflowable rich content, 2 = spreadsheets, 3 = plain text. */
enum class Kind(val family: Int, val label: String) {
    PDF(0, "PDF"), IMAGE(0, "IMG"),
    TEXT(3, "TXT"), MARKDOWN(1, "MD"), HTML(1, "HTML"),
    DOCX(1, "DOCX"), ODT(1, "ODT"), RTF(1, "RTF"), DOC(1, "DOC"),
    XLSX(2, "XLSX"), ODS(2, "ODS"), CSV(2, "CSV"), XLS(2, "XLS"),
    PPTX(1, "PPTX"), ODP(1, "ODP"), PPT(1, "PPT"),
    EPUB(1, "EPUB"), UNKNOWN(3, "FILE");

    companion object {
        const val PAGED = 0
        const val FLOW = 1
        const val GRID = 2
        const val PLAIN = 3
    }
}

object DocType {
    private val textExt = setOf(
        "txt", "log", "json", "xml", "yaml", "yml", "ini", "cfg", "conf", "properties", "toml",
        "java", "kt", "kts", "py", "js", "ts", "c", "h", "cpp", "hpp", "cs", "go", "rs", "rb", "php",
        "sh", "bat", "sql", "css", "gradle", "tex", "srt", "vtt", "env", "lua", "swift", "dart", "ics", "vcf"
    )
    private val imageExt = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "heic", "heif")

    fun ext(name: String): String = name.substringAfterLast('.', "").lowercase()

    fun fromName(name: String): Kind = when (val e = ext(name)) {
        "pdf" -> Kind.PDF
        "md", "markdown" -> Kind.MARKDOWN
        "htm", "html", "xhtml" -> Kind.HTML
        "docx", "docm", "dotx" -> Kind.DOCX
        "odt", "ott" -> Kind.ODT
        "rtf" -> Kind.RTF
        "doc", "dot" -> Kind.DOC
        "xlsx", "xlsm", "xltx" -> Kind.XLSX
        "ods" -> Kind.ODS
        "csv", "tsv" -> Kind.CSV
        "xls" -> Kind.XLS
        "pptx", "ppsx", "pptm" -> Kind.PPTX
        "odp" -> Kind.ODP
        "ppt", "pps" -> Kind.PPT
        "epub" -> Kind.EPUB
        else -> if (e in imageExt) Kind.IMAGE else if (e in textExt) Kind.TEXT else Kind.UNKNOWN
    }

    /** Decides the kind from the name, falling back to (and cross-checking with) file content. */
    fun detect(file: File, name: String): Kind {
        val byName = fromName(name)
        val head = ByteArray(8)
        val n = try { file.inputStream().use { it.read(head) } } catch (_: Exception) { -1 }
        fun at(i: Int) = if (i < n) head[i].toInt() and 0xff else -1
        val sniff: Kind? = when {
            at(0) == 0x25 && at(1) == 0x50 && at(2) == 0x44 && at(3) == 0x46 -> Kind.PDF
            at(0) == 0x50 && at(1) == 0x4B && (at(2) == 3 || at(2) == 5) -> sniffZip(file)
            at(0) == 0xD0 && at(1) == 0xCF && at(2) == 0x11 && at(3) == 0xE0 -> when (byName) {
                Kind.XLS, Kind.PPT, Kind.DOC -> byName
                else -> Cfb.guess(file)
            }
            at(0) == 0x7B && at(1) == 0x5C && at(2) == 0x72 && at(3) == 0x74 -> Kind.RTF
            at(0) == 0x89 && at(1) == 0x50 && at(2) == 0x4E -> Kind.IMAGE
            at(0) == 0xFF && at(1) == 0xD8 -> Kind.IMAGE
            at(0) == 0x47 && at(1) == 0x49 && at(2) == 0x46 -> Kind.IMAGE
            at(0) == 0x42 && at(1) == 0x4D && byName == Kind.UNKNOWN -> Kind.IMAGE
            at(0) == 0x52 && at(1) == 0x49 && at(2) == 0x46 && at(3) == 0x46 && byName == Kind.UNKNOWN -> Kind.IMAGE
            else -> null
        }
        if (sniff != null) return sniff
        return if (byName == Kind.UNKNOWN) Kind.TEXT else byName
    }

    private fun sniffZip(file: File): Kind? = try {
        ZipFile(file).use { z ->
            when {
                z.getEntry("word/document.xml") != null -> Kind.DOCX
                z.getEntry("xl/workbook.xml") != null -> Kind.XLSX
                z.getEntry("ppt/presentation.xml") != null -> Kind.PPTX
                z.getEntry("META-INF/container.xml") != null -> Kind.EPUB
                z.getEntry("mimetype") != null -> {
                    val mt = z.getInputStream(z.getEntry("mimetype")).use { String(it.readBytes()) }
                    when {
                        mt.contains("opendocument.text") -> Kind.ODT
                        mt.contains("opendocument.spreadsheet") -> Kind.ODS
                        mt.contains("opendocument.presentation") -> Kind.ODP
                        mt.contains("epub") -> Kind.EPUB
                        else -> null
                    }
                }
                else -> null
            }
        }
    } catch (_: Exception) {
        null
    }

    fun mime(kind: Kind, name: String): String = when (kind) {
        Kind.PDF -> "application/pdf"
        Kind.IMAGE -> when (ext(name)) { "jpg", "jpeg" -> "image/jpeg"; "gif" -> "image/gif"; "webp" -> "image/webp"; else -> "image/png" }
        Kind.DOCX -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        Kind.XLSX -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        Kind.PPTX -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        Kind.HTML -> "text/html"
        Kind.CSV -> "text/csv"
        Kind.MARKDOWN -> "text/markdown"
        Kind.RTF -> "application/rtf"
        Kind.EPUB -> "application/epub+zip"
        else -> if (kind.family == Kind.PLAIN) "text/plain" else "application/octet-stream"
    }
}

/** Result of converting a document into the HTML shown by the flow/grid viewer. */
class Converted(val html: String, val parts: List<String> = emptyList(), val note: String? = null)
