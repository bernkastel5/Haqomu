package com.bernkastel5.haqomu.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ф8: выбор внутри словаря (PickEntry) и голосование между словарями (Decide).
 * Правила до прогона, не подгонялись под набор (как и в эталоне).
 */
class DecideTest {

    // ------------------------------------------------------------ PickEntry

    @Test
    fun exactMatchBeatsPosition() {
        val entries = listOf(
            entry("ذهب", Families.KAIKKI, listOf("first sense" to "first sense"), pos = "verb"),
            entry("ذهب", Families.KAIKKI, listOf("second sense" to "second sense"), pos = "noun"),
        )
        val (score, e) = PickEntry.pick(entries, "ذهب")!!
        assertEquals("ذهب", e.head)
        assertEquals(103.0, score, 1e-9) // 100 за точное совпадение + 3 за английский глосс
    }

    @Test
    fun properNounIsPenalizedNotDropped() {
        val entries = listOf(
            entry("كرم", Families.KAIKKI, listOf("liberality" to "liberality"), pos = "noun"),
            entry("كريم", Families.KAIKKI, listOf("Karim, a male name" to "Karim, a male name"), pos = "name"),
        )
        val (_, e) = PickEntry.pick(entries, "كرم")!!
        assertEquals("كرم", e.head)
        assertTrue(score(entries, "كريم") < score(entries, "كرم"))
    }

    private fun score(entries: List<DictionaryEntry>, query: String): Double =
        PickEntry.pick(entries.filter { it.head == query }, query)!!.first

    @Test
    fun multiwordHeadsFallBackOnlyWhenNothingElse() {
        val only = listOf(entry("دار البيضاء", Families.KAIKKI, listOf("Casablanca" to "Casablanca"), pos = "name"))
        // строгое правило не находит однословных → fallback на составную голову
        val (_, e) = PickEntry.pick(only, "بيضاء", strict = true)!!
        assertEquals("دار البيضاء", e.head)
    }

    // ------------------------------------------------------------ Decide

    @Test
    fun agreeHeadMatchTwoPointsSameFamilyZero() {
        assertEquals(2, Decide.agree(Families.KAIKKI, "ذهب", Families.WEHR, "ذَهَبَ"))
        assertEquals(0, Decide.agree(Families.KAIKKI, "ذهب", Families.KAIKKI, "ذهب"))
        assertEquals(0, Decide.agree(Families.KAIKKI, "ذهب", Families.WEHR, "بيت"))
    }

    @Test
    fun intraFamilySupportWinsOverEarlierStage() {
        // внутри kaikki: прямой запрос (stage 0, без поддержки) против леммы (stage 1, голова совпадает с Wehr)
        val kaikkiDirect = PopupCandidate(entry("كرم", Families.KAIKKI, listOf("liberality" to "liberality")), stage = 0)
        val kaikkiLemma = PopupCandidate(entry("ذهب", Families.KAIKKI, listOf("to go" to "to go")), stage = 1)
        val wehrDirect = PopupCandidate(entry("ذهب", Families.WEHR, listOf("to go" to "to go")), stage = 0)
        val d = Decide.decide(listOf(kaikkiDirect, kaikkiLemma, wehrDirect))!!
        // победитель внутри kaikki — лемма (+2 от Wehr); все три в голосовании,
        // итоговое равенство поддержки решает приоритет kaikki > wehr
        assertEquals(Families.KAIKKI, d.candidate.sourceFamily)
        assertEquals("ذهب", d.candidate.head)
        assertEquals(1, d.candidate.stage)
    }

    @Test
    fun intraFamilyTieKeepsEarlierStage() {
        val kaikkiDirect = PopupCandidate(entry("ذهب", Families.KAIKKI, listOf("to go" to "to go")), stage = 0)
        val kaikkiLemma = PopupCandidate(entry("ذهب", Families.KAIKKI, listOf("to go" to "to go")), stage = 1)
        val d = Decide.decide(listOf(kaikkiLemma, kaikkiDirect))!!
        assertEquals(0, d.candidate.stage)
    }

    @Test
    fun familyPriorityBreaksTieKaikkiOverWehr() {
        val k = PopupCandidate(entry("ذهب", Families.KAIKKI, listOf("to go" to "to go")), stage = 0)
        val w = PopupCandidate(entry("ذهب", Families.WEHR, listOf("to go" to "to go")), stage = 0)
        val d = Decide.decide(listOf(w, k))!!
        assertEquals(Families.KAIKKI, d.candidate.sourceFamily)
        assertEquals(emptyList<String>(), d.witnesses.filter { it == Families.KAIKKI })
    }

    @Test
    fun rootAgreementGivesOnePoint() {
        val k = PopupCandidate(entry("كرامة", Families.KAIKKI, listOf("dignity" to "dignity"), root = "كرم"), stage = 0)
        val w = PopupCandidate(entry("كرم", Families.WEHR, listOf("to be generous" to "to be generous"), root = "كرم"), stage = 0)
        val d = Decide.decide(listOf(k, w))!!
        assertEquals(1, d.support)
        assertEquals(Families.KAIKKI, d.candidate.sourceFamily) // приоритет при равенстве
    }

    @Test
    fun noCandidatesNoDecision() {
        assertNull(Decide.decide(emptyList()))
    }
}
