package com.bernkastel5.haqomu.reader

import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PDF с текстовым слоем: разбор объектов, распаковка FlateDecode, CMap и операторы
 * показа текста. Собираем минимальные, но настоящие PDF в тесте — так проверяется
 * весь путь до строки текста, без Android и без сторонних библиотек.
 */
class PdfTextTest {

    private fun deflate(data: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        deflater.setInput(data)
        deflater.finish()
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (!deflater.finished()) {
            val n = deflater.deflate(buffer)
            out.write(buffer, 0, n)
        }
        deflater.end()
        return out.toByteArray()
    }

    /** Собирает PDF из объектов: номер → тело (без "N 0 obj"). */
    private fun pdf(objects: Map<Int, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("%PDF-1.7\n".toByteArray(Charsets.ISO_8859_1))
        for ((num, body) in objects.toSortedMap()) {
            out.write("$num 0 obj\n".toByteArray(Charsets.ISO_8859_1))
            out.write(body)
            out.write("\nendobj\n".toByteArray(Charsets.ISO_8859_1))
        }
        out.write("trailer\n<< /Root 1 0 R >>\n%%EOF\n".toByteArray(Charsets.ISO_8859_1))
        return out.toByteArray()
    }

    private fun str(s: String) = s.toByteArray(Charsets.ISO_8859_1)

    private fun stream(dict: String, data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write((dict + "\nstream\n").toByteArray(Charsets.ISO_8859_1))
        out.write(data)
        out.write("\nendstream".toByteArray(Charsets.ISO_8859_1))
        return out.toByteArray()
    }

    @Test
    fun readsPlainTextWithLiteralStrings() {
        val content = "BT /F1 12 Tf 72 720 Td (Hello PDF) Tj ET"
        val doc = pdf(
            mapOf(
                1 to str("<< /Type /Catalog /Pages 2 0 R >>"),
                2 to str("<< /Type /Pages /Kids [3 0 R] /Count 1 >>"),
                3 to str("<< /Type /Page /Parent 2 0 R /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>"),
                4 to stream("<< /Length ${content.length} >>", str(content)),
                5 to str("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"),
            )
        )
        val pages = PdfText.extract(doc)
        assertEquals(1, pages.size)
        assertEquals("Hello PDF", pages[0])
    }

    @Test
    fun unpacksFlateCompressedContent() {
        val content = "BT (Compressed text) Tj ET"
        val packed = deflate(str(content))
        val doc = pdf(
            mapOf(
                1 to str("<< /Type /Catalog /Pages 2 0 R >>"),
                2 to str("<< /Type /Pages /Kids [3 0 R] /Count 1 >>"),
                3 to str("<< /Type /Page /Parent 2 0 R /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>"),
                4 to stream("<< /Length ${packed.size} /Filter /FlateDecode >>", packed),
                5 to str("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"),
            )
        )
        assertEquals("Compressed text", PdfText.extract(doc).first())
    }

    @Test
    fun decodesIdentityHThroughToUnicodeCMap() {
        // арабское слово «ذهب» через ToUnicode: коды 1..4 → ذ ه ب
        val cmap = """
            1 begincmap
            16 beginbfchar
            <0001> <0630>
            <0002> <0647>
            <0003> <0628>
            <0004> <0020>
            endbfchar
            endcmap
        """.trimIndent()
        val content = "BT /F1 12 Tf <0001000200030004> Tj ET"
        val doc = pdf(
            mapOf(
                1 to str("<< /Type /Catalog /Pages 2 0 R >>"),
                2 to str("<< /Type /Pages /Kids [3 0 R] /Count 1 >>"),
                3 to str("<< /Type /Page /Parent 2 0 R /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>"),
                4 to stream("<< /Length ${content.length} >>", str(content)),
                5 to str("<< /Type /Font /Subtype /Type0 /Encoding /Identity-H /ToUnicode 6 0 R >>"),
                6 to stream("<< /Length ${cmap.length} >>", str(cmap)),
            )
        )
        val text = PdfText.extract(doc).first()
        assertEquals("\u0630\u0647\u0628", text)
    }

    @Test
    fun skipsTwoByteFontWithoutCMap() {
        // Identity-H без ToUnicode: подставить латиницу значило бы показать мусор,
        // поэтому строки такого шрифта пропускаются, а обычный шрифт читается
        val content = "BT /F1 12 Tf <000100020003> Tj /F2 12 Tf (OK) Tj ET"
        val doc = pdf(
            mapOf(
                1 to str("<< /Type /Catalog /Pages 2 0 R >>"),
                2 to str("<< /Type /Pages /Kids [3 0 R] /Count 1 >>"),
                3 to str("<< /Type /Page /Parent 2 0 R /Contents 4 0 R /Resources << /Font << /F1 5 0 R /F2 6 0 R >> >> >>"),
                4 to stream("<< /Length ${content.length} >>", str(content)),
                5 to str("<< /Type /Font /Subtype /Type0 /Encoding /Identity-H >>"),
                6 to str("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"),
            )
        )
        assertEquals("OK", PdfText.extract(doc).first())
    }

