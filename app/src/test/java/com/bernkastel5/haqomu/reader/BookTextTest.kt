package com.bernkastel5.haqomu.reader

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Диспетчер форматов: FB2, TXT, HTML, EPUB и честный отказ на мусоре. */
class BookTextTest {

    private fun file(name: String, content: String): File {
        val f = File.createTempFile("book", "-$name")
        f.deleteOnExit()
        f.writeText(content)
        return f
    }

    @Test
    fun readsFb2WithTitleAuthorAndSections() {
        val fb2 = """
            <?xml version="1.0" encoding="utf-8"?>
            <FictionBook>
              <description><title-info>
                <book-title>كتاب</book-title>
                <author><first-name>Иван</first-name><last-name>Петров</last-name></author>
              </title-info></description>
              <body>
                <section><title><p>Глава первая</p></title><p>Текст первой.</p></section>
                <section><title><p>Глава вторая</p></title><p>Текст второй.</p></section>
              </body>
            </FictionBook>
        """.trimIndent()
        val book = BookText.open(file("x.fb2", fb2))
        assertTrue(book != null)
        assertEquals("FB2", book!!.format)
        assertEquals("كتاب", book.title)
        assertEquals("Иван Петров", book.author)
        assertEquals(2, book.chapters.size)
        assertEquals("Глава первая", book.chapters[0].title)
        assertTrue(book.chapters[1].text.contains("второй"))
    }

    @Test
    fun readsTxt() {
        val text = "Первый абзац.\n\nВторой абзац."
        val book = BookText.open(file("x.txt", text))!!
        assertEquals("TXT", book.format)
        assertTrue(book.chapters.isNotEmpty())
        assertTrue(book.chapters[0].text.contains("Первый абзац."))
    }

    @Test
    fun readsHtml() {
        val html = "<html><head><title>Книга</title></head><body><p>Текст</p></body></html>"
        val book = BookText.open(file("x.html", html))!!
        assertEquals("HTML", book.format)
        assertEquals("Книга", book.title)
        assertTrue(book.chapters[0].text.contains("Текст"))
    }

    @Test
    fun emptyFileGivesNull() {
        assertNull(BookText.open(file("x.txt", "")))
    }

    @Test
    fun unsupportedContentFallsBackToPlainTextOrNull() {
        // расширения нет, содержимое не похоже на книгу — попытка как TXT,
        // а если и текста нет, честный null
        assertNull(BookText.open(file("x.bin", "")))
    }

    @Test
    fun splitLongCutsOnlyBetweenParagraphs() {
        val paragraph = "а".repeat(5000)
        val text = (1..10).joinToString("\n\n") { paragraph }
        val chapters = BookText.splitLong(text, "t")
        assertTrue(chapters.size > 1)
        // абзацы не разорваны: каждый кусок склеен из целых абзацев по 5000 знаков
        // (разделитель «\n\n» между абзацами внутри куска)
        assertTrue(chapters.all { c ->
            val parts = c.text.split("\n\n")
            parts.size == 2 && parts.all { it.length == 5000 }
        })
        assertTrue(chapters.sumOf { it.text.split("\n\n").size } == 10)
    }

    @Test
    fun splitLongKeepsOrder() {
        val chapters = BookText.splitLong("один\n\nдва\n\nтри", "t")
        assertEquals(1, chapters.size)
        assertEquals("один\n\nдва\n\nтри", chapters[0].text)
    }

    @Test
    fun extensionOfHandlesDotsAndCase() {
        assertEquals("epub", BookText.extensionOf("book.EPUB"))
        assertEquals("", BookText.extensionOf("book"))
        assertEquals("pdf", BookText.extensionOf("a.b.c.Pdf"))
    }

    @Test
    fun formatIsDetectedByContentWhenExtensionIsOdd() {
        val pdf = File.createTempFile("book", ".bin")
        pdf.deleteOnExit()
        pdf.writeBytes("%PDF-1.7\n".toByteArray())
        // без страниц книга пустая — null, но не исключение
        assertNull(BookText.open(pdf))
    }
}
