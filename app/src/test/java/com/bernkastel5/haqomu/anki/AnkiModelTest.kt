package com.bernkastel5.haqomu.anki

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ф11: чистые функции набора Haqomu (совпадение с эталоном Anki). */
class AnkiModelTest {

    @Test
    fun fieldSeparatorIsUnitSeparator() {
        assertEquals("\u001f", AnkiModel.FIELD_SEPARATOR.toString())
        assertEquals("a\u001fb", AnkiModel.joinFields(arrayOf("a", "b")))
    }

    @Test
    fun guidMatchesAnkiAlgorithm() {
        // эталон: base91(sha256(join("__", fields))[:8]) как в Anki/genanki
        assertEquals("ki1h%eaXn^", AnkiModel.guidFor("Haqomu"))
        assertEquals("s%sEmi|5c#", AnkiModel.guidFor("a", "b", "c", "d", "e", "f", "g"))
        assertEquals("B)fY;<Ezlz", AnkiModel.guidFor("ذَهَبَ"))
    }

    @Test
    fun csumMatchesAnkiAlgorithm() {
        // первые 4 байта sha1 как беззнаковое hex
        assertEquals(3918496760L, AnkiModel.csumFor("Haqomu"))
        assertEquals(3661210606L, AnkiModel.csumFor(""))
    }

    @Test
    fun fieldsArrayOrderMatchesModel() {
        val f = AnkiModel.fieldsArray("e", "t", "m", "s", "r", "d", "n")
        assertEquals(listOf("e", "t", "m", "s", "r", "d", "n"), f.toList())
        assertEquals(AnkiModel.FIELDS, listOf("Expression", "Tashkeel", "Meaning", "Sentence", "Root", "Dictionary", "Notes"))
    }

    @Test
    fun modelAndDeckJsonContainIdsAndFields() {
        val m = AnkiModel.modelJson(1763078400L)
        assertTrue(m.contains("\"id\":\"${AnkiModel.MODEL_ID}\""))
        assertTrue(m.contains(AnkiModel.MODEL_NAME))
        for (field in AnkiModel.FIELDS) {
            assertTrue(m.contains(field))
        }
        val d = AnkiModel.deckJson()
        assertTrue(d.contains("\"id\":${AnkiModel.DECK_ID}"))
        assertTrue(d.contains(AnkiModel.DECK_NAME))
    }

    @Test
    fun fixedIdsAreStable() {
        // повторный импорт набора не должен дублировать колоду и тип заметки
        assertEquals(1763078400000L, AnkiModel.MODEL_ID)
        assertEquals(1763078400001L, AnkiModel.DECK_ID)
        assertEquals("Haqomu Arabic", AnkiModel.MODEL_NAME)
        assertEquals("Haqomu", AnkiModel.DECK_NAME)
    }
}
