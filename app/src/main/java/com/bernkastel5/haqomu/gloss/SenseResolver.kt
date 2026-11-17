package com.bernkastel5.haqomu.gloss

import com.bernkastel5.haqomu.pipeline.PopupContent

/**
 * Применение контекста к готовому попапу (Ф10/Ф11, TZ §3.2, §6.3–6.4).
 *
 * Работают два механизма, в этом порядке:
 *  1. **Модель** ([SenseModel], ONNX, опциональна) — как раньше, порог 0.3.
 *  2. **Эвристика по предложению** ([ContextRanking]) — если модели нет, она выключена
 *     или промолчала. Так «контекст решает» работает и без скачанных весов: смысл
 *     поднимается, только когда слова предложения действительно есть в толковании
 *     (или часть речи разбора совпала с пометкой толкования) и перевес явный.
 *     Если и эвристика не уверена — словарный порядок, как в §3.1.
 *
 * Правила, которые нельзя нарушать (регрессия недопустима):
 *  1. Статьи не переставляются: ответ пайплайна остаётся первой статьёй (D-003).
 *     Модель меняет только порядок смыслов ВНУТРИ статьи (TZ §6.3).
 *  2. Смысл поднимается только при разрыве вероятностей ≥ [SenseRanking.THRESHOLD];
 *     иначе статья остаётся словарной (модель молчит в большинстве запросов).
 *  3. Без предложения модель не запускается вовсе (TZ §3.2): словарный порядок.
 *  4. Любая ошибка модели (нет файла, битый граф, не хватило памяти) — попап как без
 *     модели; пользователь не видит исключений и не теряет статьи.
 *
 * Оцениваются смыслы первой статьи (в ней же лежит ответ пайплайна и кнопки «В Anki»
 * по каждому смыслу); остальные статьи — это статьи по леммам, их порядок смыслов
 * остаётся словарным, как и в замере.
 */
object SenseResolver {

    /** Сколько смыслов статьи отправлять в модель: больше — только медленнее. */
    const val MAX_SENSES = SenseRanking.MAX_SENSES

    /**
     * Разрешение смыслов. [model] == null (не установлена/не включена) — возвращается
     * тот же [content], без работы.
     */
    fun resolve(content: PopupContent, sentence: String, model: SenseModel?): PopupContent {
        val text = sentence.trim()
        if (text.isEmpty()) return content
        val articles = content.articles
        if (articles.isEmpty()) return content
        val first = articles.first()
        if (first.senses.size < 2) return content

        // 1. Модель (если установлена и включена) — её решение сильнее эвристики.
        if (model != null) {
            val byModel = byModel(content, text, model)
            if (byModel != null) return byModel
        }
        // 2. Эвристика по предложению — работает всегда, когда есть контекст.
        return byContext(content, text)
    }

    /**
     * Смысл по словам предложения. Поднимает один смысл в первой статье и только
     * при явном перевесе ([ContextRanking.MIN_SCORE]/[ContextRanking.MIN_MARGIN]);
     * помечается отдельно от модели, чтобы в интерфейсе не было неправды.
     */
    private fun byContext(content: PopupContent, sentence: String): PopupContent {
        val first = content.articles.first()
        val definitions = first.senses.take(MAX_SENSES).map { it.definition }
        val choice = ContextRanking.choose(definitions, sentence, pos = first.pos) ?: return content
        if (choice.index <= 0 || choice.index >= first.senses.size) return content
        val reordered = first.copy(
            senses = SenseRanking.lift(first.senses, choice.index),
            senseChosenByContext = true,
            senseChosenByModel = false,
        )
        return content.copy(articles = listOf(reordered) + content.articles.drop(1))
    }

    /** Решение модели; null — модель промолчала или не смогла (тогда решает контекст). */
    private fun byModel(content: PopupContent, text: String, model: SenseModel): PopupContent? {
        val articles = content.articles
        val first = articles.first()
        val definitions = first.senses.take(MAX_SENSES).map { it.definition }
        if (definitions.size < 2) return null

        val scores = try {
            model.score(text, definitions)
        } catch (_: Throwable) {
            // Модель — удобство, а не условие работы попапа (R2/Ф13): молча отступаем.
            return null
        }
        if (scores.size != definitions.size) return null

        val choice = SenseRanking.choose(scores.toList()) ?: return null
        if (choice.index <= 0 || choice.index >= first.senses.size) return null

        val reordered = first.copy(
            senses = SenseRanking.lift(first.senses, choice.index),
            senseChosenByModel = true,
            senseChosenByContext = false,
        )
        return content.copy(articles = listOf(reordered) + articles.drop(1))
    }
}
