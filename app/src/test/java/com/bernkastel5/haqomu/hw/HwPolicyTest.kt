package com.bernkastel5.haqomu.hw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HwPolicyTest {

    @Test
    fun normKey_stripsDiacriticsAndUnifiesAlif() {
        assertEquals("كتب", HwPolicy.normKey("كَتَبَ"))
        assertEquals("المراه", HwPolicy.normKey("الْمَرْأَةُ"))
        assertEquals("موسي", HwPolicy.normKey("مُوسَى"))
    }

    @Test
    fun containsRootLetters_subsequence() {
        assertTrue(HwPolicy.containsRootLetters("استكتب", "كتب"))
        assertTrue(HwPolicy.containsRootLetters("مكتبة", "كتب"))
        assertFalse(HwPolicy.containsRootLetters("دفتر", "كتب"))
    }

    @Test
    fun cleanBody_removesPageNumbersAndGluedDigits() {
        assertEquals("to write", HwPolicy.cleanBody("to write 12"))
        assertEquals("Abram", HwPolicy.cleanBody("Abram9"))
        assertEquals("to flee", HwPolicy.cleanBody("and to flee"))
        assertEquals("to write", HwPolicy.cleanBody("to write <b>I</b>"))
    }

    @Test
    fun rebuildRoots_nearestPrecedingRoot() {
        val rows = listOf(
            HwPolicy.RawRow(1, "كتب", "", 1, null),
            HwPolicy.RawRow(2, "مكتبة", "", 0, null),
            HwPolicy.RawRow(3, "درس", "", 1, null),
            HwPolicy.RawRow(4, "مدارس", "", 0, null),
        )
        val plan = HwPolicy.rebuildRoots(rows)
        assertEquals(setOf(1L, 3L), plan.roots)
        assertEquals(1L, plan.rootOf[2L])
        assertEquals(3L, plan.rootOf[4L])
        assertEquals(1L, plan.rootOf[1L])
    }

    @Test
    fun rebuildRoots_rowsBeforeFirstRootHaveNoRoot() {
        val rows = listOf(
            HwPolicy.RawRow(1, "مكتبة", "", 0, null),
            HwPolicy.RawRow(2, "كتب", "", 1, null),
        )
        val plan = HwPolicy.rebuildRoots(rows)
        assertFalse(plan.rootOf.containsKey(1L))
        assertEquals(2L, plan.rootOf[2L])
    }
}
