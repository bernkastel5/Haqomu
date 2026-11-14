package com.bernkastel5.haqomu.lookup

/** Чистое форматирование для попапа (Ф9); JVM-тестируемо. */
object PopupFormat {

    /** Корень из пака хранится без пробелов («ذهب»); показываем «ذ ه ب». */
    fun rootLetters(root: String?): String? {
        val letters = root?.filter { it in '\u0600'..'\u06FF' } ?: return null
        if (letters.isEmpty()) return null
        return letters.toList().joinToString(" ")
    }

    /** Арабские-индийские цифры для нумерации смыслов (١. ٢. ٣.). */
    fun arabicIndicNumber(n: Int): String {
        require(n >= 0)
        return n.toString().map { ('\u0660' + (it - '0')) }.joinToString("") + "."
    }
}
