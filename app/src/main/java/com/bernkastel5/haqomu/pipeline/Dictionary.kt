package com.bernkastel5.haqomu.pipeline

/** Смысл статьи §4-пака: арабское определение + английский эквивалент (если есть). */
data class DictionarySense(
    val definition: String,
    val glossEn: String?,
)

/**
 * Статья словарного §4-пака. Корень хранится так же, как в паке сборщиками:
 * нормализованный (nk-совместимый), без пробелов между буквами.
 */
data class DictionaryEntry(
    val head: String,
    val headNorm: String,
    val root: String?,
    val pos: String?,
    /** Семейство источника: "ksaa" | "kaikki" | "wehr" | "other". */
    val sourceFamily: String,
    /** Все смыслы статьи по порядку (Ф9: «все смыслы видны»). */
    val senses: List<DictionarySense>,
) {
    /** Эталон pipeline_full.py: однословные заголовки предпочтительнее составных. */
    val isSingleWord: Boolean
        get() {
            val h = head.trim()
            return h.isNotEmpty() && h.none { it == ' ' || it == '\t' || it == '\u00A0' }
        }

    val hasEnglish: Boolean
        get() = senses.any { !it.glossEn.isNullOrBlank() }

    fun firstSense(): DictionarySense? = senses.firstOrNull()
}

/**
 * Источник словарных статей (установленные §4-паки).
 * JVM-тесты подменяют синтетикой; в приложении — [com.bernkastel5.haqomu.pack.AndroidPackDictionary].
 */
interface DictionarySource {
    /**
     * Статьи с головой, чья норма совпадает с queryNorm (уже nk-нормализованный запрос).
     * limitPerSource — потолок статей с одного пака; порядок стабилен: семейства в порядке
     * приоритета (ksaa, kaikki, wehr, other), внутри пака — порядок вставки.
     */
    fun findByHeadNorm(queryNorm: String, limitPerSource: Int = 12): List<DictionaryEntry>

    fun hasHead(queryNorm: String): Boolean = findByHeadNorm(queryNorm, 1).isNotEmpty()
}

object Families {
    const val KSAA = "ksaa"
    const val KAIKKI = "kaikki"
    const val WEHR = "wehr"
    const val OTHER = "other"

    /** Ф8: при равенстве подтверждений побеждает семейство с меньшим номером. */
    private val PRIORITY = mapOf(KSAA to 0, KAIKKI to 1, WEHR to 2)

    fun priority(family: String): Int = PRIORITY[family] ?: 3

    /** Порядок семейств при обходе паков (стабильный порядок кандидатов). */
    val ORDER = listOf(KSAA, KAIKKI, WEHR, OTHER)
}
