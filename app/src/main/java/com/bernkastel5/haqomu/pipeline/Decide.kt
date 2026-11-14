package com.bernkastel5.haqomu.pipeline

import com.bernkastel5.haqomu.morph.ArabicNormalize

/** Кандидат на ответ попапа (одна статья из одного словаря). */
data class PopupCandidate(
    val entry: DictionaryEntry,
    /** 0 — прямой запрос по словоформе, 1 — старшая лемма, 2 — вторая лемма. */
    val stage: Int,
) {
    val head: String get() = entry.head
    val sourceFamily: String get() = entry.sourceFamily
    val headKey: String get() = ArabicNormalize.lemmaKey(entry.head)
    val rootKey: String get() = entry.root?.let { ArabicNormalize.normRoot(it) } ?: ""
}

/** Итог голосования. */
data class Decided(
    val candidate: PopupCandidate,
    /** Сколько баллов подтверждения набрал ответ (голова +2, корень +1, за каждое чужое семейство). */
    val support: Int,
    /** Семейства словарей, подтвердившие ответ. */
    val witnesses: List<String>,
)

/**
 * Голосование между словарями (Ф8, часть 2). Порт tools/pipeline_full.py::decide,
 * обобщённый на произвольное число семейств (офлайн в приложении доступны kaikki и wehr;
 * семейство ksaa подмешивается, когда пак KSAA установлен).
 *
 * Семейство не подтверждает само себя. Пара из разных семейств подтверждает друг друга:
 * заголовок совпал (lemmaKey) — +2, корень совпал — +1.
 *
 * Шаг 1 — внутри семейства побеждает кандидат с максимальным внешним подтверждением;
 *         при равенстве — более ранняя стадия (прямой запрос важнее леммы).
 *         Внешние свидетельства берутся у локальных представителей остальных семейств
 *         (прямой запрос, иначе старшая лемма) — так эталон сводится к случаю
 *         «ровно один ответ на словарь».
 * Шаг 2 — между семействами: максимум поддержки; при равенстве — приоритет
 *         ksaa → kaikki → wehr → прочие; затем — более ранняя стадия.
 */
object Decide {

    /** Голова совпала — +2, корней совпали — +1; одно семейство — 0. */
    fun agree(famA: String, headA: String, famB: String, headB: String): Int {
        if (famA == famB) return 0
        if (headA.isEmpty() || headB.isEmpty()) return 0
        if (ArabicNormalize.lemmaKey(headA) == ArabicNormalize.lemmaKey(headB)) return 2
        if (headA == headB) return 1
        return 0
    }

    fun decide(candidates: List<PopupCandidate>): Decided? {
        if (candidates.isEmpty()) return null
        val byFamily = candidates.groupBy { it.sourceFamily }
        // Проход 0: локальный представитель семейства — прямой запрос, иначе старшая лемма.
        val localReps = byFamily.mapValues { (_, list) -> list.minByOrNull { it.stage }!! }
        // Проход 1: внутри семейства — выбор по внешней поддержке против прохода 0.
        val reps = HashMap<String, PopupCandidate>(byFamily.size)
        for ((fam, list) in byFamily) {
            reps[fam] = list.maxWithOrNull(
                compareBy({ supportOf(it, localReps, fam).first }, { -it.stage }),
            )!!
        }
        // Проход 2: голосование между семействами.
        var bestRank: List<Int>? = null
        var best: Decided? = null
        for ((fam, winner) in reps) {
            val (support, witnesses) = supportOf(winner, reps, fam)
            val rank = listOf(support, -Families.priority(fam), -winner.stage)
            if (bestRank == null || compareIntLists(rank, bestRank) > 0) {
                bestRank = rank
                best = Decided(winner, support, witnesses)
            }
        }
        return best
    }

    private fun supportOf(
        candidate: PopupCandidate,
        reps: Map<String, PopupCandidate>,
        selfFam: String,
    ): Pair<Int, List<String>> {
        var support = 0
        val witnesses = mutableListOf<String>()
        for ((fam, other) in reps) {
            if (fam == selfFam) continue
            var s = agree(selfFam, candidate.head, fam, other.head)
            if (s == 0) {
                val ra = candidate.rootKey
                val rb = other.rootKey
                if (ra.isNotEmpty() && rb.isNotEmpty() && ra == rb) s = 1
            }
            if (s != 0) {
                support += s
                witnesses += fam
            }
        }
        return support to witnesses
    }

    private fun compareIntLists(a: List<Int>, b: List<Int>): Int {
        for (i in a.indices) {
            val c = a[i].compareTo(b[i])
            if (c != 0) return c
        }
        return 0
    }
}
