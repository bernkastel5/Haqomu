package com.bernkastel5.haqomu.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Разметка → текст: абзацы, сущности, отброшенные script/style. Без Android. */
class HtmlTextTest {

    @Test
    fun paragraphsBecomeLines() {
        val html = "<html><body><p>Первый</p><p>Второй</p></body></html>"
        assertEquals("Первый\nВторой", HtmlText.fromHtml(html))
    }

    @Test
    fun brIsALineBreak() {
        assertEquals("а\nб", HtmlText.fromHtml("а<br/>б"))
    }

    @Test
    fun entitiesAreDecoded() {
        assertEquals("a & b < c", HtmlText.fromHtml("a &amp; b &lt; c"))
        assertEquals("\u064A", HtmlText.fromHtml("&#1610;"))
        assertEquals("\u0628", HtmlText.fromHtml("&#x628;"))
    }

    @Test
    fun unknownEntityIsKept() {
        assertEquals("&nope;", HtmlText.fromHtml("&nope;"))
    }

    @Test
    fun spacesCollapse() {
        assertEquals("a b", HtmlText.fromHtml("a    \n   b"))
    }

    @Test
    fun emptyParagraphsCollapse() {
        assertEquals("a\nb", HtmlText.fromHtml("<p>a</p><p></p><p> </p><p>b</p>"))
    }

    @Test
    fun scriptAndStyleAreDropped() {
        val html = "<style>p{color:red}</style><p>текст</p><script>var x=1;</script>"
        assertEquals("текст", HtmlText.fromHtml(html))
    }

    @Test
    fun arabicTextWithHarakatIsKeptAsIs() {
        // читалка не нормализует текст книги: слово должно выглядеть как напечатано
        val arabic = "\u0630\u064E\u0647\u064E\u0628\u064E \u0627\u0644\u0637\u0641\u0644\u064F"
        assertEquals(arabic, HtmlText.fromHtml("<p>$arabic</p>"))
    }

    @Test
    fun singleLineModeJoinsParagraphs() {
        assertEquals("один два", HtmlText.fromHtml("<p>один</p><p>два</p>", keepParagraphs = false))
    }

    @Test
    fun tagNameIgnoresAttributesAndClosingSlash() {
        assertEquals("p", HtmlText.tagName("p class=\"x\""))
        assertEquals("p", HtmlText.tagName("/p"))
        assertEquals("div", HtmlText.tagName("div"))
        assertEquals("", HtmlText.tagName("!-- comment --"))
    }

    @Test
    fun handlesNullAndEmpty() {
        assertEquals("", HtmlText.fromHtml(null))
        assertEquals("", HtmlText.fromHtml(""))
    }
}
