package com.bernkastel5.haqomu.pipeline

import com.bernkastel5.haqomu.morph.Analyzer
import com.bernkastel5.haqomu.morph.ArabicNormalize

enum class WordKind { WORD, CLITIC, NAME }

/**
 * Результат Ф5 (TZ §4): словарное слово / форма с клитикой / имя собственное.
 * [lemmas] для WORD — до 4 лемм словоформы (Ф6 берёт две старшие);
 * для CLITIC — леммы основы; для NAME — пусто.
 */
data class WordClassResult(
    val kind: WordKind,
    val lemmas: List<String>,
    val base: String? = null,
    val prefix: String? = null,
    val suffix: String? = null,
)

/**
 * Ф5: классификатор «словарное / клитика / имя». Порт tools/name_class.py.
 *
 * Правила (порядок важен):
 *  1. CAMeL (calima-msa-r13) дал разбор → WORD.
 *  2. Разбора нет → раскладываем на клитику и основу; требуются одновременно:
 *     (а) CAMeL разбирает основу (знает POS);
 *     (б) основа есть в словарном паке ИЛИ принадлежит служебной части речи
 *         (pron/part/conj/prep/det/interj) — словарной статьи ей не требуется;
 *     (в) суффикс совместим с частью речи основы: глагольное окончание
 *         (ي ون ين ات وا ت) — только при основе-глаголе и показателе ا/أ/пустой префикс;
 *         притяжательное/объектное окончание — при имени, прилагательном, глаголе или местоимении;
 *         слабую букву восстанавливаем только при притяжательных окончаниях и без суффикса.
 *  3. Ничего не подошло → NAME («похоже на имя собственное, словарной статьи нет»).
 *
 * Отличия от эталона, мотивированные устройством приложения:
 *  - разложение работает на строке без огласовки, но С сохранёнными видами хамзы —
 *    как и в эталоне (un() снимает только огласовку): иначе «اخرجي» не разложить;
 *  - проверка головы в словаре идёт по nk-ключу (алиф унифицирован), как и эталонная
 *    работа с ksaa_heads();
 *  - «есть статья в словаре» понимается по установленным §4-пакам (KSAA — онлайн-задача,
 *    отдельная фаза), а не по живому API.
 */
class WordClassifier(
    private val analyzer: Analyzer,
    private val dictionary: DictionarySource,
) {

    fun classify(rawWord: String): WordClassResult {
        val word = stripMarks(rawWord)
        if (word.isEmpty()) return WordClassResult(WordKind.NAME, emptyList())

        val lemmas = analyzer.lemmas(word)
        if (lemmas.isNotEmpty()) {
            return WordClassResult(WordKind.WORD, lemmas.take(4))
        }

        for ((pre, base, suf) in decompose(word)) {
            val pos = posSet(base)
            if (pos.isEmpty()) continue
            val hasHead = dictionary.hasHead(ArabicNormalize.nk(base))
            if (!hasHead && pos.intersect(SERVICE_POS).isEmpty()) continue
            if (suf in VERB_SUF && !("verb" in pos && pre in VERB_SUF_PRE)) continue
            if (suf in POSS_SUF && pos.intersect(POSS_SUF_POS).isEmpty()) continue
            val baseLemmas = analyzer.lemmas(base)
            return WordClassResult(
                WordKind.CLITIC,
                (baseLemmas.ifEmpty { listOf(base) }).take(3),
                base = base,
                prefix = pre.ifEmpty { null },
                suffix = suf.ifEmpty { null },
            )
        }
        return WordClassResult(WordKind.NAME, emptyList())
    }

    private fun posSet(word: String): Set<String> =
        analyzer.analyze(word).map { it.pos.lowercase() }.toSet()

    companion object {
        private val MARKS = Regex("[\\u064B-\\u0652\\u0670\\u0640]")

        /** Эталон un(): снимает только огласовку и татвиль, хамзу сохраняет. */
        fun stripMarks(s: String): String = MARKS.replace(s, "")

        val PRE = listOf("وال", "بال", "كال", "فال", "لل", "ال", "ها", "أ", "و", "ف", "ب", "ك", "ل")
        val SUF = listOf("هما", "كما", "هم", "هن", "ها", "نا", "ني", "ون", "ين", "ات", "وا", "ه", "ك", "ت", "ي")
        val VERB_SUF = setOf("ي", "ون", "ين", "ات", "وا", "ت")
        val POSS_SUF = setOf("ني", "ه", "ها", "هم", "هن", "نا", "ك", "كما", "هما")

        private val WEAK = listOf("", "ا", "و", "ي", "ى", "ة")
        private val VERB_SUF_PRE = setOf("ا", "أ", "")
        private val SERVICE_POS = setOf("pron", "part", "conj", "prep", "det", "interj", "intj")
        private val POSS_SUF_POS = setOf("noun", "adj", "verb", "pron")

        /**
         * Все разложения «префикс + основа + суффикс». Слабую букву в конце основы
         * восстанавливаем только при притяжательном суффиксе или его отсутствии —
         * иначе «أعط» + ي = أعطي, но «كوت» + ا при «ون» — нельзя.
         */
        fun decompose(word: String): List<Triple<String, String, String>> {
            val w = stripMarks(word)
            val out = LinkedHashSet<Triple<String, String, String>>()
            for (pre in listOf("") + PRE) {
                if (pre.isNotEmpty() && !w.startsWith(pre)) continue
                val rest = w.substring(pre.length)
                if (rest.length < 2) continue
                for (suf in listOf("") + SUF) {
                    if (suf.isNotEmpty() && !rest.endsWith(suf)) continue
                    val stem = if (suf.isNotEmpty()) rest.substring(0, rest.length - suf.length) else rest
                    if (stem.length < 2) continue
                    val weaks = if (suf in POSS_SUF || suf.isEmpty()) WEAK else listOf("")
                    for (wk in weaks) out += Triple(pre, stem + wk, suf)
                }
            }
            return out.toList()
        }
    }
}
