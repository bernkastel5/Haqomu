package com.bernkastel5.haqomu.reader

import com.bernkastel5.haqomu.lookup.LookupQuery
import com.bernkastel5.haqomu.lookup.SentenceExtractor

/**
 * Тап по слову в читалке (Ф14): из чего получается запрос к тому же попапу, что и
 * выделение в других приложениях.
 *
 * Почему тап, а не системное меню выделения. Своя читалка — не чужое приложение:
 * сервис доступности здесь не нужен и не должен быть нужен (он выключен по умолчанию).
 * Пользователь тапает по слову, читалка сама находит арабскую словоформу вокруг
 * тапа и вырезает предложение — тем же [SentenceExtractor], что и сервис, поэтому
 * контекст в попапе и в карточке одинаковый.
 *
 * Чистая логика: покрыто JVM-тестами.
 */
object ReaderSelection {

    /** Максимальный радиус поиска слова вокруг тапа, знаков. */
    private const val REACH = 24

    /**
     * Слово и предложение по позиции тапа в тексте главы.
     *
     * [offset] — индекс символа под пальцем. Если тап пришёлся на пробел или знак,
     * берём ближайшее слово в пределах [REACH]: попадать точно в букву на телефоне
     * нереально, и это не должно превращаться в «ничего не найдено».
     */
    fun select(text: String, offset: Int): LookupAt? {
        if (text.isBlank()) return null
        val index = offset.coerceIn(0, text.length - 1)
        val range = wordAround(text, index) ?: return null
        val word = LookupQuery.extract(text.substring(range.first, range.last + 1)) ?: return null
        val sentence = SentenceExtractor.around(text, range.first, range.last + 1)
        return LookupAt(word = word, sentence = sentence, start = range.first, end = range.last + 1)
    }

    /** Попап открывается только на арабском: латиница и цифры в книге — не запрос. */
    data class LookupAt(
        val word: String,
        val sentence: String,
        /** Границы найденной словоформы в тексте главы (для подсветки). */
        val start: Int,
        val end: Int,
    )

    /**
     * Ближайший отрезок арабских букв (с огласовкой) вокруг [index].
     * null — рядом нет арабского текста.
     */
    fun wordAround(text: String, index: Int): IntRange? {
        val at = index.coerceIn(0, (text.length - 1).coerceAtLeast(0))
        if (isWordChar(text[at])) return expand(text, at)
        // Ищем вправо и влево: тап мог прийтись на пробел между словами.
        for (delta in 1..REACH) {
            val right = at + delta
            if (right < text.length && isWordChar(text[right])) return expand(text, right)
            val left = at - delta
            if (left >= 0 && isWordChar(text[left])) return expand(text, left)
        }
        return null
    }

    private fun expand(text: String, at: Int): IntRange {
        var start = at
        while (start > 0 && isWordChar(text[start - 1])) start--
        var end = at
        while (end + 1 < text.length && isWordChar(text[end + 1])) end++
        return start..end
    }

    /** Буква арабского блока или огласовка/татвиль — всё это часть словоформы. */
    fun isWordChar(c: Char): Boolean =
        c in '\u0620'..'\u063F' || c in '\u0641'..'\u064A' || c in '\u066E'..'\u06D3' ||
            c in '\u06D5'..'\u06FF' || c in '\u0610'..'\u061A' || c in '\u064B'..'\u065F' ||
            c == '\u0670' || c == '\u0640'
}
