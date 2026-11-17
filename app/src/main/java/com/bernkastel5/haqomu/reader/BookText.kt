package com.bernkastel5.haqomu.reader

import java.io.File

/** Книга в памяти читалки: то, что показывает экран. */
data class Book(
    val title: String,
    val author: String?,
    val chapters: List<ReaderChapter>,
    /** Формат, из которого книга прочитана: EPUB, PDF, FB2, HTML, TXT. */
    val format: String,
)

/**
 * Открытие книги любого из поддерживаемых форматов (Ф14).
 *
 * Диспетчер по расширению и содержимому: EPUB, PDF, FB2, HTML/XHTML, TXT.
 * Всё — на своём коде без сторонних библиотек (ADR-0011), поэтому список форматов
 * честно ограничен теми, что здесь разбираются. Неизвестный файл даёт null и
 * объяснение в интерфейсе, а не пустой экран.
 *
 * Что важно для словаря: книга режется на главы/страницы, а внутри главы текст
 * остаётся **как напечатан**, с огласовкой. Читалка не нормализует арабский текст:
 * иначе выделенное слово не совпало бы с тем, что видит пользователь.
 */
object BookText {

    /** Расширения, которые читалка берёт. */
    val EXTENSIONS = listOf("epub", "pdf", "fb2", "html", "htm", "xhtml", "txt")

    /** MIME-типы для системного выбора файла. */
    val MIME_TYPES = arrayOf(
        "application/epub+zip",
        "application/pdf",
        "application/x-fictionbook+xml",
        "text/html",
        "application/xhtml+xml",
        "text/plain",
        "application/octet-stream",
    )

