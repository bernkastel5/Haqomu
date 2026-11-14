package com.bernkastel5.haqomu.lookup

/**
 * Контекст выделения (Ф2, Ф3): слово и предложение вокруг него.
 * Живёт только в памяти сервиса доступности; на диск не попадает (TZ §8).
 */
data class SelectionInfo(
    val word: String,
    /** Предложение вокруг выделения; пустая строка, если приложение отдаёт только слово. */
    val sentence: String,
)

/**
 * Вырезание предложения вокруг выделения из текста узла доступности (Ф3).
 * Чистая логика, без Android: тестируется на JVM.
 *
 * Границы предложения (docs/TZ.md §3.2, Ф3): «.», «!», «?», арабские «؟» и «؛»,
 * многоточие, точка урду «۔», перевод строки. Запятая «،» границей не является.
 * Пробелы внутри схлопываются, слишком длинный фрагмент обрезается по словам
 * с многоточием — в попапе это строка контекста, а не словарная статья.
 */
object SentenceExtractor {

    /** Потолок длины контекста: попап показывает его в три строки. */
    const val MAX_LENGTH = 240

    private const val ELLIPSIS = "\u2026"

    private val SPACES = Regex("[\\s\\u00A0\\u202F]+")

    private fun isTerminator(c: Char): Boolean =
        c == '.' || c == '!' || c == '?' || c == '؟' || c == '؛' || c == '…' || c == '۔'

    private fun isLineBreak(c: Char): Boolean = c == '\n' || c == '\r'

    private fun isBoundary(c: Char): Boolean = isTerminator(c) || isLineBreak(c)

    /**
     * Предложение, содержащее отрезок [selStart, selEnd) в [text].
     * Индексы вне текста подрезаются; пустой текст даёт пустую строку.
     */
    fun around(text: String, selStart: Int, selEnd: Int): String {
        if (text.isBlank()) return ""
        val s = selStart.coerceIn(0, text.length)
        val e = selEnd.coerceIn(s, text.length)

        var start = 0
        for (i in s - 1 downTo 0) {
            if (isBoundary(text[i])) {
                start = i + 1
                break
            }
        }
        var end = text.length
        for (i in e until text.length) {
            val c = text[i]
            if (isBoundary(c)) {
                // Знак конца предложения входит в него, перевод строки — нет.
                end = if (isLineBreak(c)) i else i + 1
                break
            }
        }

        val piece = collapse(text.substring(start, end))
        if (piece.length <= MAX_LENGTH) return piece

        // Обрезаем вокруг выделения: контекст нужен как подсказка, а не целиком.
        val length = piece.length
        val relative = (s - start).coerceIn(0, length)
        var from = (relative - MAX_LENGTH / 2).coerceIn(0, length - MAX_LENGTH)
        var to = from + MAX_LENGTH
        if (from > 0) {
            // Начинаем со следующего слова, чтобы не показывать обрубок в начале.
            val space = piece.indexOf(' ', from)
            if (space in from until to) from = space + 1
        }
        if (to < length) {
            // И заканчиваем на границе слова.
            val space = piece.lastIndexOf(' ', to)
            if (space > from) to = space
        }
        if (to - from < 1) return piece.take(MAX_LENGTH)
        val head = if (from > 0) ELLIPSIS else ""
        val tail = if (to < length) ELLIPSIS else ""
        return head + piece.substring(from, to).trim() + tail
    }

    private fun collapse(s: String): String = SPACES.replace(s, " ").trim()
}

/**
 * Разбор выделения, пришедшего от сервиса доступности (Ф2/Ф3): слово и предложение.
 * Чистая логика; узел доступности читает [com.bernkastel5.haqomu.service.HaqomuAccessibilityService].
 *
 * Слово — первая арабская последовательность в выделении ([LookupQuery]); выделение без
 * арабского текста попапом не обслуживается (возвращаем null: плашки «не арабский текст»
 * показываются только на пути ACTION_PROCESS_TEXT, а сервис просто молчит).
 */
object SelectionCapture {

    fun capture(text: String?, selStart: Int, selEnd: Int): SelectionInfo? {
        val source = text ?: return null
        if (source.isBlank()) return null
        val s = selStart.coerceIn(0, source.length)
        val e = selEnd.coerceIn(s, source.length)
        if (e <= s) return null
        val word = LookupQuery.extract(source.substring(s, e)) ?: return null
        val sentence = SentenceExtractor.around(source, s, e)
        return SelectionInfo(word = word, sentence = sentence)
    }
}
