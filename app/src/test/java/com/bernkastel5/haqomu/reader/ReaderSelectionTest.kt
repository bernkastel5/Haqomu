package com.bernkastel5.haqomu.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Тап по слову в читалке: слово и предложение для попапа. Логика та же, что у
 * сервиса доступности, поэтому контекст в карточке одинаков независимо от способа
 * выделения.
 */
class ReaderSelectionTest {

    private val text = "خرج الطفل من البيت قبل الغروب. ثم عاد إلى المدرسة."

    @Test
    fun tapOnWordGivesWordAndSentence() {
        val at = ReaderSelection.select(text, text.indexOf("البيت"))
        assertEquals("البيت", at?.word)
        assertEquals("خرج الطفل من البيت قبل الغروب.", at?.sentence)
    }

    @Test
    fun tapOnSpaceFindsNearestWord() {
        // палец почти всегда попадает между словами
        val at = ReaderSelection.select(text, text.indexOf(" من ") + 1)
        assertTrue(at != null)
        assertTrue(at!!.word in setOf("من", "الطفل", "البيت"))
    }

    @Test
    fun secondSentenceIsFoundIndependently() {
        val at = ReaderSelection.select(text, text.indexOf("المدرسة"))
        assertEquals("المدرسة", at?.word)
        assertEquals("ثم عاد إلى المدرسة.", at?.sentence)
    }

    @Test
    fun wordWithHarakatIsTakenWhole() {
        val s = "ذَهَبَ الطالبُ إلى المدرسة."
        val at = ReaderSelection.select(s, s.indexOf("الطالب"))
        assertEquals("الطالبُ", at?.word)
    }

    @Test
    fun latinOnlyPlaceGivesNothing() {
        assertNull(ReaderSelection.select("hello world", 2))
    }

    @Test
    fun indicesOutsideTextAreClamped() {
        val at = ReaderSelection.select(text, 10_000)
        assertTrue(at != null)
        assertEquals("المدرسة", at!!.word)
    }

    @Test
    fun emptyTextGivesNothing() {
        assertNull(ReaderSelection.select("", 0))
        assertNull(ReaderSelection.select("   ", 1))
    }

    @Test
    fun boundariesCoverTheWholeForm() {
        val at = ReaderSelection.select(text, text.indexOf("الطفل"))!!
        assertEquals("الطفل", text.substring(at.start, at.end))
    }

    @Test
    fun wordCharsIncludeHarakatAndTatweel() {
        assertTrue(ReaderSelection.isWordChar('\u064E')) // фатха
        assertTrue(ReaderSelection.isWordChar('\u0640')) // татвиль
        assertTrue(ReaderSelection.isWordChar('\u0628'))
        assertTrue(!ReaderSelection.isWordChar(' '))
        assertTrue(!ReaderSelection.isWordChar(','))
        assertTrue(!ReaderSelection.isWordChar('a'))
    }

    @Test
    fun longArabicRunIsNotSplitOnHarakat() {
        val s = "كِتَابٌ جَدِيدٌ"
        val at = ReaderSelection.select(s, 1)
        assertEquals("كِتَابٌ", at?.word)
    }
}
