package com.bernkastel5.haqomu.gloss

import java.text.Normalizer

/**
 * Токенизатор BERT-типа (WordPiece) для модели разрешения смыслов (Ф10).
 *
 * Зачем свой: модель разрешения смыслов — опциональный ONNX-бандл, а токенизатор
 * в нём — обычный WordPiece (AraBERTv0.2 и производные, см. ADR-0008). Тянуть
 * на устройство отдельную библиотеку токенизаторов ради одного словаря не нужно;
 * правила BERT документированы и проверяются JVM-тестами.
 *
 * Поведение повторяет `BertTokenizer` из transformers для пары предложений:
 *  1. `clean_text` — убрать управляющие символы, заменить пробельные серии пробелом;
 *  2. `[lowercase]` и `[strip_accents]` по флагам бандла (для арабского strip_accents
 *     снимает харакат: они nonspacing-метки и уходят вместе с диакритикой);
 *  3. базовая разбивка: буквы и цифры — в слово, всё остальное — отдельный токен;
 *  4. WordPiece: жадный поиск самого длинного префикса в словаре, продолжение — `##`;
 *  5. пара кодируется как `[CLS] A [SEP] B [SEP]`, `token_type_ids`: 0 для A, 1 для B.
 *
 * Модель не «гадает»: неизвестные буквосочетания становятся `[UNK]`, длинные
 * токены режутся тем же правилом, что в BERT (100 символов).
 */
