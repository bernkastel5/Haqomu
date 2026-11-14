package com.bernkastel5.haqomu.anki

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ф11: накопление и экспорт (чистые части). */
class PendingCardTest {

    @Test
    fun isValidRequiresExpressionAndMeaning() {
        assertTrue(PendingCard.isValid("ذهب", "to go"))
        assertEquals(false, PendingCard.isValid("", "x"))
        assertEquals(false, PendingCard.isValid("x", ""))
        assertEquals(false, PendingCard.isValid("  ", "  "))
    }

    @Test
    fun toFieldsOrderMatchesAnkiModel() {
        val c = PendingCard(
            expression = "عَدُوّ",
            tashkeel = "عَدُوّ",
            meaning = "enemy",
            sentence = "",
            root = "ع د و",
            dictionary = "Kaikki",
            notes = "note",
        )
        assertEquals(
            listOf("عَدُوّ", "عَدُوّ", "enemy", "", "ع د و", "Kaikki", "note"),
            c.toFields().toList(),
        )
    }
}

class AnkiConnectClientTest {

    @Test
    fun versionBodyIsJsonRpc() {
        assertEquals("""{"action":"version","version":6}""", AnkiConnectClient.versionBody())
    }

    @Test
    fun addNoteBodyContainsModelAndDeck() {
        val body = AnkiConnectClient.addNoteBody(
            deckName = "Haqomu",
            card = PendingCard(expression = "ذَهَبَ", tashkeel = "ذَهَبَ", meaning = "to go"),
        )
        assertTrue(body.contains("\"modelName\":\"Haqomu Arabic\""))
        assertTrue(body.contains("\"deckName\":\"Haqomu\""))
        assertTrue(body.contains("\"allowDuplicate\":false"))
        assertTrue(body.contains("ذَهَبَ"))
    }

    @Test
    fun addNoteBodyEscapesJson() {
        val body = AnkiConnectClient.addNoteBody(
            deckName = "Haqomu",
            card = PendingCard(expression = "say \"hi\"", tashkeel = "x", meaning = "line\nbreak"),
        )
        assertTrue(body.contains("say \\\"hi\\\""))
        assertTrue(body.contains("line\\nbreak"))
    }
}

class JsonTest {

    @Test
    fun escapeCoversQuotesBackslashesAndControl() {
        assertEquals("\"\\\"a\\\"\"", Json.quoted("\"a\""))
        assertEquals("a\\\\b", Json.escape("a\\b"))
        assertEquals("a\\nb", Json.escape("a\nb"))
        assertEquals("\\u0001", Json.escape("\u0001"))
    }
}
