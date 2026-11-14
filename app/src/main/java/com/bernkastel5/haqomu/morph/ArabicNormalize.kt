package com.bernkastel5.haqomu.morph

/**
 * Единая нормализация: снятие огласовки и татвиля, унификация букв.
 * Эквивалент un()/key() из tools/name_class.py и DEFAULT_NORMALIZE_MAP CAMeL
 * (إ أ آ ٱ → ا, ى → ي, ة → ه). Оригинал строки хранить отдельно для показа.
 */
object ArabicNormalize {

    private val HARAKAT_AND_TATWEEL = Regex("[\\u0640\\u064B-\\u0652\\u0670]")

    fun un(s: String?): String {
        if (s == null) return ""
        return s
            .replace(HARAKAT_AND_TATWEEL, "")
            .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا').replace('ٱ', 'ا')
            .replace('ى', 'ي').replace('ة', 'ه')
    }

    fun key(s: String?): String = un(s)
}
