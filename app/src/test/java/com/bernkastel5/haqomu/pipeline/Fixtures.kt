package com.bernkastel5.haqomu.pipeline

import com.bernkastel5.haqomu.morph.AffixRow
import com.bernkastel5.haqomu.morph.ArabicNormalize
import com.bernkastel5.haqomu.morph.CamelStore
import com.bernkastel5.haqomu.morph.StemRow

/**
 * Общие синтетические фикстуры JVM-тестов Ф4–Ф9 (словарных данных в репозитории нет).
 * Повторяют calima: стебли без огласовки, пустые аффиксы, таблица compat.
 */
object FakeCamel {

    fun store(): CamelStore {
        val stems = mapOf(
            "ذهب" to listOf(StemRow("VERB_STEM", "VERB", "ذهب", -0.50, "ذ ه ب")),
            "اخرج" to listOf(StemRow("VERB_STEM", "VERB", "اخرج", -0.60, "خ ر ج")),
            "عط" to listOf(StemRow("VERB_STEM", "VERB", "عطى", -0.40, "ع ط و")),
            "بيت" to listOf(StemRow("NOUN_STEM", "NOUN", "بيت", -0.70, "ب ي ت")),
        )
        val empty = mapOf("pos" to "-", "lex" to "-") // аффиксы не несут полезного lex_logprob
        val prefixes = mapOf("" to listOf(AffixRow("NOPREF", empty)))
        val suffixes = mapOf("" to listOf(AffixRow("NSUFF", empty)))
        val compat = setOf(
            Triple('a', "NOPREF", "VERB_STEM"), Triple('a', "NOPREF", "NOUN_STEM"),
            Triple('b', "VERB_STEM", "NSUFF"), Triple('b', "NOUN_STEM", "NSUFF"),
            Triple('c', "NOPREF", "NSUFF"),
        )
        return object : CamelStore {
            override fun stems(stem: String): List<StemRow> = stems[stem] ?: emptyList()
            override fun prefixes(form: String): List<AffixRow> = prefixes[form] ?: emptyList()
            override fun suffixes(form: String): List<AffixRow> = suffixes[form] ?: emptyList()
            override fun compatAllows(kind: Char, a: String, b: String): Boolean = compat.contains(Triple(kind, a, b))
        }
    }
}

/** Статья §4-пака для тестов; headNorm считается тем же ключом, что и в сборщиках паков. */
fun entry(
    head: String,
    family: String,
    senses: List<Pair<String, String?>>,
    root: String? = null,
    pos: String? = null,
): DictionaryEntry = DictionaryEntry(
    head = head,
    headNorm = ArabicNormalize.nk(head),
    root = root,
    pos = pos,
    sourceFamily = family,
    senses = senses.map { (def, en) -> DictionarySense(def, en) },
)

/** In-memory словарь: семейства обходятся по приоритету, limit — на семейство; как в паках. */
class FakeDictionary(entries: List<DictionaryEntry>) : DictionarySource {
    private val all = entries.toList()

    override fun findByHeadNorm(queryNorm: String, limitPerSource: Int): List<DictionaryEntry> {
        if (queryNorm.isEmpty()) return emptyList()
        val known = Families.ORDER.toSet()
        val families = Families.ORDER + all.map { it.sourceFamily }.filter { it !in known }.distinct().sorted()
        val out = mutableListOf<DictionaryEntry>()
        for (family in families) {
            var taken = 0
            for (e in all) {
                if (e.sourceFamily != family || e.headNorm != queryNorm) continue
                if (taken >= limitPerSource) break
                taken += 1
                out += e
            }
        }
        return out
    }
}
