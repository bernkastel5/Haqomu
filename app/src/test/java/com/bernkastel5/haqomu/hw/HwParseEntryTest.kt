package com.bernkastel5.haqomu.hw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HwParseEntryTest {

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
    fun parseEntry_arabicParensStayHead() {
        // эталон: is_arabic_token("(قوم)") == true — скобка съедается заголовком,
        // ветка «арабская скобка после заголовка» на одиночном токене не срабатывает
        val p = HwPolicy.parseEntry(
            "قَامَ",
            "قَامَ (قوم) qāma to stand <b>I</b>",
        )
        assertNull(p.altRoot)
        assertEquals("qāma", p.romanization)
    }

    @Test
    fun parseEntry_empty() {
        val p = HwPolicy.parseEntry("كلمة", "")
        assertEquals("empty", p.kind)
    }
}
