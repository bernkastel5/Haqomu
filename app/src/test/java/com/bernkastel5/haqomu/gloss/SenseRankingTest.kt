package com.bernkastel5.haqomu.gloss

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ф10 / TZ §6.4: порог 0.3 — сердце правила. Модель меняет порядок только когда разрыв
 * «лучший минус текущий первый» не меньше порога; иначе молчит. Порядок статей не трогается.
 */
class SenseRankingTest {

    // ---------------------------------------------------------------- порог

    @Test
    fun silentWhenMarginIsBelowThreshold() {
        // 0.45 против 0.30: разрыв 0.15 — модель молчит, словарный порядок остаётся
        assertNull(SenseRanking.choose(listOf(0.30, 0.45, 0.25)))
    }

    @Test
    fun silentExactlyBelowThreshold() {
        assertNull(SenseRanking.choose(listOf(0.5, 0.799)))
    }

    @Test
    fun actsAtThreshold() {
        val choice = SenseRanking.choose(listOf(0.4, 0.7))
        assertNotNull(choice)
        assertEquals(1, choice!!.index)
        assertEquals(0.7, choice.probability, 1e-9)
        assertEquals(0.4, choice.baselineProbability, 1e-9)
        assertEquals(SenseRanking.THRESHOLD, choice.margin, 1e-9)
    }

    @Test
    fun actsAboveThresholdForLaterSense() {
        val choice = SenseRanking.choose(listOf(0.1, 0.15, 0.7))
        assertEquals(2, choice!!.index)
        assertEquals(0.6, choice.margin, 1e-9)
    }

    @Test
    fun silentWhenBestIsAlreadyFirst() {
        // модель согласна со словарём — менять нечего
        assertNull(SenseRanking.choose(listOf(0.9, 0.05, 0.05)))
    }

    @Test
    fun silentForSingleSense() {
        assertNull(SenseRanking.choose(listOf(0.99)))
        assertNull(SenseRanking.choose(emptyList()))
    }

    @Test
    fun thresholdIsTheProductRule() {
        assertEquals(0.3, SenseRanking.THRESHOLD, 0.0)
        assertEquals(0.3, BundleMeta(model = "x").threshold, 0.0)
    }

    // ---------------------------------------------------------------- перестановка

    @Test
    fun liftMovesChosenSenseFirstAndKeepsRest() {
        val senses = listOf("a", "b", "c", "d")
        assertEquals(listOf("c", "a", "b", "d"), SenseRanking.lift(senses, 2))
    }

    @Test
    fun liftKeepsAllSenses() {
        val senses = listOf("a", "b", "c")
        assertEquals(senses.size, SenseRanking.lift(senses, 1).size)
        assertEquals(senses.toSet(), SenseRanking.lift(senses, 1).toSet())
    }

    @Test
    fun liftIsNoOpForFirstOrOutOfRange() {
        val senses = listOf("a", "b")
        assertEquals(senses, SenseRanking.lift(senses, 0))
        assertEquals(senses, SenseRanking.lift(senses, 5))
        assertEquals(senses, SenseRanking.lift(senses, -1))
        assertEquals(emptyList<String>(), SenseRanking.lift(emptyList<String>(), 2))
    }

    // ---------------------------------------------------------------- вероятности

    @Test
    fun softmaxOverTwoLogitsIsMonotonic() {
        val low = SenseRanking.positiveProbability(doubleArrayOf(0.0, -2.0))
        val high = SenseRanking.positiveProbability(doubleArrayOf(0.0, 2.0))
        assertTrue(high > 0.5)
        assertTrue(low < 0.5)
        assertEquals(1.0, SenseRanking.probabilitiesFromLogits(doubleArrayOf(1.0, 2.0)).sum(), 1e-9)
    }

    @Test
    fun singleLogitIsTreatedAsProbability() {
        assertEquals(0.7, SenseRanking.positiveProbability(doubleArrayOf(0.7)), 1e-9)
        assertEquals(1.0, SenseRanking.positiveProbability(doubleArrayOf(3.0)), 1e-9)
    }

    @Test
    fun degenerateLogitsDoNotProduceNaN() {
        val probs = SenseRanking.probabilitiesFromLogits(doubleArrayOf(0.0, 0.0))
        assertEquals(0.5, probs[1], 1e-9)
        assertTrue(probs.none { it.isNaN() })
        assertTrue(SenseRanking.probabilitiesFromLogits(DoubleArray(0)).isEmpty())
    }
}
