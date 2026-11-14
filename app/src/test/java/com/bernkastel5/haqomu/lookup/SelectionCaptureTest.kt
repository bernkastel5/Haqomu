package com.bernkastel5.haqomu.lookup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Ф2/Ф3: разбор выделения, пришедшего от сервиса доступности. Сервис отдаёт слово
 * и предложение; выделение без арабского текста он не обслуживает (null), чтобы
 * не открывать попап на латинице и цифрах.
 */
class SelectionCaptureTest {

    private val sentence = "خرج الطفل من البيت قبل الغروب."

    private fun capture(selection: String, full: String = sentence): SelectionInfo? {
        val start = full.indexOf(selection)
        return SelectionCapture.capture(full, start, start + selection.length)
    }

    @Test
    fun wordAndSentenceAreExtracted() {
        val info = capture("الطفل")
        assertNotNull(info)
        assertEquals("الطفل", info!!.word)
        assertEquals("خرج الطفل من البيت قبل الغروب.", info.sentence)
    }

    @Test
    fun selectionWithPunctuationYieldsTheWord() {
        val info = capture("البيت قبل")
        assertEquals("البيت", info?.word)
        assertEquals("خرج الطفل من البيت قبل الغروب.", info?.sentence)
    }

    @Test
    fun harakatAreKeptInWord() {
        val s = "ذَهَبَ الطالب إلى المدرسة."
        val start = s.indexOf("ذَهَبَ")
        val info = SelectionCapture.capture(s, start, start + 6)
        assertEquals("ذَهَبَ", info?.word)
    }

    @Test
    fun latinOnlySelectionIsIgnored() {
        val s = "hello ذهب world"
        assertNull(SelectionCapture.capture(s, 0, 5))
    }

    @Test
    fun emptyOrBrokenSelectionIsIgnored() {
        assertNull(SelectionCapture.capture(null, 0, 3))
        assertNull(SelectionCapture.capture("", 0, 0))
        assertNull(SelectionCapture.capture(sentence, 5, 5))
        assertNull(SelectionCapture.capture(sentence, 10, 2))
    }

    @Test
    fun indicesOutsideTextAreClamped() {
        val info = SelectionCapture.capture("ذهب", -4, 99)
        assertEquals("ذهب", info?.word)
        assertEquals("ذهب", info?.sentence)
    }
}
