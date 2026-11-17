package com.bernkastel5.haqomu.reader

/**
 * Разбор контент-потока PDF: только операторы показа текста.
 *
 * В контент-потоке текст идёт операторами `Tj` (строка), `TJ` (массив строк и кернингов),
 * `'` (перевод строки + текст) и `"`. Всё остальное (линии, картинки, цвет, трансформации)
 * для текста не важно и пропускается. Текущий шрифт меняется оператором `Tf`, поэтому
 * парсер помнит имя ресурса и берёт для строк соответствующий CMap.
 *
 * Позиционирование упрощено до «пробел там, где в TJ заметный кернинг» и «новая строка
 * на операторах сдвига текста» (Td, TD, T-star, Tm): полноценная вёрстка требует матриц текста и ширин глифов, а читалке
 * нужен абзац текста, который потом можно выделять, а не воспроизведение строки в строку.
 *
 * Если шрифт двухбайтовый и без CMap (Identity-H без ToUnicode), его строки
 * **пропускаются**: подставить туда латиницу значило бы показать мусор.
 */
internal class PdfContentParser(
    private val content: String,
    private val fontOf: (String?) -> PdfFontInfo,
) {

    private companion object {
        /** Предел текста одного потока: защита от патологических файлов. */
        const val MAX_CHARS = 2_000_000
    }

    fun parse(): String {
        val out = StringBuilder(minOf(content.length, MAX_CHARS))
        var currentFont: String? = null
        val operands = ArrayList<Any>(8)
        var i = 0
        while (i < content.length) {
            if (out.length >= MAX_CHARS) break
            val c = content[i]
            when {
                c == '%' -> {
                    // комментарий до конца строки
                    while (i < content.length && content[i] != '\n' && content[i] != '\r') i++
                }
                c == '(' -> {
                    val (text, next) = readLiteralString(content, i)
                    operands += text
                    i = next
                }
                c == '<' && content.getOrNull(i + 1) != '<' -> {
                    val close = content.indexOf('>', i)
                    if (close < 0) break
                    operands += PdfString.fromHex(content.substring(i + 1, close))
                    i = close + 1
                }
                c == '[' -> {
                    val (items, next) = readArray(content, i)
                    operands.add(items)
                    i = next
                }
                c == '<' && content.getOrNull(i + 1) == '<' -> {
                    // вложенный словарь (например, в BDC) — пропускаем целиком
                    i = skipDictionary(content, i)
                }
                c == '/' -> {
                    var j = i + 1
                    while (j < content.length && !isDelimiter(content[j])) j++
                    operands += NameOperand(content.substring(i + 1, j))
                    i = j
                }
                c.isDigit() || c == '-' || c == '+' || c == '.' -> {
                    var j = i + 1
                    while (j < content.length && (content[j].isDigit() || content[j] == '.' || content[j] == '-' || content[j] == '+')) j++
                    operands += content.substring(i, j).toDoubleOrNull() ?: 0.0
                    i = j
                }
                c.isLetter() || c == '\'' || c == '"' -> {
                    var j = i + 1
                    while (j < content.length && (content[j].isLetter() || content[j].isDigit() || content[j] == '*')) j++
                    val op = content.substring(i, j)
                    when (op) {
                        "Tf" -> currentFont = (operands.lastOrNull { it is NameOperand } as? NameOperand)?.name
                        "Tj" -> if (out.length < MAX_CHARS) {
                            val s = (operands.lastOrNull() as? PdfString)?.decode(fontOf(currentFont))
                            if (s != null) out.append(s)
                        }
                        "TJ" -> if (out.length < MAX_CHARS) {
                            val arr = operands.lastOrNull() as? List<*>
                            if (arr != null) out.append(arrayText(arr, fontOf(currentFont)))
                        }
                        "'" -> if (out.length < MAX_CHARS) {
                            newline(out)
                            val s = (operands.lastOrNull() as? PdfString)?.decode(fontOf(currentFont))
                            if (s != null) out.append(s)
                        }
                        "\"" -> if (out.length < MAX_CHARS) {
                            newline(out)
                            val s = (operands.filterIsInstance<PdfString>().lastOrNull())?.decode(fontOf(currentFont))
                            if (s != null) out.append(s)
                        }
                        "Td", "TD", "T*", "Tm", "BT", "ET" -> newline(out)
                        "Do", "BI", "EI" -> Unit
                    }
                    operands.clear()
                    i = j
                }
                else -> i++
            }
        }
        return out.toString().trim()
    }

    private fun newline(out: StringBuilder) {
        if (out.isEmpty()) return
        val last = out.last()
        if (last == '\n') return
        // Одиночный перевод строки внутри абзаца не нужен: абзацем считаем пустую строку.
        out.append('\n')
    }

    /** Текст массива TJ: строки подряд, заметный кернинг — пробел. */
    private fun arrayText(items: List<*>, font: PdfFontInfo): String {
        val sb = StringBuilder()
        for (item in items) {
            when (item) {
                is PdfString -> sb.append(item.decode(font) ?: "")
                is Double -> if (item < -180) sb.append(' ')
            }
        }
        return sb.toString()
    }

    private fun readLiteralString(text: String, start: Int): Pair<PdfString, Int> {
        val sb = StringBuilder()
        var i = start + 1
        var depth = 1
        while (i < text.length) {
            val c = text[i]
            when {
                c == '\\' -> {
                    val next = text.getOrNull(i + 1) ?: break
                    when (next) {
                        'n' -> { sb.append('\n'); i += 2 }
                        'r' -> { sb.append('\r'); i += 2 }
                        't' -> { sb.append('\t'); i += 2 }
                        'b' -> { sb.append('\b'); i += 2 }
                        'f' -> { sb.append('\u000C'); i += 2 }
                        '(', ')', '\\' -> { sb.append(next); i += 2 }
                        '\n' -> i += 2
                        '\r' -> i += if (text.getOrNull(i + 2) == '\n') 3 else 2
                        else -> {
                            // восьмеричный код \ddd
                            var j = i + 1
                            var value = 0
                            var digits = 0
                            while (j < text.length && digits < 3 && text[j] in '0'..'7') {
                                value = value * 8 + (text[j] - '0')
                                j++
                                digits++
                            }
                            if (digits > 0) {
                                sb.append((value and 0xFF).toChar())
                                i = j
                            } else {
                                sb.append(next)
                                i += 2
                            }
                        }
                    }
                }
                c == '(' -> { depth++; sb.append(c); i++ }
                c == ')' -> {
                    depth--
                    if (depth == 0) return PdfString.fromLiteral(sb.toString()) to i + 1
                    sb.append(c)
                    i++
                }
                else -> { sb.append(c); i++ }
            }
        }
        return PdfString.fromLiteral(sb.toString()) to text.length
    }

    private fun readArray(text: String, start: Int): Pair<List<Any>, Int> {
        val items = ArrayList<Any>(8)
        var i = start + 1
        while (i < text.length) {
            val c = text[i]
            when {
                c == ']' -> return items to i + 1
                c == '(' -> {
                    val (s, next) = readLiteralString(text, i)
                    items += s
                    i = next
                }
                c == '<' && text.getOrNull(i + 1) != '<' -> {
                    val close = text.indexOf('>', i)
                    if (close < 0) return items to text.length
                    items += PdfString.fromHex(text.substring(i + 1, close))
                    i = close + 1
                }
                c.isDigit() || c == '-' || c == '+' || c == '.' -> {
                    var j = i + 1
                    while (j < text.length && (text[j].isDigit() || text[j] == '.' || text[j] == '-' || text[j] == '+')) j++
                    items += text.substring(i, j).toDoubleOrNull() ?: 0.0
                    i = j
                }
                c == '/' -> {
                    var j = i + 1
                    while (j < text.length && !isDelimiter(text[j])) j++
                    items += NameOperand(text.substring(i + 1, j))
                    i = j
                }
                else -> i++
            }
        }
        return items to text.length
    }

    private fun skipDictionary(text: String, start: Int): Int {
        var i = start
        var depth = 0
        while (i < text.length) {
            when {
                text.startsWith("<<", i) -> { depth++; i += 2 }
                text.startsWith(">>", i) -> {
                    depth--
                    i += 2
                    if (depth == 0) return i
                }
                text[i] == '(' -> i = readLiteralString(text, i).second
                else -> i++
            }
        }
        return text.length
    }

    private fun isDelimiter(c: Char): Boolean =
        c.isWhitespace() || c == '/' || c == '[' || c == ']' || c == '<' || c == '>' || c == '(' || c == ')'
}

