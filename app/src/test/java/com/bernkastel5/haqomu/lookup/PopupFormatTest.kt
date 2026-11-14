package com.bernkastel5.haqomu.lookup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Ф9: форматирование попапа. */
class PopupFormatTest {

    @Test
    fun rootLettersSpreadsSpacelessRoot() {
        assertEquals("ذ ه ب", PopupFormat.rootLetters("ذهب"))
        assertEquals("ك ت ب", PopupFormat.rootLetters("كتب"))
    }

    @Test
    fun rootLettersReturnsNullForGarbage() {
        assertNull(PopupFormat.rootLetters(null))
        assertNull(PopupFormat.rootLetters(""))
        assertNull(PopupFormat.rootLetters("abc"))
        assertNull(PopupFormat.rootLetters("123"))
    }

    @Test
    fun arabicIndicNumberUsesArabicIndicZero() {
        assertEquals("١.", PopupFormat.arabicIndicNumber(1))
        assertEquals("٩.", PopupFormat.arabicIndicNumber(9))
        assertEquals("١٢.", PopupFormat.arabicIndicNumber(12))
    }

    @Test
    fun arabicIndicNumberRejectsNegative() {
        var thrown = false
        try {
            PopupFormat.arabicIndicNumber(-1)
        } catch (expected: IllegalArgumentException) {
            thrown = true
        }
        org.junit.Assert.assertTrue(thrown)
    }
}
