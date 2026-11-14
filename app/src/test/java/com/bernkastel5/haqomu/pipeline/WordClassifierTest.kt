package com.bernkastel5.haqomu.pipeline

import com.bernkastel5.haqomu.morph.Analyzer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ф5: классификатор. Синтетика FakeCamel: ذهب/اخرج/عط — глаголы, بيت — существительное;
 * полный разбор возможен только для голых стеблей, клитики разбираются по основе.
 */
class WordClassifierTest {

    private fun classifier(dict: FakeDictionary): WordClassifier =
        WordClassifier(Analyzer(FakeCamel.store()), dict)

    private fun dict(vararg entries: DictionaryEntry) = FakeDictionary(entries.toList())

    @Test
    fun camelParsedWordIsWord() {
        val c = classifier(dict())
        val r = c.classify("ذهب")
        assertEquals(WordKind.WORD, r.kind)
        assertTrue(r.lemmas.contains("ذهب"))
    }

    @Test
    fun cliticVerbWithObjectSuffix() {
        // اعطني (в написании с همزة القطع): CAMeL её целиком не разбирает,
        // основа عط разбирается, статья есть → клитика, не «имя»
        val d = dict(entry("عط", Families.KAIKKI, listOf("to give" to "to give"), root = "عطو", pos = "verb"))
        val r = classifier(d).classify("أعطني")
        assertEquals(WordKind.CLITIC, r.kind)
        assertEquals("عط", r.base)
        assertEquals("ني", r.suffix)
        assertTrue(r.lemmas.isNotEmpty())
    }

    @Test
    fun imperativeWithVerbSuffix() {
        // اخرجي = основа اخرج (глагол) + глагольный ير ي; пустого префикса достаточно
        val d = dict(entry("اخرج", Families.WEHR, listOf("to go out" to "to go out"), root = "خرج", pos = "verb"))
        val r = classifier(d).classify("اخرجي")
        assertEquals(WordKind.CLITIC, r.kind)
        assertEquals("اخرج", r.base)
        assertEquals("ي", r.suffix)
    }

    @Test
    fun verbSuffixRejectsNounBase() {
        // بيتت: основа بيت — существительное, глагольное окончание ت не подходит → NAME
        val d = dict(entry("بيت", Families.KAIKKI, listOf("house" to "house"), root = "بيت", pos = "noun"))
        val r = classifier(d).classify("بيتت")
        assertEquals(WordKind.NAME, r.kind)
    }

    @Test
    fun unknownGarbageIsName() {
        val d = dict()
        assertEquals(WordKind.NAME, classifier(d).classify("قفزح").kind)
    }

    @Test
    fun serviceLikeWordWithoutStemIsName() {
        // В синтетике стебля «هو» нет — POS у основы нет, разложение не находит;
        // служебная leniency применяется только когда POS известен.
        val d = dict()
        assertEquals(WordKind.NAME, classifier(d).classify("هاهو").kind)
    }

    @Test
    fun noDictionaryHeadIsName() {
        // Основа разбирается, но статьи в словарных паках нет и POS не служебный → NAME
        val d = dict()
        val r = classifier(d).classify("اخرجي") // основа разбирается, головы в словаре нет
        assertEquals(WordKind.NAME, r.kind)
    }

    @Test
    fun falseNamesOnFixtureAreZero() {
        // Регрессия TZ: ложных «имён собственных» на фикстуре словарных слов быть не должно
        val d = dict(
            entry("عط", Families.KAIKKI, listOf("to give" to "to give"), root = "عطو", pos = "verb"),
            entry("اخرج", Families.KAIKKI, listOf("to go out" to "to go out"), root = "خرج", pos = "verb"),
            entry("بيت", Families.KAIKKI, listOf("house" to "house"), root = "بيت", pos = "noun"),
        )
        val c = classifier(d)
        for (w in listOf("ذهب", "اخرج", "أعطني", "اخرجي", "بيت")) {
            assertTrue("слово $w не должно быть NAME", c.classify(w).kind != WordKind.NAME)
        }
    }
}
