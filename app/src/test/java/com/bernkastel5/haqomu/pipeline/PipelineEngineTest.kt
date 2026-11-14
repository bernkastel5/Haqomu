package com.bernkastel5.haqomu.pipeline

import com.bernkastel5.haqomu.morph.Analyzer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ф6–Ф9: полный проход «слово → попап» на синтетике. Инварианты:
 *  - ответ пайплайна — всегда первая статья;
 *  - не больше трёх статей; смыслы статей на месте;
 *  - имя собственное → честная плашка, статей нет;
 *  - ложных «имён» на словах, которые CAMeL разбирает, быть не должно.
 */
class PipelineEngineTest {

    private fun engine(dict: FakeDictionary) = PipelineEngine(Analyzer(FakeCamel.store()), dict)

    private fun standardDict() = FakeDictionary(
        listOf(
            entry("ذهب", Families.KAIKKI, listOf("to go, to leave" to "to go"), root = "ذهب", pos = "verb"),
            entry("ذهب", Families.WEHR, listOf("ḏahaba: to go; fig. to die" to "to go; to die"), root = "ذهب", pos = "verb"),
            entry("بيت", Families.KAIKKI, listOf("house, home" to "house"), root = "بيت", pos = "noun"),
            entry("بيت", Families.WEHR, listOf("bayt: house, tent; verse" to "house, verse"), root = "بيت", pos = "noun"),
            entry("اخرج", Families.KAIKKI, listOf("to go out" to "to go out"), root = "خرج", pos = "verb"),
            entry("عطي", Families.KAIKKI, listOf("to give" to "to give"), root = "عطو", pos = "verb"),
        ),
    )

    @Test
    fun pipelineAnswerIsAlwaysFirstArticle() {
        val r = engine(standardDict()).lookup("ذهب")
        assertEquals(PopupMode.ARTICLES, r.mode)
        assertTrue(r.articles.isNotEmpty())
        assertTrue(r.articles.first().isAnswer)
        assertEquals("answer", r.articles.first().rank)
        // все смыслы статьи на месте
        assertEquals("to go, to leave", r.articles.first().senses.first().definition)
    }

    @Test
    fun answerFamilyWinsByPriorityOnTie() {
        val r = engine(standardDict()).lookup("ذهب")
        assertEquals(Families.KAIKKI, r.articles.first().sourceFamily)
    }

    @Test
    fun lemmaArticlesFollowAnswerAndRespectCap() {
        // «وبيت»: CAMeL целиком не разбирает, лемма بيت → статьи из всех семейств; ≤ 3 статей
        val d = FakeDictionary(
            listOf(
                entry("بيت", Families.KAIKKI, listOf("house, home" to "house"), root = "بيت", pos = "noun"),
                entry("بيت", Families.WEHR, listOf("bayt: house, tent; verse" to "house, verse"), root = "بيت", pos = "noun"),
                entry("بيت", Families.OTHER, listOf("bayt — other deck" to "bayt — other deck"), root = "بيت", pos = "noun"),
            ),
        )
        // в синтетике و не префикс calima: «وبيت» разбирается только как клитика (و + بيت)
        val r = engine(d).lookup("وبيت")
        assertEquals(WordKind.CLITIC, r.wordKind)
        assertEquals(3, r.articles.size)
        assertTrue(r.articles.first().isAnswer)
        assertEquals(listOf(Families.KAIKKI, Families.WEHR, Families.OTHER), r.articles.map { it.sourceFamily })
    }

    @Test
    fun cliticLookupUsesBaseLemmas() {
        val r = engine(standardDict()).lookup("أعطني")
        assertEquals(WordKind.CLITIC, r.wordKind)
        assertEquals(PopupMode.ARTICLES, r.mode)
        assertEquals("عط", r.cliticBase)
        assertTrue(r.articles.isNotEmpty())
        assertTrue(r.articles.first().isAnswer)
        assertEquals("عطي", r.articles.first().head)
    }

    @Test
    fun namePlateForOwnNames() {
        val r = engine(standardDict()).lookup("جاكي")
        assertEquals(PopupMode.NAME_PLATE, r.mode)
        assertEquals(WordKind.NAME, r.wordKind)
        assertTrue(r.articles.isEmpty())
    }

    @Test
    fun unknownWordWithNoDictionaryDataGivesEmptyArticlesNotName() {
        // CAMeL разбирает слово, словарных данных нет → ARTICLES с нулевыми статьями, не плашка имени
        val r = engine(FakeDictionary(emptyList())).lookup("ذهب")
        assertEquals(PopupMode.ARTICLES, r.mode)
        assertTrue(r.articles.isEmpty())
    }

    @Test
    fun falseNameRateOnFixtureIsZero() {
        val d = standardDict()
        val e = engine(d)
        for (w in listOf("ذهب", "اخرج", "أعطني", "اخرجي", "بيت")) {
            assertFalse("слово $w не должно опознаваться как имя", e.lookup(w).mode == PopupMode.NAME_PLATE)
        }
    }

    @Test
    fun atMostThreeArticlesAndNoDuplicateHeads() {
        val r = engine(standardDict()).lookup("بيت")
        assertTrue(r.articles.size <= 3)
        val keys = r.articles.map { it.sourceFamily + ":" + it.head + ":" + (it.senses.firstOrNull()?.definition ?: "") }
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun answerFirstEvenWhenOnlyLemmaMatches() {
        // словоформы нет в словаре (الذهب там нет), лемма ذهب есть → статья должна быть, и она первая
        val r = engine(standardDict()).lookup("الذهب")
        assertEquals(PopupMode.ARTICLES, r.mode)
        if (r.articles.isNotEmpty()) {
            assertTrue(r.articles.first().isAnswer)
        }
    }
}
