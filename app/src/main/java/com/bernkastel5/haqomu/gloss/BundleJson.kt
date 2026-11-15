package com.bernkastel5.haqomu.gloss

/**
 * Минимальный разбор `model.json` бандла модели (Ф13).
 *
 * Свой парсер, а не `org.json`: модуль `gloss` обязан быть JVM-тестируемым без Android,
 * а формат плоский — объект из строк, чисел и булевых значений. Неизвестные ключи
 * игнорируются (бандл делает не только наша сборка), ошибки разбора — исключение
 * с человекочитаемым текстом, которое ловит [ModelBundle.parseManifest].
 */
internal object BundleJson {

    fun parse(text: String): BundleMeta {
        val map = Parser(text).parseObjectFlat()
        fun str(key: String, default: String): String =
            (map[key] as? String)?.takeIf { it.isNotBlank() } ?: default
        fun long(key: String, default: Long): Long = when (val v = map[key]) {
            is Double -> v.toLong()
            is String -> v.toLongOrNull() ?: default
            else -> default
        }
        fun int(key: String, default: Int): Int = long(key, default.toLong()).toInt()
        fun bool(key: String, default: Boolean): Boolean = when (val v = map[key]) {
            is Boolean -> v
            is String -> v.equals("true", ignoreCase = true)
            else -> default
        }
        fun double(key: String, default: Double): Double = when (val v = map[key]) {
            is Double -> v
            is String -> v.toDoubleOrNull() ?: default
            else -> default
        }
        return BundleMeta(
            model = str("model", ""),
            version = int("version", 1),
            license = str("license", "CC BY-SA 4.0"),
            source = str("source", "SinaLab/ArabGlossBERT"),
            sizeBytes = long("size_bytes", -1L),
            maxLength = int("max_length", 192),
            lowercase = bool("lowercase", false),
            stripAccents = bool("strip_accents", true),
            inputIds = str("input_ids", "input_ids"),
            attentionMask = str("attention_mask", "attention_mask"),
            tokenTypeIds = str("token_type_ids", "token_type_ids"),
            logits = str("logits", "logits"),
            labels = int("labels", 2),
            threshold = double("threshold", 0.3),
        )
    }

    private class Parser(private val s: String) {
        private var i = 0

        fun parseObjectFlat(): Map<String, Any> {
            skipWs()
            expect('{')
            val out = LinkedHashMap<String, Any>()
            skipWs()
            if (peek() == '}') {
                i += 1
                return out
            }
            while (true) {
                skipWs()
                val key = parseString()
                skipWs()
                expect(':')
                skipWs()
                out[key] = parseValue()
                skipWs()
                when (peek()) {
                    ',' -> { i += 1; continue }
                    '}' -> { i += 1; return out }
                    else -> throw IllegalArgumentException("ожидалась , или } в позиции $i")
                }
            }
        }

        private fun parseValue(): Any {
            skipWs()
            return when (val c = peek()) {
                '"' -> parseString()
                't' -> parseLiteral("true", true)
                'f' -> parseLiteral("false", false)
                'n' -> parseLiteral("null", "")
                '{' -> skipNested('{', '}')
                '[' -> skipNested('[', ']')
                else -> if (c == '-' || c.isDigit()) parseNumber()
                else throw IllegalArgumentException("непонятный символ '$c' в позиции $i")
            }
        }

        /** Вложенные объекты и массивы не нужны продукту — пропускаем их целиком. */
        private fun skipNested(open: Char, close: Char): String {
            expect(open)
            var depth = 1
            var inString = false
            while (i < s.length) {
                val c = s[i]
                if (inString) {
                    if (c == '\\') i += 1 else if (c == '"') inString = false
                } else {
                    when (c) {
                        '"' -> inString = true
                        open -> depth += 1
                        close -> {
                            depth -= 1
                            if (depth == 0) {
                                i += 1
                                return ""
                            }
                        }
                    }
                }
                i += 1
            }
            throw IllegalArgumentException("не закрыт вложенный блок")
        }

        private fun parseLiteral(literal: String, value: Any): Any {
            if (!s.startsWith(literal, i)) throw IllegalArgumentException("ожидалось $literal в позиции $i")
            i += literal.length
            return value
        }

        private fun parseNumber(): Double {
            val start = i
            if (peek() == '-') i += 1
            while (i < s.length && (s[i].isDigit() || s[i] in ".eE+-")) i += 1
            val raw = s.substring(start, i)
            return raw.toDoubleOrNull() ?: throw IllegalArgumentException("число '$raw' не разобрано")
        }

        private fun parseString(): String {
            expect('"')
            val sb = StringBuilder()
            while (i < s.length) {
                val c = s[i]
                i += 1
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (i >= s.length) throw IllegalArgumentException("оборванный escape")
                        when (val esc = s[i]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                val hex = s.substring(i + 1, minOf(i + 5, s.length))
                                if (hex.length < 4) throw IllegalArgumentException("короткий \\u escape")
                                sb.append(hex.toInt(16).toChar())
                                i += 4
                            }
                            else -> throw IllegalArgumentException("неизвестный escape '$esc'")
                        }
                        i += 1
                    }
                    else -> sb.append(c)
                }
            }
            throw IllegalArgumentException("строка не закрыта")
        }

        private fun skipWs() {
            while (i < s.length && s[i].isWhitespace()) i += 1
        }

        private fun peek(): Char {
            if (i >= s.length) throw IllegalArgumentException("неожиданный конец манифеста")
            return s[i]
        }

        private fun expect(c: Char) {
            if (peek() != c) throw IllegalArgumentException("ожидался '$c' в позиции $i")
            i += 1
        }
    }
}
