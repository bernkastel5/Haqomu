package com.bernkastel5.haqomu.reader

/**
 * Текст из XHTML/HTML без библиотек: читалка должна открывать книги «как есть»,
 * а полноценный HTML-парсер — это ещё зависимость и ещё поверхность ошибок.
 *
 * Правила намеренно простые и проверяемые:
 *  - блочные теги (p, div, br, li, заголовки, tr, h1..h6) дают перевод строки,
 *    остальные теги просто выбрасываются;
 *  - `<script>`, `<style>`, `<head>` целиком пропускаются: в книге это не текст;
 *  - сущности (`&amp;`, `&#1610;`, `&nbsp;`) раскрываются;
 *  - пробелы схлопываются, пустые строки схлопываются в одну (в попапе контекст —
 *    строка, а не абзац).
 *
 * Арабский текст остаётся как есть: биди-порядок — забота отображения, а не разбора.
 * Если бы здесь удалялись диакритические метки или нормализовались буквы, огласовка
 * в книге терялась бы — книга показывается как напечатана.
 */
object HtmlText {

    /** Теги, после которых начинается новая строка. */
    private val BLOCK = setOf(
        "p", "div", "br", "li", "ul", "ol", "tr", "td", "th", "table", "section", "article",
        "h1", "h2", "h3", "h4", "h5", "h6", "blockquote", "pre", "hr", "title", "figcaption",
        "header", "footer", "aside", "nav", "dl", "dt", "dd", "body",
    )

    /** Содержимое этих элементов не является текстом книги. */
    private val SKIP = setOf("script", "style", "head", "svg", "math")

    /**
     * Текст из HTML-фрагмента. [keepParagraphs] = true — абзацы разделяются переводами
     * строк (для читалки), false — всё в одну строку (для контекста в попапе).
     */
    fun fromHtml(html: String?, keepParagraphs: Boolean = true): String {
        if (html.isNullOrEmpty()) return ""
        val out = StringBuilder(html.length)
        var i = 0
        while (i < html.length) {
            val c = html[i]
            if (c == '<') {
                val close = html.indexOf('>', i)
                if (close < 0) break
                val raw = html.substring(i + 1, close)
                val name = tagName(raw)
                if (name in SKIP && !raw.trimStart().startsWith("/")) {
                    // пропускаем весь элемент целиком
                    val end = findClosing(html, close + 1, name)
                    i = if (end < 0) html.length else end
                    continue
                }
                if (keepParagraphs && name in BLOCK) out.append('\n') else if (name in BLOCK) out.append(' ')
                i = close + 1
                continue
            }
            if (c == '&') {
                val semi = html.indexOf(';', i)
                if (semi in (i + 2) until minOf(i + 12, html.length)) {
                    val entity = html.substring(i + 1, semi)
                    val decoded = decodeEntity(entity)
                    if (decoded != null) {
                        out.append(decoded)
                        i = semi + 1
                        continue
                    }
                }
            }
            // Переводы строк в исходнике — обычные пробелы по правилам HTML:
            // переводы строк в тексте появляются только из блочных тегов.
            out.append(if (c == '\n' || c == '\r' || c == '\t') ' ' else c)
            i++
        }
        return normalize(out.toString(), keepParagraphs)
    }

    /** Имя тега в нижнем регистре без атрибутов; пустая строка для комментариев. */
    fun tagName(raw: String): String {
        val s = raw.trimStart()
        if (s.startsWith("!") || s.startsWith("?")) return ""
        val start = if (s.startsWith("/")) 1 else 0
        var end = start
        while (end < s.length && (s[end].isLetterOrDigit() || s[end] == '-' || s[end] == ':')) end++
        return s.substring(start, end).lowercase()
    }

    /** Раскрытие сущности без «&» и «;»; null — не знаем такую (оставляем как есть). */
    fun decodeEntity(entity: String): String? = when {
        entity.startsWith("#x") || entity.startsWith("#X") ->
            entity.drop(2).toIntOrNull(16)?.let { codeToString(it) }
        entity.startsWith("#") ->
            entity.drop(1).toIntOrNull()?.let { codeToString(it) }
        else -> NAMED[entity.lowercase()]
    }

    private fun codeToString(code: Int): String? {
        if (code <= 0 || code > 0x10FFFF) return null
        if (code in 0xD800..0xDFFF) return null
        return String(Character.toChars(code))
    }

    private val NAMED = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
        "nbsp" to " ", "ensp" to " ", "emsp" to " ", "thinsp" to " ",
        "shy" to "", "zwnj" to "", "zwj" to "", "lrm" to "", "rlm" to "",
        "mdash" to "\u2014", "ndash" to "\u2013", "hellip" to "\u2026",
        "lsquo" to "\u2018", "rsquo" to "\u2019", "ldquo" to "\u201C", "rdquo" to "\u201D",
        "laquo" to "\u00AB", "raquo" to "\u00BB", "copy" to "\u00A9", "reg" to "\u00AE",
        "deg" to "\u00B0", "middot" to "\u00B7", "bull" to "\u2022",
        "times" to "\u00D7", "divide" to "\u00F7", "plusmn" to "\u00B1",
        "sect" to "\u00A7", "para" to "\u00B6", "dagger" to "\u2020",
        "rlm;" to "", "lrm;" to "",
    )

    /**
     * Схлопывание пробелов. Абзацы (перевод строки) сохраняются, если
     * [keepParagraphs]; пустые строки не копятся.
     */
    fun normalize(text: String, keepParagraphs: Boolean = true): String {
        val sb = StringBuilder(text.length)
        var pendingSpace = false
        var pendingNewline = false
        for (ch in text) {
            when {
                ch == '\n' || ch == '\r' -> {
                    pendingNewline = true
                    pendingSpace = false
                }
                ch == ' ' || ch == '\t' || ch == '\u00A0' || ch == '\u202F' -> pendingSpace = true
                else -> {
                    if (pendingNewline) {
                        if (sb.isNotEmpty() && keepParagraphs && sb.last() != '\n') sb.append('\n')
                        pendingNewline = false
                        pendingSpace = false
                    } else if (pendingSpace) {
                        if (sb.isNotEmpty() && sb.last() != '\n' && sb.last() != ' ') sb.append(' ')
                        pendingSpace = false
                    }
                    sb.append(ch)
                }
            }
        }
        return sb.toString().trim()
    }

    /** Индекс сразу после закрывающего тега [name] (или -1, если его нет). */
    private fun findClosing(html: String, from: Int, name: String): Int {
        var i = from
        while (i < html.length) {
            val open = html.indexOf("</", i)
            if (open < 0) return -1
            val close = html.indexOf('>', open)
            if (close < 0) return -1
            if (tagName(html.substring(open + 1, close)) == name) return close + 1
            i = close + 1
        }
        return -1
    }
}