    /** Открыть книгу. null — формат не поддержан или файл повреждён. */
    fun open(file: File): Book? {
        if (!file.isFile || file.length() == 0L) return null
        return try {
            when (extensionOf(file.name)) {
                "epub" -> openEpub(file)
                "pdf" -> openPdf(file)
                "fb2" -> openFb2(file)
                "html", "htm", "xhtml" -> openHtml(file)
                "txt" -> openTxt(file)
                else -> detect(file)
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Определение формата по первым байтам, если расширение не помогло. */
    private fun detect(file: File): Book? {
        val head = try {
            file.inputStream().use { stream ->
                val buf = ByteArray(1024)
                val n = stream.read(buf)
                String(buf, 0, maxOf(n, 0), Charsets.ISO_8859_1)
            }
        } catch (_: Exception) {
            return null
        }
        return when {
            head.startsWith("%PDF") -> openPdf(file)
            head.startsWith("PK") -> openEpub(file)
            head.contains("<FictionBook") -> openFb2(file)
            head.contains("<html", ignoreCase = true) || head.contains("<?xml", ignoreCase = true) -> openHtml(file)
            else -> openTxt(file)
        }
    }

    private fun openEpub(file: File): Book? {
        val epub = EpubBook.open(file) ?: return null
        return epub.use {
            Book(
                title = it.title,
                author = it.author,
                chapters = it.chapters,
                format = "EPUB",
            )
        }
    }

    private fun openPdf(file: File): Book? {
        val pages = PdfText.extract(file.readBytes())
        if (pages.isEmpty()) return null
        return Book(
            title = file.nameWithoutExtension,
            author = null,
            chapters = pages.mapIndexed { index, text ->
                ReaderChapter(id = "page-$index", title = "Стр. ${index + 1}", text = text)
            },
            format = "PDF",
        )
    }

    private fun openFb2(file: File): Book? {
        val xml = file.readText(Charsets.UTF_8)
        val title = TAG_BOOK_TITLE.find(xml)?.groupValues?.get(1)
            ?.let { HtmlText.fromHtml(it, false) }?.takeIf { it.isNotBlank() }
            ?: file.nameWithoutExtension
        val author = TAG_AUTHOR.find(xml)?.groupValues?.get(1)?.let { authorBlock ->
            val first = TAG_FIRST_NAME.find(authorBlock)?.groupValues?.get(1).orEmpty()
            val last = TAG_LAST_NAME.find(authorBlock)?.groupValues?.get(1).orEmpty()
            HtmlText.fromHtml("$first $last", false).takeIf { it.isNotBlank() }
        }
        val body = TAG_BODY.find(xml)?.value ?: xml
        val sections = SECTION.findAll(body).map { it.value }.toList()
        val chunks = if (sections.size > 1) sections else listOf(body)
        val chapters = chunks.mapNotNull { section ->
            val text = HtmlText.fromHtml(section)
            if (text.isBlank()) return@mapNotNull null
            val heading = SECTION_TITLE.find(section)?.value?.let { HtmlText.fromHtml(it, false) }
            ReaderChapter(
                id = "fb2-${chaptersHash(section)}",
                title = heading?.takeIf { it.isNotBlank() } ?: "Глава ${chaptersHash(section) % 1000}",
                text = text,
            )
        }
        if (chapters.isEmpty()) return null
        return Book(title = title, author = author, chapters = chapters, format = "FB2")
    }

    private fun chaptersHash(section: String): Int = (section.hashCode() and 0x7FFFFFFF)

    private fun openHtml(file: File): Book? {
        val html = file.readText(Charsets.UTF_8)
        val text = HtmlText.fromHtml(html)
        if (text.isBlank()) return null
        val title = TAG_TITLE_TEXT.find(html)?.groupValues?.get(1)
            ?.let { HtmlText.fromHtml(it, false) }?.takeIf { it.isNotBlank() }
            ?: file.nameWithoutExtension
        return Book(
            title = title,
            author = null,
            chapters = splitLong(text, "html"),
            format = "HTML",
        )
    }

    private fun openTxt(file: File): Book? {
        val text = file.readText(Charsets.UTF_8).ifBlank { file.readText(Charsets.ISO_8859_1) }
        if (text.isBlank()) return null
        return Book(
            title = file.nameWithoutExtension,
            author = null,
            chapters = splitLong(HtmlText.normalize(text, keepParagraphs = true), "txt"),
            format = "TXT",
        )
    }

    /**
     * Один длинный текст → главы примерно по [CHUNK_CHARS], но только по границам
     * абзацев: прокрутка на телефоне не любит пятнадцать тысяч знаков в одной строке,
     * а разрывать абзац пополам нельзя — из него берётся предложение для карточки.
     */
    fun splitLong(text: String, idPrefix: String): List<ReaderChapter> {
        val paragraphs = text.split(Regex("\n{2,}")).map { it.trim() }.filter { it.isNotEmpty() }
        if (paragraphs.isEmpty()) return emptyList()
        val chapters = mutableListOf<ReaderChapter>()
        val current = StringBuilder()
        var part = 1
        fun flush() {
            if (current.isEmpty()) return
            chapters += ReaderChapter(
                id = "$idPrefix-$part",
                title = "Часть $part",
                text = current.toString().trim(),
            )
            current.setLength(0)
            part++
        }
        for (p in paragraphs) {
            if (current.length + p.length > CHUNK_CHARS) flush()
            if (current.isNotEmpty()) current.append("\n\n")
            current.append(p)
            if (current.length >= CHUNK_CHARS) flush()
        }
        flush()
        return chapters
    }

    fun extensionOf(name: String): String = name.substringAfterLast('.', "").lowercase()

    private const val CHUNK_CHARS = 12_000

    private val TAG_BOOK_TITLE = Regex("<book-title\\b[^>]*>(.*?)</book-title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val TAG_AUTHOR = Regex("<author\\b[^>]*>(.*?)</author>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val TAG_FIRST_NAME = Regex("<first-name\\b[^>]*>(.*?)</first-name>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val TAG_LAST_NAME = Regex("<last-name\\b[^>]*>(.*?)</last-name>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val TAG_BODY = Regex("<body\\b[^>]*>(.*?)</body>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val SECTION = Regex("<section\\b[^>]*>(.*?)</section>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val SECTION_TITLE = Regex("<title\\b[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val TAG_TITLE_TEXT = Regex("<title\\b[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
}
