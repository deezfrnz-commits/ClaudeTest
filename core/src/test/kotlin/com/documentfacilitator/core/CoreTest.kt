package com.documentfacilitator.core

import org.json.JSONArray
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CoreTest {
    private val outDir = File("build/test-out").apply { mkdirs() }

    private fun fx(name: String): File {
        val f = File(outDir, "fx-$name")
        javaClass.getResourceAsStream("/fx/$name")!!.use { i -> f.outputStream().use { i.copyTo(it) } }
        return f
    }

    @Test
    fun xmlScanner() {
        val x = XmlScanner("<?xml version='1.0'?><a x=\"1 &amp; 2\" y='q'><!-- c --><b/>t&lt;x<c k=\"a/b\"/></a>")
        assertEquals(XmlScanner.START, x.next()); assertEquals("a", x.name); assertEquals("1 & 2", x.attr("x")); assertEquals("q", x.attr("y")); assertEquals(null, x.attr("z"))
        assertEquals(XmlScanner.START, x.next()); assertEquals("b", x.name); assertTrue(x.selfClosing)
        assertEquals(XmlScanner.END, x.next())
        assertEquals(XmlScanner.TEXT, x.next()); assertEquals("t<x", x.text)
        assertEquals(XmlScanner.START, x.next()); assertEquals("a/b", x.attr("k"))
        assertEquals(XmlScanner.END, x.next()); assertEquals(XmlScanner.END, x.next()); assertEquals(XmlScanner.EOF, x.next())
    }

    @Test
    fun detectKinds() {
        assertEquals(Kind.DOCX, DocType.detect(fx("sample.docx"), "whatever.bin"))
        assertEquals(Kind.XLSX, DocType.detect(fx("sample.xlsx"), "x"))
        assertEquals(Kind.PPTX, DocType.detect(fx("sample.pptx"), "x"))
        assertEquals(Kind.ODT, DocType.detect(fx("sample.odt"), "x"))
        assertEquals(Kind.ODS, DocType.detect(fx("sample.ods"), "x"))
        assertEquals(Kind.EPUB, DocType.detect(fx("sample.epub"), "x"))
        assertEquals(Kind.PDF, DocType.detect(fx("form.pdf"), "x"))
        assertEquals(Kind.DOC, DocType.detect(fx("sample.doc"), "x"))
        assertEquals(Kind.XLS, DocType.detect(fx("sample.xls"), "x"))
        assertEquals(Kind.RTF, DocType.detect(fx("sample.rtf"), "x"))
        assertEquals(Kind.IMAGE, DocType.detect(fx("red.png"), "x"))
        assertEquals(Kind.MARKDOWN, DocType.fromName("README.md"))
    }

    @Test
    fun docxToHtmlAndLosslessPatch() {
        val src = fx("sample.docx")
        val html = ZipDoc(src).use { Docx.toHtml(it) }.html
        File(outDir, "docx.html").writeText(html)
        assertTrue(html.contains("<h1>"), "heading")
        assertTrue(html.contains("font-weight:bold"), "bold run")
        assertTrue(html.contains("words &amp; &lt;symbols&gt;."))
        assertTrue(html.contains("class=\"li\""), "bullets")
        assertTrue(html.contains("li ol"), "numbered")
        assertTrue(html.contains("<table>") && html.contains("<td>"))
        assertTrue(html.contains("${RES_BASE}word/media/"), "image")
        assertTrue(Regex("<p data-e=\"0:\\d+\"").containsMatchIn(html), "empty paragraph addressable")

        // change the text of the run holding "Name: ____" and type into an empty table cell
        val run = Regex("data-r=\"0:(\\d+)\"[^>]*>Name: _").find(html)!!.groupValues[1].toInt()
        val cellPara = Regex("<td><p data-e=\"0:(\\d+)\"").find(html)!!.groupValues[1].toInt()
        val e = OoxmlPatch.PartEdits()
        e.runs[run] = "Name: Ada <Lovelace> & co   Date: 2024"
        e.paras[cellPara] = "typed into cell"
        val out = File(outDir, "patched.docx")
        OoxmlPatch.patch(src, out, "w", mapOf(Docx.PART to e))
        val html2 = ZipDoc(out).use { Docx.toHtml(it) }.html
        assertTrue(html2.contains("Name: Ada &lt;Lovelace&gt; &amp; co   Date: 2024"))
        assertTrue(html2.contains("typed into cell"))
        assertTrue(html2.contains("Tail paragraph."))
        // everything except the body part is byte-identical
        ZipDoc(src).use { a -> ZipDoc(out).use { b ->
            assertEquals(a.names().toSet(), b.names().toSet())
            for (n in a.names()) if (n != Docx.PART) assertTrue(a.bytes(n)!!.contentEquals(b.bytes(n)!!), n)
        } }
    }

    @Test
    fun pptxToHtmlAndPatch() {
        val src = fx("sample.pptx")
        val c = ZipDoc(src).use { Pptx.toHtml(it) }
        File(outDir, "pptx.html").writeText(c.html)
        assertEquals(2, c.parts.size)
        assertTrue(c.html.contains("<h2><span data-r=\"0:0\">Slide Title</span></h2>"), c.html.take(400))
        assertTrue(c.html.contains("Point A") && c.html.contains("Point B"))
        assertTrue(c.html.contains("${RES_BASE}ppt/media/"))
        val empty = Regex("data-e=\"1:(\\d+)\"").find(c.html)!!.groupValues[1].toInt()
        val e0 = OoxmlPatch.PartEdits().also { it.runs[0] = "New Title" }
        val e1 = OoxmlPatch.PartEdits().also { it.paras[empty] = "Filled box" }
        val out = File(outDir, "patched.pptx")
        OoxmlPatch.patch(src, out, "a", mapOf(c.parts[0] to e0, c.parts[1] to e1))
        val h2 = ZipDoc(out).use { Pptx.toHtml(it) }.html
        assertTrue(h2.contains("New Title") && h2.contains("Filled box") && h2.contains("Point B"))
    }

    @Test
    fun xlsxGridAndPatch() {
        val src = fx("sample.xlsx")
        val grids = ZipDoc(src).use { Xlsx.grids(it) }
        assertEquals(listOf("Data", "Second"), grids.map { it.name })
        val g = grids[0]
        assertEquals("Item", g.get(0, 0)); assertEquals("3", g.get(1, 1)); assertEquals("2.5", g.get(1, 2))
        assertEquals("2024-01-05", g.get(1, 3)); assertEquals("Gadget & Co", g.get(2, 0))
        assertEquals("=SUM(B2:B3)", g.formulas[(4 shl 16) or 1])
        assertEquals("lonely", grids[1].get(2, 2))
        val html = Grid.toHtml(grids)
        assertTrue(html.contains("data-f=\"=SUM(B2:B3)\""))

        val paths = ZipDoc(src).use { z -> Xlsx.sheets(z).map { it.path } }
        val out = File(outDir, "patched.xlsx")
        XlsxPatch.patch(src, out, mapOf(
            paths[0] to mapOf("B2" to "7", "A3" to "Renamed <x>", "E1" to "New col", "A9" to "new row", "C2" to "", "B4" to "=B2*2"),
            paths[1] to mapOf("A1" to "first", "D3" to "after")
        ))
        val g2 = ZipDoc(out).use { Xlsx.grids(it) }
        assertEquals("7", g2[0].get(1, 1)); assertEquals("Renamed <x>", g2[0].get(2, 0)); assertEquals("New col", g2[0].get(0, 4))
        assertEquals("new row", g2[0].get(8, 0)); assertEquals(null, g2[0].get(1, 2)); assertEquals("Item", g2[0].get(0, 0))
        assertEquals("=B2*2", g2[0].formulas[(3 shl 16) or 1]); assertEquals("=SUM(B2:B3)", g2[0].formulas[(4 shl 16) or 1])
        assertEquals("first", g2[1].get(0, 0)); assertEquals("after", g2[1].get(2, 3)); assertEquals("lonely", g2[1].get(2, 2))
    }

    @Test
    fun gridWritersAndCsv() {
        val g = Csv.parse("a;b;\"c;d\"\n1;\"say \"\"hi\"\"\";\n\nlast")
        assertEquals("c;d", g.get(0, 2)); assertEquals("say \"hi\"", g.get(1, 1)); assertEquals("last", g.get(3, 0))
        val rows = listOf(listOf("x", "1.5", "a,b"), listOf("", "", ""), listOf("=B1*2", "q\"r", ""))
        assertEquals("x,1.5,\"a,b\"\n\n=B1*2,\"q\"\"r\"\n", GridWriter.csv(rows))
        val out = File(outDir, "new.xlsx")
        GridWriter.xlsx(out, listOf(Pair("My/Sheet", rows)))
        val back = ZipDoc(out).use { Xlsx.grids(it) }
        assertEquals("My Sheet", back[0].name); assertEquals("1.5", back[0].get(0, 1)); assertEquals("a,b", back[0].get(0, 2)); assertEquals("=B1*2", back[0].formulas[(2 shl 16)])
    }

    @Test
    fun odfEpubRtfMarkdownLegacy() {
        val odt = ZipDoc(fx("sample.odt")).use { Odf.toHtml(it) }.html
        File(outDir, "odt.html").writeText(odt)
        assertTrue(odt.contains("Quarterly Report") && odt.contains("<table>") && odt.contains("Tail paragraph."), odt.take(300))
        assertTrue(odt.contains("font-weight:bold"))
        val ods = ZipDoc(fx("sample.ods")).use { Odf.grids(it) }
        assertEquals("Data", ods[0].name); assertEquals("Widget", ods[0].get(1, 0)); assertEquals("3", ods[0].get(1, 1)); assertEquals("lonely", ods[1].get(2, 2))
        val odp = ZipDoc(fx("sample.odp")).use { Odf.toHtml(it) }.html
        assertTrue(odp.contains("class=\"slide\"") && odp.contains("Slide Title") && odp.contains("Point B"))

        val epub = ZipDoc(fx("sample.epub")).use { Epub.toHtml(it) }.html
        assertTrue(epub.contains("Chapter One") && epub.contains("Chapter Two"))
        assertTrue(epub.contains("src=\"${RES_BASE}OEBPS/img/red.png\""), epub)
        assertFalse(epub.contains("script") || epub.contains("onload"))

        val rtf = Rtf.toHtml(fx("sample.rtf").readBytes())
        assertTrue(rtf.contains("text-align:center") && rtf.contains("Title Line"), rtf)
        assertTrue(rtf.contains("café € sign.") && rtf.contains("font-style:italic") && rtf.contains("Second para"), rtf)
        assertFalse(rtf.contains("Arial") || rtf.contains("generator"))

        val md = Markdown.toHtml("# Title\n\nSome **bold** and *it* `code<>`.\n\n- a\n- b\n  1. n1\n\n| h1 | h2 |\n|----|----|\n| c1 | c2 |\n\n```\nraw <b>\n```\n- [ ] todo\n")
        assertTrue(md.contains("<h1>Title</h1>") && md.contains("<b>bold</b>") && md.contains("<i>it</i>") && md.contains("<code>code&lt;&gt;</code>"), md)
        assertTrue(md.contains("<ul><li>a</li><li>b<ol><li>n1</li></ol>") && md.contains("<th>h1</th>") && md.contains("<td>c2</td>") && md.contains("raw &lt;b&gt;") && md.contains("☐ todo"), md)

        val doc = Legacy.docToHtml(fx("sample.doc")).html
        File(outDir, "doc.html").writeText(doc)
        assertTrue(doc.contains("Quarterly Report") && doc.contains("Plain text with bold and italic words &amp; &lt;symbols&gt;.") && doc.contains("Tail paragraph."), doc.take(500))
        val xls = Legacy.xlsGrids(fx("sample.xls"))!!
        assertEquals("Data", xls[0].name); assertEquals("Widget", xls[0].get(1, 0)); assertEquals("3", xls[0].get(1, 1)); assertEquals("2.5", xls[0].get(1, 2))
        assertEquals("2024-01-05", xls[0].get(1, 3)); assertEquals("13", xls[0].get(4, 1)); assertEquals("lonely", xls[1].get(2, 2))

        assertTrue(Html.body("<html><head><style>p{color:red}</style><script>x</script></head><body class=a><p onclick=\"e()\">hi</p></body></html>").let { it.contains("<p>hi</p>") && it.contains("color:red") && !it.contains("script") })
    }

    @Test
    fun docxWriterRoundTrip() {
        val blocks = JSONArray("""[
          {"t":"h1","runs":[{"s":"Heading"}]},
          {"t":"p","align":"center","runs":[{"s":"plain "},{"s":"bold","b":true,"c":"ff0000"},{"s":"line1\nline2","i":true}]},
          {"t":"li","runs":[{"s":"bullet"}]},
          {"t":"li","ol":true,"list":7,"runs":[{"s":"numbered"}]},
          {"t":"table","rows":[[[{"t":"p","runs":[{"s":"c1"}]}],[{"t":"p","runs":[{"s":"c2"}]}]],[[{"t":"p","runs":[{"s":"c3"}]}]]]},
          {"t":"p","runs":[{"img":"k1","w":60,"h":40}]}
        ]""")
        val out = File(outDir, "written.docx")
        DocxWriter.write(out, blocks, mapOf("k1" to DocxWriter.Image(fx("red.png").readBytes(), "png", 60, 40)))
        val html = ZipDoc(out).use { Docx.toHtml(it) }.html
        assertTrue(html.contains("<h1>") && html.contains("Heading") && html.contains("text-align:center") && html.contains("color:#ff0000"), html)
        assertTrue(html.contains("class=\"li\"") && html.contains("li ol") && html.contains("c3") && html.contains("word/media/image1.png") && html.contains("line1</span><br>"), html)
    }

    private fun pdfRoundTrip(name: String) {
        val src = fx(name)
        PdfFile.open(src).use { pdf ->
            assertFalse(pdf.repaired, "$name xref")
            assertTrue(pdf.pages.isNotEmpty())
            val marks = ArrayList<Mark>()
            marks.add(Mark(Mark.HIGHLIGHT, 0).also { it.x = 72f; it.y = 60f; it.w = 150f; it.h = 16f; it.color = 0xFFFFFF00.toInt() })
            marks.add(Mark(Mark.TEXT, 0).also { it.x = 100f; it.y = 100f; it.size = 14f; it.text = "Typed (text) \\ café\nsecond line"; it.color = 0xFF0000FF.toInt() })
            marks.add(Mark(Mark.INK, 0).also { it.pts = floatArrayOf(10f, 10f, 50f, 30f, 90f, 10f); it.size = 2f; it.color = 0xFFFF0000.toInt() })
            marks.add(Mark(Mark.CHECK, 0).also { it.x = 300f; it.y = 300f; it.w = 12f; it.h = 12f })
            marks.add(Mark(Mark.COVER, 0).also { it.x = 200f; it.y = 200f; it.w = 40f; it.h = 10f; it.color = 0xFFFFFFFF.toInt() })
            marks.add(Mark(Mark.BOX, 0).also { it.x = 5f; it.y = 5f; it.w = 30f; it.h = 30f; it.size = 1f })
            marks.add(Mark(Mark.IMAGE, 0).also { it.x = 400f; it.y = 40f; it.w = 60f; it.h = 40f; it.img = "im" })
            val im = PdfImage(2, 2, byteArrayOf(-1, 0, 0, 0, -1, 0, 0, 0, -1, -1, -1, 0), byteArrayOf(-1, -1, -1, 80), null)
            val json = Mark.listToJson(marks).toString()
            val back = Mark.listFromJson(JSONArray(json))
            assertEquals(marks.size, back.size); assertEquals("Typed (text) \\ café\nsecond line", back[1].text)
            val out = File(outDir, "marked-$name")
            PdfEdit.apply(pdf, out, back, { if (it == "im") im else null }, emptyMap())
            PdfFile.open(out).use { p2 ->
                assertFalse(p2.repaired, "$name output xref")
                assertEquals(pdf.pages.size, p2.pages.size)
                assertTrue((p2.pages[0].dict["Contents"] as List<*>).size >= 3)
                // second generation update on top of the first
                val out2 = File(outDir, "marked2-$name")
                PdfEdit.apply(p2, out2, listOf(Mark(Mark.TEXT, p2.pages.size - 1).also { it.x = 50f; it.y = 50f; it.text = "again" }), { null }, emptyMap())
                PdfFile.open(out2).use { p3 -> assertFalse(p3.repaired); assertEquals(pdf.pages.size, p3.pages.size) }
            }
        }
    }

    @Test fun pdfMarksClassicXref() = pdfRoundTrip("form.pdf")
    @Test fun pdfMarksXrefStream() = pdfRoundTrip("form_objstm.pdf")
    @Test fun pdfMarksRotated90() = pdfRoundTrip("rot90.pdf")
    @Test fun pdfMarksRotated270Cropped() = pdfRoundTrip("rot270crop.pdf")

    @Test
    fun pdfFormFields() {
        for (name in listOf("form.pdf", "form_objstm.pdf")) {
            PdfFile.open(fx(name)).use { pdf ->
                val fields = PdfEdit.fields(pdf)
                val byName = fields.groupBy { it.name }
                assertEquals(setOf("name", "agree", "color", "city", "notes"), byName.keys, name)
                assertEquals(FormField.TEXT, byName["name"]!![0].type)
                assertEquals(FormField.CHECK, byName["agree"]!![0].type)
                assertEquals(2, byName["color"]!!.size); assertEquals(FormField.RADIO, byName["color"]!![0].type)
                assertEquals(listOf("Paris", "Rome", "Oslo"), byName["city"]!![0].options)
                assertTrue(byName["notes"]!![0].multiline)
                val nm = byName["name"]!![0]
                assertEquals(130f, nm.x, 1.5f); assertEquals(792f - 672f - 20f, nm.y, 1.5f); assertEquals(200f, nm.w, 1.5f)
                val out = File(outDir, "filled-$name")
                PdfEdit.apply(pdf, out, emptyList(), { null }, mapOf(
                    nm.id to "Ada Lovelace", byName["agree"]!![0].id to "1", byName["color"]!![1].id to "1",
                    byName["city"]!![0].id to "Rome", byName["notes"]!![0].id to "line one\nline two is rather long and should wrap around inside the box nicely"
                ))
                PdfFile.open(out).use { p2 ->
                    val f2 = PdfEdit.fields(p2).groupBy { it.name }
                    assertEquals("Ada Lovelace", f2["name"]!![0].value)
                    assertEquals("1", f2["agree"]!![0].value)
                    assertEquals("", f2["color"]!![0].value); assertEquals("1", f2["color"]!![1].value)
                    assertEquals("Rome", f2["city"]!![0].value)
                }
            }
        }
    }

    @Test
    fun pdfRotateAndDelete() {
        PdfFile.open(fx("form.pdf")).use { pdf ->
            assertEquals(2, pdf.pages.size)
            val r = File(outDir, "rotated.pdf")
            PdfEdit.rotate(pdf, r, listOf(0), 90)
            PdfFile.open(r).use { p2 ->
                assertEquals(90, p2.pages[0].rotate); assertEquals(792f, p2.pages[0].width, 0.5f)
                val d = File(outDir, "deleted.pdf")
                PdfEdit.delete(p2, d, listOf(0))
                PdfFile.open(d).use { p3 -> assertEquals(1, p3.pages.size); assertEquals(0, p3.pages[0].rotate) }
            }
        }
    }

    @Test
    fun pdfRepairsBrokenXref() {
        val bytes = fx("form.pdf").readBytes()
        val s = String(bytes, Charsets.ISO_8859_1)
        val broken = File(outDir, "broken.pdf")
        broken.writeBytes(s.substring(0, s.lastIndexOf("startxref")).toByteArray(Charsets.ISO_8859_1))
        PdfFile.open(broken).use { pdf -> assertTrue(pdf.repaired); assertEquals(2, pdf.pages.size) }
    }

    private fun blanks(png: String): Pair<List<Blank>, Float> {
        val img = ImageIO.read(fx(png))
        val w = img.width; val h = img.height
        val lum = ByteArray(w * h)
        for (y in 0 until h) for (x in 0 until w) { val rgb = img.getRGB(x, y); lum[y * w + x] = (((rgb shr 16 and 0xff) * 3 + (rgb shr 8 and 0xff) * 6 + (rgb and 0xff)) / 10).toByte() }
        val ppp = 100f / 72f
        return Pair(FieldDetector.detect(lum, w, h, ppp), ppp)
    }

    @Test
    fun detectsBlanksOnFlatForm() {
        val (found, ppp) = blanks("flat-1.png")
        val desc = found.joinToString("\n") { "${it.kind} x=${(it.x / ppp).toInt()} y=${(it.y / ppp).toInt()} w=${(it.w / ppp).toInt()} h=${(it.h / ppp).toInt()}" }
        File(outDir, "blanks.txt").writeText(desc)
        // expected blanks in page points (top-left origin): kind, x, y(bottom edge), minimal width
        fun has(kind: Int, x: Float, bottom: Float, minW: Float) = found.any {
            it.kind == kind && Math.abs(it.x / ppp - x) < 6 && Math.abs((it.y + it.h) / ppp - bottom) < 6 && it.w / ppp >= minW
        }
        assertTrue(has(Blank.TEXT, 130f, 792f - 688f, 250f), "name line\n$desc")
        assertTrue(has(Blank.TEXT, 104f, 792f - 659f, 150f), "underscore run\n$desc")
        assertTrue(has(Blank.CHECK, 130f, 792f - 627f, 7f), "checkbox\n$desc")
        assertTrue(has(Blank.CHECK, 210f, 792f - 627f, 7f), "radio\n$desc")
        assertTrue(has(Blank.AREA, 72f, 792f - 470f, 380f), "comment box\n$desc")
        assertEquals(3, found.count { it.kind == Blank.TEXT && Math.abs((it.y + it.h) / ppp - (792f - 350f)) < 6 }, "empty table row\n$desc")
        assertTrue(has(Blank.TEXT, 72f, 792f - 280f, 280f), "captioned cell\n$desc")
        assertEquals(9, found.size, "no false positives (heading rule, table header, paragraph, underlined word, letters)\n$desc")
    }
}
