package com.bernkastel5.haqomu.anki

/**
 * Минимальное JSON-построение без зависимостей (на JVM и Android одинаково).
 * Парсить JSON здесь не нужно; строки экранируем по RFC 8259.
 */
object Json {
    fun escape(value: String): String {
        val sb = StringBuilder(value.length + 16)
        for (ch in value) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (ch < ' ') {
                    sb.append("\\u").append(ch.code.toString(16).padStart(4, '0'))
                } else {
                    sb.append(ch)
                }
            }
        }
        return sb.toString()
    }

    fun quoted(value: String): String = "\"${escape(value)}\""

    fun stringMap(entries: List<Pair<String, String>>): String =
        entries.joinToString(",", prefix = "{", postfix = "}") { (k, v) ->
            "${quoted(k)}:${quoted(v)}"
        }

    fun stringList(values: List<String>): String =
        values.joinToString(",", prefix = "[", postfix = "]") { quoted(it) }
}
