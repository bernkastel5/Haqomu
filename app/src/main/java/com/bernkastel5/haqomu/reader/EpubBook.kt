package com.bernkastel5.haqomu.reader

import java.io.Closeable
import java.io.File
import java.util.zip.ZipFile

/** Глава книги: имя файла внутри контейнера и уже распакованный текст. */
data class ReaderChapter(
    /** Идентификатор для закладки/продолжения: путь главы внутри контейнера. */
    val id: String,
    /** Заголовок из оглавления, если он есть; иначе имя главы. */
    val title: String,
    /** Текст главы (абзацы через перевод строки, без разметки). */
    val text: String,
)

/**
 * EPUB без сторонних библиотек: `java.util.zip.ZipFile` + разбор XML вручную.
 *
 * Почему так (ADR-0011): EPUB — это zip с `META-INF/container.xml`, OPF-манифестом
 * и XHTML-главами. Полноценный парсер тянет за собой библиотеку и её поверхность
 * ошибок, а здесь нужно ровно три вещи: порядок чтения (spine), заголовки
 * (nav/NCX, необязательно) и текст глав. Разметку в текст переводит [HtmlText].
 *
 * Чего здесь нет и не будет: DRM (зашифрованные книги честно не открываются),
 * фиксированная вёрстка (EPUB Fixed Layout — это картинки, текста нет), аудио/видео.
 * Файл читается целиком в память: книга в 2–3 МБ текста — это единицы мегабайт
 * строк, для читалки на телефоне приемлемо, а zip-поток даёт случайный доступ.
 */