    @Test
    fun tjArrayJoinsStringsAndAddsKerningSpaces() {
        val content = "BT /F1 12 Tf [(Hel) -20 (lo) -400 (world)] TJ ET"
        val doc = pdf(
            mapOf(
                1 to str("<< /Type /Catalog /Pages 2 0 R >>"),
                2 to str("<< /Type /Pages /Kids [3 0 R] /Count 1 >>"),
                3 to str("<< /Type /Page /Parent 2 0 R /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>"),
                4 to stream("<< /Length ${content.length} >>", str(content)),
                5 to str("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"),
            )
        )
        assertEquals("Hello world", PdfText.extract(doc).first())
    }

    @Test
    fun readsObjectsInsideObjectStreams() {
        // современный PDF: страницы и шрифт лежат в сжатом /ObjStm
        val pageBody = "<< /Type /Page /Parent 2 0 R /Contents 4 0 R /Resources << /Font << /F1 6 0 R >> >> >>"
        val fontBody = "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"
        // первая часть ObjStm: пары «номер объекта, смещение»; тела идут после /First
        val headerOffset = 2
        val headerText = "5 0 6 ${headerOffset + pageBody.length + 1} "
        val first = headerText.length
        val inner = headerText + pageBody + " " + fontBody
        val packed = deflate(str(inner))
        val content = "BT (From object stream) Tj ET"
        val doc = pdf(
            mapOf(
                1 to str("<< /Type /Catalog /Pages 2 0 R >>"),
                2 to str("<< /Type /Pages /Kids [5 0 R] /Count 1 >>"),
                4 to stream("<< /Length ${content.length} >>", str(content)),
                7 to stream(
                    "<< /Type /ObjStm /N 2 /First $first /Length ${packed.size} /Filter /FlateDecode >>",
                    packed,
                ),
            )
        )
        assertEquals("From object stream", PdfText.extract(doc).first())
    }

    @Test
    fun multiplePagesAreInOrder() {
        fun page(contentNum: Int, fontNum: Int) = str(
            "<< /Type /Page /Parent 2 0 R /Contents $contentNum 0 R /Resources << /Font << /F1 $fontNum 0 R >> >> >>"
        )
        val p1 = "BT (first page) Tj ET"
        val p2 = "BT (second page) Tj ET"
        val doc = pdf(
            mapOf(
                1 to str("<< /Type /Catalog /Pages 2 0 R >>"),
                2 to str("<< /Type /Pages /Kids [3 0 R 5 0 R] /Count 2 >>"),
                3 to page(4, 7),
                4 to stream("<< /Length ${p1.length} >>", str(p1)),
                5 to page(6, 7),
                6 to stream("<< /Length ${p2.length} >>", str(p2)),
                7 to str("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"),
            )
        )
        assertEquals(listOf("first page", "second page"), PdfText.extract(doc))
    }

    @Test
    fun brokenPdfGivesEmptyList() {
        assertTrue(PdfText.extract(ByteArray(0)).isEmpty())
        assertTrue(PdfText.extract("не pdf".toByteArray()).isEmpty())
    }

    // ------------------------------------------------------------- CMap

    @Test
    fun cmapParsesBfcharAndBfrange() {
        val cmap = """
            begincmap
            4 beginbfchar
            <0001> <0630>
            <0002> <0647>
            endbfchar
            2 beginbfrange
            <0010> <0012> <0661>
            <0020> <0021> [<0627> <0628>]
            endbfrange
            endcmap
        """.trimIndent()
        val parsed = PdfText.PdfCMap.parse(cmap.toByteArray(Charsets.ISO_8859_1))
        assertEquals("\u0630", parsed.text(1))
        assertEquals("\u0647", parsed.text(2))
        assertEquals("\u0661", parsed.text(0x10))
        assertEquals("\u0663", parsed.text(0x12))
        assertEquals("\u0627", parsed.text(0x20))
        assertEquals("\u0628", parsed.text(0x21))
        assertNull(parsed.text(0x30))
    }

    @Test
    fun cmapHexDecodingIsUtf16Be() {
        assertEquals("\u0630", PdfText.PdfCMap.hexToText("0630"))
        assertEquals("A", PdfText.PdfCMap.hexToText("0041"))
        assertEquals("", PdfText.PdfCMap.hexToText(""))
    }
}
