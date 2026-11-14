package com.bernkastel5.haqomu.pipeline

import com.bernkastel5.haqomu.morph.ArabicNormalize

/**
 * Выбор статьи внутри словаря (Ф8, часть 1). Порт tools/pipeline_full.py::pick_entry.
 *
 * Однословные заголовки предпочтительнее составных; имя собственное, кораническая
 * помета и пассив штрафуются (не отбрасываются, если альтернатив нет);
 * точное совпадение с запросом — бонус +100, префиксное — +15;
 * позиция в выдаче словаря — штраф −0.5 за позицию.
 * strict=false — fallback без требования однословности.
 */
object PickEntry {

    fun pick(entries: List<DictionaryEntry>, query: String, strict: Boolean = true): Pair<Double, DictionaryEntry>? {
        val q = ArabicNormalize.lemmaKey(query)
        var best: Pair<Double, DictionaryEntry>? = null
        for ((i, s) in entries.withIndex()) {
            if (strict && !s.isSingleWord) continue
            var sc = 0.0
            val h = ArabicNormalize.lemmaKey(s.head)
            if (q.isNotEmpty() && h.isNotEmpty()) {
                if (h == q) {
                    sc += 100.0
                } else if (h.startsWith(q) || q.startsWith(h)) {
                    sc += 15.0
                }
            }
            if (isProper(s)) sc -= 40.0
            if (isQuranic(s)) sc -= 20.0
            if (isPassive(s)) sc -= 15.0
            if (s.hasEnglish) sc += 3.0
            sc -= i * 0.5
            val cur = best
            if (cur == null || sc > cur.first) best = sc to s
        }
        if (best == null && strict) return pick(entries, query, strict = false)
        return best
    }

    /** Имя собственное: POS-помета Kaikki/KSAA-стиля или толкование со словами «اسم علم». */
    fun isProper(e: DictionaryEntry): Boolean {
        val pos = e.pos?.lowercase()?.trim()
        if (pos != null && pos in PROPER_POS) return true
        return e.senses.any { "اسم علم" in it.definition }
    }

    /** Кораническая помета в первом смысле. */
    fun isQuranic(e: DictionaryEntry): Boolean =
        e.senses.firstOrNull()?.let { "قرآني" in it.definition } == true

    /** Пассив (KSAA-стиль: «فعل ماض مبني للمجهول …») в первом смысле. */
    fun isPassive(e: DictionaryEntry): Boolean =
        e.senses.firstOrNull()?.definition?.startsWith("فعل ماض مبني للمجهول") == true

    private val PROPER_POS = setOf(
        "name", "proper-noun", "proper noun", "proper_noun", "propernoun", "prop", "proper",
    )
}