class EpubBook private constructor(
    private val zip: ZipFile,
    val title: String,
    val author: String?,
    val chapters: List<ReaderChapter>,
) : Closeable {

    override fun close() {
        try {
            zip.close()
        } catch (_: Exception) {
            // закрытие не должно ломать экран читалки
        }
    }

    companion object {

        /** Открыть EPUB. null — файл не книга или повреждён. */
        fun open(file: File): EpubBook? {
            if (!file.isFile || file.length() == 0L) return null
            var zip: ZipFile? = null
            return try {
                zip = ZipFile(file)
                val rootFile = findRootFile(zip) ?: "OEBPS/content.opf"
                val opf = readEntry(zip, rootFile) ?: return null
                val base = rootFile.substringBeforeLast('/', "")
                val manifest = parseManifest(opf)
                if (manifest.isEmpty()) return null
                val spine = parseSpine(opf).ifEmpty { manifest.keys.toList() }

                // Оглавление — подсказка для заголовков; без него главы нумеруются.
                val toc = parseToc(zip, opf, base, manifest)

                val chapters = mutableListOf<ReaderChapter>()
                for (idref in spine) {
                    val href = manifest[idref] ?: continue
                    val path = resolve(base, href)
                    val html = readEntry(zip, path) ?: continue
                    val text = HtmlText.fromHtml(html)
                    if (text.isBlank()) continue
                    chapters += ReaderChapter(
                        id = path,
                        title = toc[path] ?: chapterTitle(html) ?: "Глава ${chapters.size + 1}",
                        text = text,
                    )
                }
                if (chapters.isEmpty()) return null

                val meta = parseMetadata(opf)
                EpubBook(
                    zip = zip,
                    title = meta.first.ifBlank { file.nameWithoutExtension },
                    author = meta.second,
                    chapters = chapters,
                )
            } catch (_: Exception) {
                try {
                    zip?.close()
                } catch (_: Exception) {
                    // уже закрываемся из-за ошибки
                }
                null
            }
        }

        // ------------------------------------------------------------ контейнер

        /** Путь к OPF из META-INF/container.xml. */
        fun findRootFile(zip: ZipFile): String? {
            val container = readEntry(zip, "META-INF/container.xml") ?: return null
            val m = Regex("full-path\\s*=\\s*[\"']([^\"']+)[\"']").find(container) ?: return null
            return m.groupValues[1].ifBlank { null }
        }

        private fun readEntry(zip: ZipFile, path: String): String? = try {
            val entry = zip.getEntry(path) ?: zip.getEntry(path.removePrefix("/")) ?: return null
            zip.getInputStream(entry).use { stream ->
                val bytes = stream.readBytes()
                // EPUB по спецификации UTF-8; BOM не мешает, но и не нужен
                String(bytes, Charsets.UTF_8).removePrefix("\uFEFF")
            }
        } catch (_: Exception) {
            null
        }

        // ------------------------------------------------------------ OPF

        /** id → href (без фрагмента) из манифеста OPF. */
        fun parseManifest(opf: String): Map<String, String> {
            val out = LinkedHashMap<String, String>()
            for (m in ITEM.findAll(opf)) {
                val attrs = attributes(m.value)
                val id = attrs["id"] ?: continue
                val href = attrs["href"] ?: continue
                if (href.isBlank()) continue
                val media = attrs["media-type"].orEmpty()
                // В манифесте бывают обложки и шрифты; читаем только документы.
                if (media.isNotEmpty() && !media.contains("html") && !media.contains("xml")) {
                    if (!href.endsWith(".xhtml") && !href.endsWith(".html") && !href.endsWith(".htm")) continue
                }
                out[id] = href.substringBefore('#')
            }
            return out
        }

        /** Порядок чтения: idref из spine, по порядку. */
        fun parseSpine(opf: String): List<String> {
            val out = mutableListOf<String>()
            for (m in ITEMREF.findAll(opf)) {
                attributes(m.value)["idref"]?.let { out += it }
            }
            return out
        }

        /** Название и автор из metadata OPF. */
        fun parseMetadata(opf: String): Pair<String, String?> {
            val title = TAG_TITLE.find(opf)?.groupValues?.get(1)?.let { HtmlText.fromHtml(it, false) }.orEmpty()
            val author = TAG_CREATOR.find(opf)?.groupValues?.get(1)?.let { HtmlText.fromHtml(it, false) }
            return title to author?.takeIf { it.isNotBlank() }
        }

        /**
         * Оглавление: EPUB 3 (nav.xhtml, `<nav epub:type="toc">`) либо EPUB 2 (toc.ncx).
         * Возвращает путь главы → заголовок. Ошибки не критичны: без оглавления
         * главы называются по первому заголовку внутри.
         */
        fun parseToc(zip: ZipFile, opf: String, base: String, manifest: Map<String, String>): Map<String, String> {
            val out = LinkedHashMap<String, String>()
            // EPUB 3: ищем в манифесте nav-документ
            val navHref = ITEM.findAll(opf).map { attributes(it.value) }
                .firstOrNull { it["properties"].orEmpty().split(' ').contains("nav") }
                ?.get("href")
            if (navHref != null) {
                val navPath = resolve(base, navHref.substringBefore('#'))
                val nav = readEntry(zip, navPath)
                if (nav != null) {
                    collectNavLinks(nav, navPath.substringBeforeLast('/', ""), out)
                    if (out.isNotEmpty()) return out
                }
            }
            // EPUB 2: NCX
            val ncxHref = ITEM.findAll(opf).map { attributes(it.value) }
                .firstOrNull { it["media-type"] == "application/x-dtbncx+xml" }
                ?.get("href")
            if (ncxHref != null) {
                val ncxPath = resolve(base, ncxHref)
                val ncx = readEntry(zip, ncxPath) ?: return out
                val ncxBase = ncxPath.substringBeforeLast('/', "")
                for (m in NAVPOINT.findAll(ncx)) {
                    val label = TAG_LABEL.find(m.value)?.groupValues?.get(1)
                        ?.let { HtmlText.fromHtml(it, false) } ?: continue
                    val src = TAG_SRC.find(m.value)?.groupValues?.get(1) ?: continue
                    out.putIfAbsent(resolve(ncxBase, src.substringBefore('#')), label)
                }
            }
            return out
        }

        private fun collectNavLinks(nav: String, navBase: String, out: MutableMap<String, String>) {
            val start = nav.indexOf("epub:type=\"toc\"")
            val region = if (start >= 0) nav.substring(start) else nav
            for (m in LINK.findAll(region)) {
                val attrs = attributes(m.value)
                val href = attrs["href"] ?: continue
                val body = TAG_A_BODY.find(m.value)?.groupValues?.get(1) ?: ""
                val label = HtmlText.fromHtml(body, false)
                if (label.isBlank()) continue
                out.putIfAbsent(resolve(navBase, href.substringBefore('#')), label)
            }
        }

        /** Первый h1..h3 страницы — запасной заголовок главы. */
        fun chapterTitle(html: String): String? {
            val m = TAG_HEADING.find(html) ?: return null
            return HtmlText.fromHtml(m.groupValues[2], false).takeIf { it.isNotBlank() }
        }

        /** Относительный путь [href] от каталога [base] внутри контейнера. */
        fun resolve(base: String, href: String): String {
            val clean = href.substringBefore('#').removePrefix("./")
            val raw = if (clean.startsWith("/")) clean.removePrefix("/") else if (base.isEmpty()) clean else "$base/$clean"
            val parts = ArrayDeque<String>()
            for (segment in raw.split('/')) {
                when (segment) {
                    "", "." -> Unit
                    ".." -> if (parts.isNotEmpty()) parts.removeLast()
                    else -> parts.addLast(segment)
                }
            }
            return parts.joinToString("/")
        }

        /** Атрибуты тега из строки `<item id="x" href="y"/>`. */
        fun attributes(tag: String): Map<String, String> {
            val out = HashMap<String, String>(4)
            for (m in ATTR.findAll(tag)) {
                out[m.groupValues[1].lowercase()] = m.groupValues[2]
            }
            return out
        }

        private val ITEM = Regex("<(?:opf:)?item\\b[^>]*>", RegexOption.IGNORE_CASE)
        private val ITEMREF = Regex("<(?:opf:)?itemref\\b[^>]*>", RegexOption.IGNORE_CASE)
        private val NAVPOINT = Regex("<navPoint\\b.*?</navPoint>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        private val LINK = Regex("<a\\b[^>]*>.*?</a>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        private val TAG_A_BODY = Regex("<a\\b[^>]*>(.*?)</a>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        private val TAG_LABEL = Regex("<text\\b[^>]*>(.*?)</text>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        private val TAG_SRC = Regex("<content\\b[^>]*\\bsrc\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
        private val TAG_TITLE = Regex("<(?:dc:)?title\\b[^>]*>(.*?)</(?:dc:)?title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        private val TAG_CREATOR = Regex("<(?:dc:)?creator\\b[^>]*>(.*?)</(?:dc:)?creator>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        private val TAG_HEADING = Regex("<h([1-3])\\b[^>]*>(.*?)</h\\1>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        private val ATTR = Regex("([A-Za-z_:][-A-Za-z0-9_:.]*)\\s*=\\s*[\"']([^\"']*)[\"']")
    }
}
