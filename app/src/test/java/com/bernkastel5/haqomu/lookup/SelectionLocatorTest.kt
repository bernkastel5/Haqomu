package com.bernkastel5.haqomu.lookup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Ф3: восстановление выделения, когда система дала не всё. Проверяем три пути —
 * смещения, поиск выделенного текста в тексте узла, работа по одному фрагменту —
 * и честный отказ на поле пароля.
 */
class SelectionLocatorTest {

    private val full = "خرج الطفل من البيت قبل الغروب."

    @Test
    fun usesSelectionOffsetsWhenPresent() {
        val start = full.indexOf("الطفل")
        val info = SelectionLocator.locate(full, null, start, start + 5)
        assertEquals("الطفل", info?.word)
        assertEquals(full, info?.sentence)
    }

    @Test
    fun findsSelectionByTextWhenOffsetsAreMissing() {
        // WebView: смещений нет, в событии только выделенный фрагмент
        val info = SelectionLocator.locate(full, "البيت", -1, -1)
        assertEquals("البيت", info?.word)
        assertEquals(full, info?.sentence)
    }

    @Test
    fun findsSelectionWhenEventTextHasCollapsedSpaces() {
        val text = "قال  الرجل   كلمة  غريبة"
        val info = SelectionLocator.locate(text, "الرجل كلمة", -1, -1)
        assertNotNull(info)
        assertEquals("الرجل", info!!.word)
        // предложение всегда со схлопнутыми пробелами (SentenceExtractor)
        assertEquals("قال الرجل كلمة غريبة", info.sentence)
    }

    @Test
    fun worksWithoutNodeTextAtAll() {
        // приложение не отдаёт текст узла: контекста нет, но слово обслуживается
        val info = SelectionLocator.locate(null, "المدرسة", -1, -1)
        assertNotNull(info)
        assertEquals("المدرسة", info!!.word)
        assertEquals("المدرسة", info.sentence)
    }

    @Test
    fun brokenOffsetsFallBackToSearch() {
        // смещения вне текста — не доверяем, ищем по фрагменту
        val info = SelectionLocator.locate(full, "الغروب", 999, 1005)
        assertEquals("الغروب", info?.word)
    }

    @Test
    fun passwordFieldIsIgnored() {
        assertNull(SelectionLocator.locate(full, "البيت", 10, 15, isPassword = true))
    }

    @Test
    fun noTextAtAllIsIgnored() {
        assertNull(SelectionLocator.locate(null, null, -1, -1))
        assertNull(SelectionLocator.locate("   ", "", -1, -1))
    }

    @Test
    fun latinOnlySelectionIsIgnored() {
        assertNull(SelectionLocator.locate("hello world", "hello", -1, -1))
    }

    @Test
    fun indexOfSelectionFindsSkeletonMatch() {
        assertEquals(0, SelectionLocator.indexOfSelection("ذهب الطفل", "ذهب"))
        assertEquals(-1, SelectionLocator.indexOfSelection("ذهب الطفل", "كتاب"))
        assertEquals(0, SelectionLocator.indexOfSelection("ذ ه ب", "ذهب"))
    }

    @Test
    fun emptySelectionArgumentGivesNoPosition() {
        assertEquals(-1, SelectionLocator.indexOfSelection("ذهب", "   "))
    }

    @Test
    fun captureStillReportsSentenceAroundTheWord() {
        val info = SelectionLocator.locate(full, "البيت", -1, -1)
        // предложение целиком: границей служит точка, а не запятая
        assertEquals("خرج الطفل من البيت قبل الغروب.", info?.sentence)
    }
}
