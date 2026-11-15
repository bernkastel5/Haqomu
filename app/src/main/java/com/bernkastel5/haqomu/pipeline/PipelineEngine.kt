package com.bernkastel5.haqomu.pipeline

import com.bernkastel5.haqomu.morph.Analyzer
import com.bernkastel5.haqomu.morph.ArabicNormalize

/** Одна статья в попапе. */
data class PopupArticle(
    val head: String,
    val root: String?,
    val pos: String?,
    val sourceFamily: String,
    /** Все смыслы статьи по порядку (Ф9: «все смыслы видны»). */
    val senses: List<DictionarySense>,
    /** Откуда статья: "answer" — ответ пайплайна, иначе "lemma <лемма>". */
    val rank: String,
    /** Ф10: смысл поднят моделью по предложению (в интерфейсе — пометка «выбран моделью»). */
    val senseChosenByModel: Boolean = false,
    /** Баллы внешнего подтверждения (для ответа пайплайна). */
    val support: Int,
    val isAnswer: Boolean,
)

enum class PopupMode {
    /** Обычный попап со статьями. */
    ARTICLES,

    /** «Похоже на имя собственное, словарной статьи нет» (Ф5). */
    NAME_PLATE,
}

data class PopupContent(
    val original: String,
    val mode: PopupMode,
    val wordKind: WordKind,
    /** Статьи в порядке показа; первая — ответ пайплайна (может отсутствовать). */
    val articles: List<PopupArticle>,
    val lemmas: List<String>,
    /** Основа для CLITIC (то, что не является клитикой). */
    val cliticBase: String? = null,
)

/**
 * Ядро пайплайна попапа (Ф6–Ф9). Порядок стадий — docs/architecture.md §2 и
 * tools/pipeline_full.py / pipeline_v2.py / crossrank.py.
 *
 * Ф6 Леммы: до двух старших по lex_logprob.
 * Ф7 Кандидаты: прямой запрос по словоформе + по каждой из лемм, по каждому установленному
 *              словарному паку; внутри словаря выбирает PickEntry (Ф8, часть 1).
 * Ф8 Голосование: Decide — внутри семейства по внешней поддержке, затем между семействами
 *              (ksaa → kaikki → wehr).
 * Ф9 Попап: до 3 статей; ответ пайплайна всегда первая статья; ниже — статьи по леммам
 *           (сначала старшая лемма, при равенстве — приоритет словаря);
 *           дедуп по паре «голова + первый смысл» (для арабского текста — первые 24 знака
           после nk, для латинских глоссов Kaikki/Wehr — первые 24 знака в нижнем регистре:
           иначе у английских определений ключ вырождается в пустую строку и разные статьи
           схлопываются).
 *
 * Инвариант (TZ §6, регрессия): ответ базового пайплайна — всегда первая статья;
 * ложных «имён собственных» не больше, чем допускает фикстура (JVM-тесты на синтетике).
 */
class PipelineEngine(
    private val analyzer: Analyzer,
    private val dictionary: DictionarySource,
) {

    private val classifier = WordClassifier(analyzer, dictionary)

    fun lookup(rawWord: String): PopupContent {
        val original = rawWord.trim()
        val wc = classifier.classify(original)

        // Ф7: кандидаты из словарных паков — прямой запрос и по леммам.
        val candidates = mutableListOf<PopupCandidate>()
        val directEntries = dictionary.findByHeadNorm(ArabicNormalize.nk(original))
        for ((family, entries) in directEntries.groupBy { it.sourceFamily }) {
            PickEntry.pick(entries, original)?.let { (_, e) ->
                candidates += PopupCandidate(entry = e, stage = 0)
            }
        }

        // Плашка «имя собственное» — только если словарной статьи тоже нет. Иначе
        // сбой морфологии (пак не установлен, база нечитаема) превращал бы в «имя»
        // любое слово, у которого статья в словаре есть.
        if (wc.kind == WordKind.NAME && candidates.isEmpty()) {
            return PopupContent(
                original = original,
                mode = PopupMode.NAME_PLATE,
                wordKind = WordKind.NAME,
                articles = emptyList(),
                lemmas = emptyList(),
            )
        }

        val lemmas = wc.lemmas.distinct().take(2) // Ф6
        for ((i, lemma) in lemmas.withIndex()) {
            val lemmaEntries = dictionary.findByHeadNorm(ArabicNormalize.nk(lemma))
            for ((family, entries) in lemmaEntries.groupBy { it.sourceFamily }) {
                PickEntry.pick(entries, lemma)?.let { (_, e) ->
                    candidates += PopupCandidate(entry = e, stage = i + 1)
                }
            }
        }

        // Ф8: голосование.
        val answer = Decide.decide(candidates)

        // Ф9: сборка попапа.
        val articles = mutableListOf<PopupArticle>()
        val seen = HashSet<String>()
        if (answer != null) {
            seen += dedupeKey(answer.candidate.entry)
            articles += articleOf(answer.candidate, rank = "answer", support = answer.support, isAnswer = true)
        }
        for (i in lemmas.indices) {
            if (articles.size >= MAX_ARTICLES) break
            val stageCandidates = candidates
                .filter { it.stage == i + 1 }
                .sortedBy { Families.priority(it.sourceFamily) }
            for (c in stageCandidates) {
                if (articles.size >= MAX_ARTICLES) break
                val key = dedupeKey(c.entry)
                if (key in seen) continue
                seen += key
                articles += articleOf(c, rank = "лемма ${lemmas[i]}", support = 0, isAnswer = false)
            }
        }

        return PopupContent(
            original = original,
            mode = PopupMode.ARTICLES,
            wordKind = wc.kind,
            articles = articles.take(MAX_ARTICLES),
            lemmas = lemmas,
            cliticBase = wc.base,
        )
    }

    private fun articleOf(c: PopupCandidate, rank: String, support: Int, isAnswer: Boolean) =
        PopupArticle(
            head = c.entry.head,
            root = c.entry.root,
            pos = c.entry.pos,
            sourceFamily = c.sourceFamily,
            senses = c.entry.senses,
            rank = rank,
            support = support,
            isAnswer = isAnswer,
        )

    private fun dedupeKey(e: DictionaryEntry): String {
        val def = e.firstSense()?.definition ?: ""
        val normDef = ArabicNormalize.nk(def)
        val first = if (normDef.isNotEmpty()) normDef.take(24) else def.trim().lowercase().take(24)
        return ArabicNormalize.lemmaKey(e.head) + "|" + first
    }

    companion object {
        /** Ф9 / architecture §2.8: не больше трёх статей в попапе. */
        const val MAX_ARTICLES = 3
    }
}
