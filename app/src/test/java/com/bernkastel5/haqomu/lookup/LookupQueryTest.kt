package com.bernkastel5.haqomu.lookup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Ф1: извлечение запроса из выделенного текста. */
class LookupQueryTest {

    @Test
    fun plainWord() {
        assertEquals("ذهب", LookupQuery.extract("ذهب"))
        assertEquals("ذهب", LookupQuery.extract("  ذهب  "))
    }

    @Test
    fun firstArabicRunWins() {
        assertEquals("الاستقلال", LookupQuery.extract("الاستقلال."))
        assertEquals("الطفل", LookupQuery.extract("الطفل، عاد إلى البيت"))
    }

    @Test
    fun harakatAreKept() {
        assertEquals("ذَهَبَ", LookupQuery.extract("ذَهَبَ"))
    }

    @Test
    fun tatweelIsKept() {
        assertEquals("ايـمان", LookupQuery.extract("ايـمان"))
    }

    @Test
    fun latinDigitsAndPunctuationAreSeparators() {
        assertEquals("ذهب", LookupQuery.extract("ذهب123"))
        assertEquals("ذهـب", LookupQuery.extract("(ذهـب)؟")) // татвиль внутри слова сохраняется
        assertEquals("ذهب", LookupQuery.extract("café ذهب tea"))
    }

    @Test
    fun latinWithArabicMixedSentence() {
        assertEquals("رأيته", LookupQuery.extract("I saw رأيته yesterday"))
    }

    @Test
    fun noArabicGivesNull() {
        assertNull(LookupQuery.extract(""))
        assertNull(LookupQuery.extract("hello world"))
        assertNull(LookupQuery.extract(null))
        assertNull(LookupQuery.extract("123 ... !!!"))
    }

    @Test
    fun multiWordSelectionTakesFirstWord() {
        assertEquals("كتب", LookupQuery.extract("كتب الطالب الدرس"))
    }
}
