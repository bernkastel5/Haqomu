package com.bernkastel5.haqomu.hw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun parseEntry_xrefSee() {
        // жадный head «كَتَبَ kataba» съедает латинский токен — как в эталоне rom тогда не извлекается
        val p = HwPolicy.parseEntry("كَتَبَ", "كَتَبَ kataba see كَتَبَ")
        assertEquals("xref", p.kind)
        assertEquals("كَتَبَ", p.seeTarget)
        assertNull(p.romanization)
    }

    @Test
    fun parseEntry_xrefWithTwoTokenArabicHeadAndRom() {
        val p = HwPolicy.parseEntry("فرصة", "فرصة ثانية thaniya see فرصة")
        assertEquals("xref", p.kind)
        assertEquals("فرصة", p.seeTarget)
        assertEquals("thaniya", p.romanization)
    }

    @Test
    fun parseEntry_alphaRef() {
        val p = HwPolicy.parseEntry("كلمة", "look up alphabetically under ق")
        assertEquals("xref_alpha", p.kind)
    }

    @Test
    fun parseEntry_bodyAndForms() {
        val p = HwPolicy.parseEntry(
            "كَتَبَ",
            "كَتَبَ kataba to write, to inscribe <b>I</b> pl. كُتُب books 15",
        )
        assertEquals("entry", p.kind)
        assertEquals("kataba", p.romanization)
        assertEquals("I", p.verbForms)
        assertTrue(p.plurals.contains("كُتُب"))
        assertFalse(p.body.contains("15"))
        assertFalse(p.body.contains("<b>"))
    }

    @Test
    fun parseEntry_altRootFromArabicParens() {
        val p = HwPolicy.parseEntry(
            "قَامَ",
            "قَامَ (قوم) qāma to stand <b>I</b>",
        )
        assertEquals("قوم", p.altRoot)
        assertEquals("qāma", p.romanization)
    }

    @Test
    fun parseEntry_empty() {
        val p = HwPolicy.parseEntry("كلمة", "")
        assertEquals("empty", p.kind)
    }

    @Test
    fun rebuildRoots_nearestPrecedingRoot() {
        val rows = listOf(
            HwPolicy.RawRow(1, "كتب", "", 1, null),   // корень (3 буквы, is_root=1)
            HwPolicy.RawRow(2, "مكتبة", "", 0, null),  // производное от كتب
            HwPolicy.RawRow(3, "درس", "", 1, null),    // второй корень
            HwPolicy.RawRow(4, "مدارس", "", 0, null),   // производное от درس
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
