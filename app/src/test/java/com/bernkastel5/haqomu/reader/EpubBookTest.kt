package com.bernkastel5.haqomu.reader

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * EPUB разбирается своим кодом: контейнер → OPF → spine → главы. Проверяем порядок
 * из spine, а не из манифеста, оглавление из nav и то, что мусор не читается.
 */
class EpubBookTest {

    private fun epub(vararg entries: Pair<String, String>): File {
        val file = File.createTempFile("book", ".epub")
        file.deleteOnExit()
        ZipOutputStream(file.outputStream()).use { zip ->
            for ((name, content) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return file
    }

    private val container = """
        <?xml version="1.0"?>
        <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
          <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
        </container>
    """.trimIndent()

    private val opf = """
        <?xml version="1.0"?>
        <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
          <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
            <dc:title>Книга</dc:title>
            <dc:creator>Автор</dc:creator>
          </metadata>
          <manifest>
            <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
            <item id="c1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
            <item id="c2" href="ch2.xhtml" media-type="application/xhtml+xml"/>
            <item id="css" href="style.css" media-type="text/css"/>
          </manifest>
          <spine><itemref idref="c2"/><itemref idref="c1"/></spine>
        </package>
    """.trimIndent()

    private val nav = """
        <html xmlns="http://www.w3.org/1999/xhtml"><body>
          <nav epub:type="toc"><ol>
            <li><a href="ch1.xhtml">Первая глава</a></li>
            <li><a href="ch2.xhtml">Вторая глава</a></li>
          </ol></nav>
        </body></html>
    """.trimIndent()

    private fun sample() = epub(
        "META-INF/container.xml" to container,
        "OEBPS/content.opf" to opf,
        "OEBPS/nav.xhtml" to nav,
        "OEBPS/ch1.xhtml" to "<html><body><h1>Заголовок</h1><p>Текст первой главы.</p></body></html>",
        "OEBPS/ch2.xhtml" to "<html><body><p>Текст второй главы.</p></body></html>",
        "OEBPS/style.css" to "p { color: red }",
    )

    @Test
    fun readsTitleAuthorAndChapters() {
        val book = EpubBook.open(sample())
        assertNotNull(book)
        book!!.use {
            assertEquals("Книга", it.title)
            assertEquals("Автор", it.author)
            assertEquals(2, it.chapters.size)
        }
    }

    @Test
    fun followsSpineOrderNotManifestOrder() {
        EpubBook.open(sample())!!.use { book ->
            // spine: c2, c1 — так и должно быть, манифест тут ни при чём
            assertTrue(book.chapters[0].text.contains("второй"))
            assertTrue(book.chapters[1].text.contains("первой"))
        }
    }

    @Test
    fun usesTocTitles() {
        EpubBook.open(sample())!!.use { book ->
            val titles = book.chapters.map { it.title }
            assertTrue(titles.contains("Первая глава"))
            assertTrue(titles.contains("Вторая глава"))
        }
    }

    @Test
    fun chapterTextHasNoMarkup() {
        EpubBook.open(sample())!!.use { book ->
            val ch = book.chapters.first { it.text.contains("первой") }
            assertTrue("<p>" !in ch.text)
            assertTrue("Текст первой главы." in ch.text)
        }
    }

    @Test
    fun cssAndOtherAssetsAreNotChapters() {
        EpubBook.open(sample())!!.use { book ->
            assertEquals(2, book.chapters.size)
            assertTrue(book.chapters.none { it.text.contains("color: red") })
        }
    }

    @Test
    fun brokenFileGivesNull() {
        assertNull(EpubBook.open(File.createTempFile("epub", ".epub")))
        val notZip = File.createTempFile("bad", ".epub").apply { writeText("не zip") }
        notZip.deleteOnExit()
        assertNull(EpubBook.open(notZip))
    }

    @Test
    fun resolveHandlesDotSegments() {
        assertEquals("OEBPS/text/ch1.xhtml", EpubBook.resolve("OEBPS", "text/ch1.xhtml"))
        assertEquals("OEBPS/ch1.xhtml", EpubBook.resolve("OEBPS/text", "../ch1.xhtml"))
        assertEquals("root.xhtml", EpubBook.resolve("", "./root.xhtml"))
    }

    @Test
    fun attributesAreParsed() {
        val attrs = EpubBook.attributes("""<item id="a" href="b.xhtml" media-type="application/xhtml+xml"/>""")
        assertEquals("a", attrs["id"])
        assertEquals("b.xhtml", attrs["href"])
    }

    @Test
    fun chapterTitleFromFirstHeading() {
        assertEquals("Глава", EpubBook.chapterTitle("<h2 class='x'>Глава</h2>"))
        assertNull(EpubBook.chapterTitle("<p>без заголовка</p>"))
    }
}