/**
 * Строка из контент-потока: буквальная или hex; коды декодируются шрифтом.
 * Хранит исходные байты кодами — так одинаково обрабатываются оба вида записи.
 */
internal class PdfString private constructor(private val codes: IntArray) {

    companion object {
        /** Буквальная строка: символы по байту (Latin-1). */
        fun fromLiteral(value: String) = PdfString(IntArray(value.length) { value[it].code and 0xFF })

        /** Hex-строка без пробелов: пары символов — байты. */
        fun fromHex(hex: String) = PdfString(
            IntArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toIntOrNull(16) ?: 0 }
        )
    }

    fun decode(font: PdfFontInfo): String? =
        if (font.composite) decodeTwoByte(font) else decodeOneByte(font)

    private fun decodeOneByte(font: PdfFontInfo): String =
        codes.joinToString("") { code ->
            font.cmap?.text(code) ?: if (code in 32..126 || code in 160..255) code.toChar().toString() else ""
        }

    private fun decodeTwoByte(font: PdfFontInfo): String? {
        val cmap = font.cmap ?: return null // Identity-H без ToUnicode: честно пропускаем
        val sb = StringBuilder(codes.size / 2)
        var i = 0
        while (i + 1 < codes.size) {
            sb.append(cmap.text((codes[i] shl 8) or codes[i + 1]) ?: return null)
            i += 2
        }
        return sb.toString()
    }
}

internal class NameOperand(val name: String)

/** Что парсеру нужно знать о шрифте: двухбайтовый ли он и есть ли CMap. */
internal class PdfFontInfo(val composite: Boolean, val cmap: PdfText.PdfCMap?) {
    companion object {
        val UNKNOWN = PdfFontInfo(composite = false, cmap = null)
    }
}