class BertTokenizer(
    vocab: List<String>,
    private val lowercase: Boolean = false,
    private val stripAccents: Boolean = true,
    private val maxCharsPerWord: Int = 100,
    val clsToken: String = CLS,
    val sepToken: String = SEP,
    val unkToken: String = UNK,
    val padToken: String = PAD,
) {

    private val ids: Map<String, Int> = buildMap {
        vocab.forEachIndexed { index, token -> putIfAbsent(token, index) }
    }

    val vocabularySize: Int get() = ids.size

    init {
        require(ids.containsKey(unkToken)) { "в словаре нет $unkToken" }
        require(ids.containsKey(clsToken)) { "в словаре нет $clsToken" }
        require(ids.containsKey(sepToken)) { "в словаре нет $sepToken" }
    }

    fun idOf(token: String): Int? = ids[token]

    // ------------------------------------------------------------------ API

    /** Одна последовательность: `[CLS] tokens [SEP]`. */
    fun encode(text: String, maxLength: Int = DEFAULT_MAX_LENGTH): Encoded =
        encodePair(text, null, maxLength)

    /**
     * Пара «предложение — толкование»: `[CLS] a [SEP] b [SEP]`.
     * Если [b] пустой или null — пара не нужна, возвращается одиночная последовательность.
     */
    fun encodePair(a: String, b: String?, maxLength: Int = DEFAULT_MAX_LENGTH): Encoded {
        val idsA = wordPieceIds(normalize(a))
        val idsB = if (b == null) emptyList() else wordPieceIds(normalize(b))
        return assemble(idsA, idsB, maxLength)
    }

    // ------------------------------------------------------------- нормализация

    /** `clean_text` + флаги бандла. Возвращает строку в нижнем регистре при `lowercase`. */
    fun normalize(text: String): String {
        val cleaned = cleanText(text)
        val stripped = if (stripAccents) stripAccents(cleaned) else cleaned
        return if (lowercase) stripped.lowercase() else stripped
    }

    /** Базовый токенизатор BERT: буквы и цифры — слово, прочее — по одному символу. */
    fun basicTokens(normalized: String): List<String> {
        val out = mutableListOf<String>()
        val current = StringBuilder()
        fun flush() {
            if (current.isNotEmpty()) {
                out += current.toString()
                current.setLength(0)
            }
        }
        var i = 0
        val n = normalized.length
        while (i < n) {
            val cp = normalized.codePointAt(i)
            val count = Character.charCount(cp)
            if (cp == ' '.code) {
                flush()
            } else if (isControl(cp)) {
                // управляющие символы выброшены ещё в cleanText; здесь на всякий случай
            } else if (!isWordCodePoint(cp)) {
                flush()
                out += String(Character.toChars(cp))
            } else {
                current.appendCodePoint(cp)
            }
            i += count
        }
        flush()
        return out
    }

    /** WordPiece для нормализованной строки. */
    fun wordPieceIds(normalized: String): List<Int> =
        basicTokens(normalized).flatMap { piece -> idsOfWord(piece) }

    /** WordPiece для одного слова: префикс в словаре, продолжение — с `##`. */
    fun idsOfWord(word: String): List<Int> {
        if (word.isEmpty()) return emptyList()
        if (word.length > maxCharsPerWord) return listOf(ids.getValue(unkToken))
        val out = mutableListOf<Int>()
        var start = 0
        while (start < word.length) {
            var end = word.length
            var found = -1
            while (start < end) {
                val piece = if (start > 0) "##" + word.substring(start, end) else word.substring(start, end)
                val id = ids[piece]
                if (id != null) {
                    found = id
                    break
                }
                end -= 1
            }
            if (found < 0) return listOf(ids.getValue(unkToken))
            out += found
            start = end
        }
        return out
    }

    // ------------------------------------------------------------- сборка

    private fun assemble(idsA: List<Int>, idsB: List<Int>, maxLength: Int): Encoded {
        val hasB = idsB.isNotEmpty()
        val cls = ids.getValue(clsToken)
        val sep = ids.getValue(sepToken)
        val budget = maxLength - 2 - if (hasB) 1 else 0
        val (a, b) = truncatePair(idsA, idsB, budget)
        val ids = ArrayList<Long>(maxLength)
        val types = ArrayList<Long>(maxLength)
        ids += cls.toLong()
        types += 0L
        a.forEach { value -> ids += value.toLong(); types += 0L }
        ids += sep.toLong()
        types += 0L
        if (hasB) {
            b.forEach { value -> ids += value.toLong(); types += 1L }
            ids += sep.toLong()
            types += 1L
        }
        return Encoded(ids.toLongArray(), types.toLongArray())
    }

    /**
     * Усечение пары как в BERT: пока не влезает, режем более длинную часть.
     * Возвращает усечённые списки.
     */
    fun truncatePair(idsA: List<Int>, idsB: List<Int>, budget: Int): Pair<List<Int>, List<Int>> {
        if (budget <= 0) return emptyList<Int>() to emptyList()
        if (idsA.size + idsB.size <= budget) return idsA to idsB
        var a = idsA.size
        var b = idsB.size
        while (a + b > budget) {
            if (a >= b && a > 0) a -= 1 else if (b > 0) b -= 1 else break
        }
        return idsA.subList(0, a).toList() to idsB.subList(0, b).toList()
    }

    // ------------------------------------------------------------- служебное

    private fun cleanText(text: String): String {
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            if (isWhitespace(cp)) {
                sb.append(' ')
            } else if (!isControl(cp)) {
                sb.appendCodePoint(cp)
            }
            i += Character.charCount(cp)
        }
        return sb.toString().trim().replace(WHITESPACE_RUN, " ")
    }

    /** NFD и удаление nonspacing-меток: для арабского это и есть снятие огласовки. */
    private fun stripAccents(text: String): String {
        val decomposed = Normalizer.normalize(text, Normalizer.Form.NFD)
        val sb = StringBuilder(decomposed.length)
        var i = 0
        while (i < decomposed.length) {
            val cp = decomposed.codePointAt(i)
            if (Character.getType(cp) != Character.NON_SPACING_MARK.toInt()) sb.appendCodePoint(cp)
            i += Character.charCount(cp)
        }
        return sb.toString()
    }

    private fun isWhitespace(cp: Int): Boolean =
        cp == ' '.code || cp == '\t'.code || cp == '\n'.code || cp == '\r'.code ||
            Character.isWhitespace(cp)

    private fun isControl(cp: Int): Boolean {
        val type = Character.getType(cp)
        return type == Character.CONTROL.toInt() ||
            type == Character.FORMAT.toInt() ||
            cp == 0 ||
            cp == 0xFFFD
    }

    /** Буква или цифра (для арабского — буквы блока 0600–06FF, знаки огласовки уже сняты). */
    private fun isWordCodePoint(cp: Int): Boolean = Character.isLetterOrDigit(cp)

    /** Результат кодирования: id и тип сегмента. */
    data class Encoded(
        val inputIds: LongArray,
        val tokenTypeIds: LongArray,
    ) {
        val length: Int get() = inputIds.size

        override fun equals(other: Any?): Boolean =
            other is Encoded && inputIds.contentEquals(other.inputIds) &&
                tokenTypeIds.contentEquals(other.tokenTypeIds)

        override fun hashCode(): Int = 31 * inputIds.contentHashCode() + tokenTypeIds.contentHashCode()

        override fun toString(): String =
            "Encoded(ids=${inputIds.joinToString(",")}, types=${tokenTypeIds.joinToString(",")})"
    }

    companion object {
        const val CLS = "[CLS]"
        const val SEP = "[SEP]"
        const val UNK = "[UNK]"
        const val PAD = "[PAD]"

        /** Как в замере `tools/eval_live.py`: max_length=192. */
        const val DEFAULT_MAX_LENGTH = 192

        private val WHITESPACE_RUN = Regex(" {2,}")

        /** Словарь из файла `vocab.txt`: один токен на строку. */
        fun fromVocabText(
            text: String,
            lowercase: Boolean = false,
            stripAccents: Boolean = true,
        ): BertTokenizer = BertTokenizer(
            vocab = text.lineSequence().map { it.trim('\n', '\r') }.filter { it.isNotEmpty() }.toList(),
            lowercase = lowercase,
            stripAccents = stripAccents,
        )
    }
}
