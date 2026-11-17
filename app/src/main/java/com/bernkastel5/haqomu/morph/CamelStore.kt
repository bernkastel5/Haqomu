package com.bernkastel5.haqomu.morph

/** Строка таблицы stems (категории calima: VERB_STEM, NOUN_STEM, ...). */
data class StemRow(
    val cat: String,
    val pos: String,
    val lex: String,
    val lexLogprob: Double,
    val root: String?,
    /** Огласованный вид стебля (calima `diac`); пусто в паках, собранных до Ф15. */
    val diac: String = "",
)

/** Строка таблицы affixes; feats — разобранные пары "ключ:значение" calima. */
data class AffixRow(
    val cat: String,
    val feats: Map<String, String>,
)

/**
 * Источник данных морфологической базы. Реализация для устройства —
 * [AndroidSqliteCamelStore]; в JVM-тестах подменяется синтетикой.
 * Все строки уже без огласовки (так хранит calima).
 */
interface CamelStore {
    fun stems(stem: String): List<StemRow>
    fun prefixes(form: String): List<AffixRow>
    fun suffixes(form: String): List<AffixRow>

    /**
     * Таблицы совместимости категорий calima:
     * kind = 'a' — AB (prefix_cat → stem_cat), 'b' — BC (stem_cat → suffix_cat),
     * kind = 'c' — AC (prefix_cat → suffix_cat).
     */
    fun compatAllows(kind: Char, a: String, b: String): Boolean
}

/**
 * Отображение вида совместимости: анализатор спрашивает одиночной буквой
 * ('a' — префикс→стебель, 'b' — стебель→суффикс, 'c' — префикс→суффикс),
 * а в паке (и в дампе calima) таблицы называются парами 'ab'/'bc'/'ac'.
 * Держим перевод в одном месте и покрываем тестом: расхождение здесь silently
 * ломало разбор всех слов (попап отвечал «имя собственное» на что угодно).
 */
object PackCompatKey {
    fun of(kind: Char): String? = when (kind) {
        'a' -> "ab"
        'b' -> "bc"
        'c' -> "ac"
        else -> null
    }
}
