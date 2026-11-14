package com.bernkastel5.haqomu.morph

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Синтетическая база, повторяющая calima: голый стебель, аффиксы с пустыми
 * feats ('-'), таблицы compat. Прогоны соответствуют tools/build_camel_pack.py --selftest.
 */
class AnalyzerTest {

    private fun fakeStore(): CamelStore {
        val stems = mapOf(
            "ذهب" to listOf(StemRow("VERB_STEM", "VERB", "ذهب", -0.50, "ذ ه ب")),
            "بيت" to listOf(StemRow("NOUN_STEM", "NOUN", "بيت", -0.80, "ب ي ت")),
            "بيوت" to listOf(StemRow("NOUN_STEM", "NOUN", "بيت", -0.90, "ب ي ت")),
        )
        val empty = mapOf("pos" to "-", "lex" to "-")  # calima-афиксы не несут полезного lex_logprob — стебель сохраняет свой
        val prefixes = mapOf(
            "و" to listOf(AffixRow("CONJ", mapOf("pos" to "CC"))),
            "ال" to listOf(AffixRow("DET", mapOf("pos" to "DET"))),
            "" to listOf(AffixRow("NOPREF", empty)),
        )
        val suffixes = mapOf(
            "" to listOf(AffixRow("NSUFF", empty)),
            "ون" to listOf(AffixRow("M_PL_NOM", empty)),
        )
        val compat = setOf(
            Triple('a', "CONJ", "VERB_STEM"), Triple('a', "CONJ", "NOUN_STEM"), Triple('a', "DET", "NOUN_STEM"),
            Triple('a', "NOPREF", "VERB_STEM"), Triple('a', "NOPREF", "NOUN_STEM"),
            Triple('b', "VERB_STEM", "NSUFF"), Triple('b', "NOUN_STEM", "M_PL_NOM"), Triple('b', "NOUN_STEM", "NSUFF"),
            Triple('c', "CONJ", "NSUFF"), Triple('c', "CONJ", "M_PL_NOM"), Triple('c', "DET", "M_PL_NOM"), Triple('c', "DET", "NSUFF"),
            Triple('c', "NOPREF", "NSUFF"), Triple('c', "NOPREF", "M_PL_NOM"),
        )
        return object : CamelStore {
            override fun stems(stem: String) = stems[stem] ?: emptyList()
            override fun prefixes(form: String) = prefixes[form] ?: emptyList()
            override fun suffixes(form: String) = suffixes[form] ?: emptyList()
            override fun compatAllows(kind: Char, a: String, b: String) = compat.contains(Triple(kind, a, b))
        }
    }

    @Test
    fun bareVerbAnalyzes() {
        val analyses = Analyzer(fakeStore()).analyze("ذهب")
        assertEquals(1, analyses.size)
        assertEquals("VERB", analyses[0].pos)
        assertEquals("ذهب", analyses[0].lex)
        assertEquals(-0.50, analyses[0].lexLogprob, 1e-9)
    }

    @Test
    fun cliticPrefixPlusStem() {
        val lemmas = Analyzer(fakeStore()).lemmas("وذهب")
        assertEquals(listOf("ذهب"), lemmas)
    }

    @Test
    fun definitePluralNoun() {
        // ال + بيوت (плюрал в стеблях calima) — лемма بيت
        val lemmas = Analyzer(fakeStore()).lemmas("البيوت")
        assertEquals(listOf("بيت"), lemmas)
    }

    @Test
    fun unknownWordGivesNothing() {
        val analyses = Analyzer(fakeStore()).analyze("قفزح")
        assertTrue(analyses.isEmpty())
    }

    @Test
    fun normalizeStripsHarakatAndUnifiesAlef() {
        assertEquals("ذهب", ArabicNormalize.un("ذَهَبَ"))
        assertEquals("اكل", ArabicNormalize.un("أكل"))
        assertEquals("اهدي", ArabicNormalize.un("إهدى"))
    }
}
