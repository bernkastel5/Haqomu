package com.bernkastel5.haqomu.gloss

import com.bernkastel5.haqomu.morph.ArabicNormalize

/**
 * Выбор смысла по предложению без нейросети (Ф10/Ф11, «контекст решает»).
 *
 * Зачем. Модель смыслов опциональна: её весов в APK нет, и по умолчанию она выключена.
 * До этой правки предложение приносилось в попап, показывалось строкой контекста, но
 * порядок смыслов не меняло — пользователь видел «контекст есть, смысла нет». Здесь
 * контекст начинает работать всегда, на данных, которые уже лежат в паке: в арабских
 * толкованиях Kaikki и KSAA.
 *
 * Что считается (сигналы намеренно грубые и проверяемые):
 *  1. **Совпадение слов.** Слова предложения (без служебных) ищутся в тексте толкования.
 *     Это работает, когда толкование описывает ситуацию: «عين» — «عضو البصر» (глаз)
 *     против «منبع الماء» (родник): при предложении «نظرت في عينه» слово «البصر» не
 *     встретится, но «ينبوع» в предложении «وجدنا عيناً في الصحراء» встретится — и
 *     поднимется верный смысл. Точных совпадений мало, поэтому сигнал весит больше
 *     всего, но сам по себе редко срабатывает: этого достаточно, чтобы не врать.
 *  2. **Часть речи.** У слова-омографа разные смыслы часто разной части речи («عين» —
 *     существительное «глаз» и глагол «смотреть»). Толкования KSAA начинаются с «اسم»,
 *     «فعل»، «صفة»; если разбор слова дал часть речи, смысл с той же пометкой получает
 *     небольшой плюс. Это подсказка, а не решение: одного её мало.
 *  3. **Порядок.** Словарный порядок — база; смысл поднимается только при явном
 *     перевесе (см. [MIN_SCORE], [MIN_MARGIN]), иначе всё остаётся как было.
 *
 * Чего здесь нет: вероятностей и порога 0.3 из TZ §6.4 — это правило модели, а не
 * эвристики. У эвристики свой порог: перевес в целых единицах сигнала. Порог модели
 * не переиспользуется, чтобы не создавать видимость измеренной калибровки.
 *
 * Ничего не переставляет между статьями и не удаляет смыслы: как и модель, этот
 * ранжировщик поднимает один смысл внутри первой статьи (TZ §6.3).
 */
object ContextRanking {

    /** Минимальный собственный балл смысла, чтобы вообще считаться кандидатом. */
    const val MIN_SCORE = 2.0

    /** Минимальный перевес над текущим первым смыслом. */
    const val MIN_MARGIN = 1.0

    /** Сколько слов предложения учитывать: контекст — строка в попапе, не абзац. */
    private const val MAX_TOKENS = 40

    /** Минимальная длина арабского слова, чтобы оно что-то значило. */
    private const val MIN_WORD = 3

    /** Решение по статье. [index] — номер смысла в исходном порядке статьи. */
    data class Choice(
        val index: Int,
        /** Балл выбранного смысла. */
        val score: Double,
        /** Балл текущего первого смысла. */
        val baseline: Double,
        /** Какие слова предложения совпали с толкованием (для проверки и отладки). */
        val matched: List<String>,
    ) {
        val margin: Double get() = score - baseline
    }

    /**
     * Какой смысл поднять. Возвращает null, если контекст ничего не решает:
     * нет предложения, меньше двух смыслов, лучший — уже первый, перевес мал.
     *
     * [pos] — часть речи слова из морфологического разбора (VERB/NOUN/ADJ/…), может быть null.
     */
    fun choose(senses: List<String>, sentence: String, pos: String? = null): Choice? {
        if (senses.size < 2) return null
        val tokens = tokensOf(sentence)
        if (tokens.isEmpty()) return null
        val posGroup = posGroup(pos)

        val scores = senses.map { score(it, tokens, posGroup) }
        val best = scores.indices.maxByOrNull { scores[it].first } ?: return null
        if (best == 0) return null
        val (bestScore, matched) = scores[best]
        val baseline = scores[0].first
        if (bestScore < MIN_SCORE) return null
        if (bestScore - baseline < MIN_MARGIN) return null
        return Choice(index = best, score = bestScore, baseline = baseline, matched = matched)
    }

    /** Балл одного толкования и совпавшие слова. */
    fun score(definition: String, tokens: Set<String>, posGroup: String?): Pair<Double, List<String>> {
        val definitionTokens = tokensOf(definition)
        val matched = definitionTokens.intersect(tokens).sorted()
        var score = matched.size.toDouble()
        if (posGroup != null && declaresPos(definition, posGroup)) score += 1.0
        return score to matched
    }

    /**
     * Служебные слова и местоимения: они есть в любом предложении и в любом толковании,
     * так что как признак смысла бесполезны. Список короткий и общий — это не словарные
     * данные, а синтаксический шум.
     */
    private val STOPWORDS = setOf(
        "من", "في", "علي", "الي", "عن", "ان", "انه", "انها", "كان", "كانت", "هذا", "هذه",
        "ذلك", "تلك", "التي", "الذي", "الذين", "لا", "ما", "مع", "بعد", "قبل", "هو", "هي",
        "قد", "ثم", "او", "كل", "بين", "عند", "حتي", "اذا", "كما", "لكن", "غير", "بعض",
        "علي", "اليه", "فيه", "فيها", "منه", "منها", "به", "بها", "له", "لها", "ولا", "وما",
    )

    /** Арабские слова текста в нормализованном виде (без огласовки и татвиля). */
    fun tokensOf(text: String): Set<String> {
        if (text.isBlank()) return emptySet()
        val out = LinkedHashSet<String>()
        // Огласовку снимаем до разбиения: харака — не разделитель, «المَاء» это одно
        // слово, а не «الم» + «اء» (иначе короткие обрубки отбрасывались бы как шум).
        for (run in ARABIC_RUN.findAll(MARKS.replace(text, ""))) {
            if (out.size >= MAX_TOKENS) break
            val w = ArabicNormalize.lemmaKey(run.value)
            if (w.length < MIN_WORD) continue
            if (w in STOPWORDS) continue
            out.add(w)
        }
        return out
    }

    /** Группа части речи из пометки разбора CAMeL: VERB/NOUN/ADJ/ADV/… */
    fun posGroup(pos: String?): String? = when (pos?.trim()?.uppercase()) {
        "VERB", "PV", "IV", "CV" -> "VERB"
        "NOUN", "PROP", "PRON" -> "NOUN"
        "ADJ", "ADJP" -> "ADJ"
        "ADV" -> "ADV"
        else -> null
    }

    /** Начинается ли толкование с пометки части речи (KSAA-стиль: «اسم …», «فعل ماض …»). */
    private fun declaresPos(definition: String, group: String): Boolean {
        val head = definition.trimStart().take(12)
        val marker = when (group) {
            "VERB" -> "فعل"
            "NOUN" -> "اسم"
            "ADJ" -> "صفة"
            "ADV" -> "ظرف"
            else -> return false
        }
        return head.startsWith(marker)
    }

    /** Арабские буквы подряд; цифры, латиница и пунктуация — разделители. */
    private val ARABIC_RUN = Regex("[\\u0621-\\u063A\\u0641-\\u064A\\u066E-\\u06FF]+")

    /** Огласовка и татвиль: снимаются до разбиения на слова. */
    private val MARKS = Regex("[\\u064B-\\u0652\\u0670\\u0640]")
}
