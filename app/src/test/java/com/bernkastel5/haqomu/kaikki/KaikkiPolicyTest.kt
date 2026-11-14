package com.bernkastel5.haqomu.kaikki

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KaikkiPolicyTest {

    @Test
    fun norm_stripsDiacriticsAndUnifies() {
        assertEquals("كتب", KaikkiPolicy.norm("كَتَبَ"))
        assertEquals("موسي", KaikkiPolicy.norm("مُوسَى"))
        assertEquals("فتاه", KaikkiPolicy.norm("فَتَاة"))
    }

    @Test
    fun isArabicWord_rejectsNonArabic() {
        assertTrue(KaikkiPolicy.isArabicWord("كَتَبَ"))
        assertFalse(KaikkiPolicy.isArabicWord("write"))
        assertFalse(KaikkiPolicy.isArabicWord(""))
        assertFalse(KaikkiPolicy.isArabicWord(null))
    }

    @Test
    fun planSenses_skipsBadTagsKeepsOrder() {
        val plan = KaikkiPolicy.planSenses(
            listOf(
                KaikkiPolicy.RawSense("obsolete sense", listOf("obsolete"), null),
                KaikkiPolicy.RawSense("to write", listOf("form-I"), "كتب الدرس"),
                KaikkiPolicy.RawSense("to inscribe", emptyList(), null),
            )
        )
        requireNotNull(plan)
        assertEquals(2, plan.size)
        assertEquals(1, plan[0].ord)
        assertEquals("to write", plan[0].definition)
        assertEquals("كتب الدرس", plan[0].example)
        assertEquals(2, plan[1].ord)
        assertEquals("to inscribe", plan[1].definition)
    }

    @Test
    fun planSenses_fallbackToFirstGlossWhenAllFiltered() {
        val plan = KaikkiPolicy.planSenses(
            listOf(
                KaikkiPolicy.RawSense("", emptyList(), null),
                KaikkiPolicy.RawSense("to write", listOf("archaic"), null),
            )
        )
        requireNotNull(plan)
        assertEquals(1, plan.size)
        assertEquals("to write", plan[0].definition)
    }

    @Test
    fun planSenses_formOfGlossesSkipped() {
        val plan = KaikkiPolicy.planSenses(
            listOf(KaikkiPolicy.RawSense("plural of كتب", emptyList(), null))
        )
        requireNotNull(plan)
        // все отфильтрованы → fallback берёт первый непустой глосс
        assertEquals("plural of كتب", plan[0].definition)
    }

    @Test
    fun planSenses_allEmpty_returnsNull() {
        assertNull(KaikkiPolicy.planSenses(listOf(KaikkiPolicy.RawSense("", emptyList(), null))))
    }

    @Test
    fun rootFromCategories_extractsRootLetters() {
        assertEquals(
            "كتب",
            KaikkiPolicy.rootFromCategories(listOf("Arabic terms belonging to the root ك ت ب")),
        )
        assertNull(KaikkiPolicy.rootFromCategories(listOf("Arabic nouns")))
    }
}
