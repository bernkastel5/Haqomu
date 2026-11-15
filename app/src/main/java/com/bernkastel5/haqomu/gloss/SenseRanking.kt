package com.bernkastel5.haqomu.gloss

/** Один кандидат на «подходит этому предложению»: номер смысла в статье и вероятность. */
data class SenseScore(val index: Int, val probability: Double)

/** Решение по статье: какой смысл поднять и на сколько уверена модель. */
data class SenseChoice(
    /** Номер смысла, который модель предлагает первым (в исходном порядке статьи). */
    val index: Int,
    /** Вероятность выбранного смысла. */
    val probability: Double,
    /** Вероятность текущего первого смысла. */
    val baselineProbability: Double,
    /** Разрыв «лучший минус текущий»: показывается в отладке и объясняет молчание модели. */
    val margin: Double,
)

/**
 * Порог и порядок смыслов (Ф10, TZ §6.3–6.4, D-004). Чистая логика, никакого ONNX.
 *
 * Правило продукта: модель меняет порядок ТОЛЬКО когда уверена — разрыв вероятностей
 * «лучший смысл минус текущий первый» не меньше [THRESHOLD] (0.3). Без порога модель
 * портит 32% верных ответов и метрика падает ниже словарного порядка (измерено).
 *
 * Второе правило из TZ §6.3: меняется только порядок смыслов ВНУТРИ статьи,
 * сама статья остаётся на своём месте — поэтому здесь нет операций над списком статей.
 */
object SenseRanking {

    /** Порог разрыва вероятностей (TZ §6.4; измерен на фикстуре, см. architecture §2.7). */
    const val THRESHOLD = 0.3

    /** Допуск сравнения: порог — правило продукта, а не упражнение в двоичной арифметике. */
    private const val EPS = 1e-9

    /** Сколько смыслов статьи вообще имеет смысл оценивать (замер — до 4 смыслов). */
    const val MAX_SENSES = 6

    /**
     * Решение по статье. [scores] — вероятности смыслов в порядке статьи.
     * Возвращает null, если:
     *  - смыслов меньше двух (менять нечего);
     *  - лучший смысл и так первый (модели нечего исправлять);
     *  - разрыв меньше порога (модель молчит, словарный порядок неприкосновенен).
     */
    fun choose(scores: List<Double>): SenseChoice? {
        if (scores.size < 2) return null
        val best = scores.indices.maxByOrNull { scores[it] } ?: return null
        if (best == 0) return null
        val baseline = scores.first()
        // Дельта и сравнение с допуском: 0.7 − 0.4 в double равно 0.29999999999999993,
        // и честный порог 0.3 без допуска не срабатывал бы на ровной границе.
        val margin = scores[best] - baseline
        if (margin + EPS < THRESHOLD) return null
        return SenseChoice(
            index = best,
            probability = scores[best],
            baselineProbability = baseline,
            margin = margin,
        )
    }

    /**
     * Перестановка смыслов статьи: выбранный смысл поднимается на первое место,
     * остальные сохраняют исходный относительный порядок. Длина списка не меняется —
     * «все смыслы статьи видны» (Ф9) остаётся в силе.
     */
    fun <T> lift(senses: List<T>, index: Int): List<T> {
        if (index <= 0 || index >= senses.size) return senses
        val chosen = senses[index]
        return listOf(chosen) + senses.filterIndexed { i, _ -> i != index }
    }

    /**
     * Доли вероятностей из сырых выходов модели. Модель — классификатор на 2 класса
     * («подходит» / «не подходит»), поэтому softmax по двум логитам; при одном выходе
     * (sigmoid-голова) значение уже является вероятностью.
     */
    fun probabilitiesFromLogits(logits: DoubleArray): DoubleArray {
        if (logits.isEmpty()) return DoubleArray(0)
        if (logits.size == 1) return doubleArrayOf(logits[0].coerceIn(0.0, 1.0))
        val max = logits.max()
        val exps = DoubleArray(logits.size) { Math.exp(logits[it] - max) }
        val sum = exps.sum()
        if (sum <= 0.0 || sum.isNaN()) return DoubleArray(logits.size) { 1.0 / logits.size }
        return DoubleArray(logits.size) { exps[it] / sum }
    }

    /** Вероятность класса «подходит» из выходов классификатора. */
    fun positiveProbability(logits: DoubleArray): Double {
        val probs = probabilitiesFromLogits(logits)
        return when (probs.size) {
            0 -> 0.0
            1 -> probs[0]
            else -> probs[1]
        }
    }
}
