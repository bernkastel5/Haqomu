package com.bernkastel5.haqomu.gloss

import com.bernkastel5.haqomu.pipeline.PopupContent

/**
 * Применение модели разрешения смыслов к готовому попапу (Ф10, TZ §3.2, §6.3–6.4).
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
        if (model == null) return content
        val text = sentence.trim()
        if (text.isEmpty()) return content
        val articles = content.articles
        if (articles.isEmpty()) return content

        val first = articles.first()
        val definitions = first.senses.take(MAX_SENSES).map { it.definition }
        if (definitions.size < 2) return content

        val scores = try {
            model.score(text, definitions)
        } catch (_: Throwable) {
            // Модель — удобство, а не условие работы попапа (R2/Ф13): молча отступаем.
            return content
        }
        if (scores.size != definitions.size) return content

        val choice = SenseRanking.choose(scores.toList()) ?: return content
        if (choice.index <= 0 || choice.index >= first.senses.size) return content

        val reordered = first.copy(
            senses = SenseRanking.lift(first.senses, choice.index),
            senseChosenByModel = true,
        )
        return content.copy(articles = listOf(reordered) + articles.drop(1))
    }
}
