package com.bernkastel5.haqomu.lookup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ф3: предложение вокруг выделения (логика без Android, TZ §8 — только в памяти). */
class SentenceExtractorTest {

    private val text = "خرج الطفل من البيت. عادت الأم إلى المطبخ، ثم جلست."

    private fun aroundSelection(selection: String): String {
        val start = text.indexOf(selection)
        return SentenceExtractor.around(text, start, start + selection.length)
    }

    @Test
    fun takesSentenceWithItsPeriod() {
        assertEquals("خرج الطفل من البيت.", aroundSelection("الطفل"))
    }

    @Test
    fun commaIsNotABoundary() {
        assertEquals("عادت الأم إلى المطبخ، ثم جلست.", aroundSelection("المطبخ"))
    }

    @Test
    fun arabicQuestionMarkEndsSentence() {
        val s = "أين ذهب الطفل؟ قال إنه في المدرسة."
        val start = s.indexOf("الطفل")
        assertEquals("أين ذهب الطفل؟", SentenceExtractor.around(s, start, start + "الطفل".length))
    }

    @Test
    fun lineBreakEndsSentenceWithoutItself() {
        val s = "السطر الأول فيه كلمة\nالسطر الثاني"
        val start = s.indexOf("كلمة")
        assertEquals("السطر الأول فيه كلمة", SentenceExtractor.around(s, start, start + 4))
    }

    @Test
    fun whitespaceIsCollapsed() {
        val s = "خرج   الطفل  من   البيت."
        val start = s.indexOf("الطفل")
        assertEquals("خرج الطفل من البيت.", SentenceExtractor.around(s, start, start + 5))
    }

    @Test
    fun emptyAndOutOfRangeAreSafe() {
        assertEquals("", SentenceExtractor.around("", 0, 0))
        assertEquals("", SentenceExtractor.around("   ", 0, 1))
        assertEquals("ذهب", SentenceExtractor.around("ذهب", -5, 999))
        assertEquals("ذهب", SentenceExtractor.around("ذهب", 2, 2))
    }

    @Test
    fun longTextIsTrimmedAroundSelectionWithEllipsis() {
        val filler = "وكان في الطريق حديث طويل عن السفر والمدن ".repeat(12)
        val s = filler + "الكلمة هنا" + filler
        val start = s.indexOf("الكلمة")
        val piece = SentenceExtractor.around(s, start, start + 6)
        assertTrue("длина ${piece.length}", piece.length <= SentenceExtractor.MAX_LENGTH + 2)
        assertTrue(piece.contains("الكلمة"))
        assertTrue(piece.startsWith("…"))
        assertTrue(piece.endsWith("…"))
    }

    @Test
    fun shortSentenceIsNotMarkedAsTrimmed() {
        val piece = aroundSelection("الطفل")
        assertTrue(!piece.startsWith("…") && !piece.endsWith("…"))
    }
}
