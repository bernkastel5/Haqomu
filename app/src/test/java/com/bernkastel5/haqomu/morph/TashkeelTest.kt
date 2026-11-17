package com.bernkastel5.haqomu.morph

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Огласовка поверх синтетической базы: логика сборки (префикс + стебель + суффикс)
 * и честный отказ, когда база формы не знает или огласовка выходит противоречивой.
 */
class TashkeelTest {

    // بَيْت и ـهُ: ожидаемые куски записаны константами, чтобы тест читался.
    private val B_YAYT = "\u0628\u064E\u064A\u0652\u062A"
    private val PRON_HU_DAMMA = "\u0647\u064F"
    private val BARE_BAYT = "\u0628\u064A\u062A"

    // Огласованный стебель: ذَهَبَ (глагол «уходить»), بَيْت (дом).
    private fun store(): CamelStore {
        val stems = mapOf(
            "\u0630\u0647\u0628" to listOf(
                StemRow("VERB_STEM", "VERB", "\u0630\u0647\u0628", -0.50, "\u0630 \u0647 \u0628", "\u0630\u064E\u0647\u064E\u0628\u064E"),
            ),
            "\u0628\u064A\u062A" to listOf(
                StemRow("NOUN_STEM", "NOUN", "\u0628\u064A\u062A", -0.80, "\u0628 \u064A \u062A", "\u0628\u064E\u064A\u0652\u062A"),
            ),
        )
        val empty = mapOf("pos" to "-", "lex" to "-")
        // Как в calima: ключ аффикса без огласовки, огласованный вид — в feats diac.
        val prefixes = mapOf(
            "" to listOf(AffixRow("NOPREF", empty)),
            "\u0627\u0644" to listOf(AffixRow("DET", mapOf("pos" to "DET", "diac" to "\u0627\u0644\u0652"))),
            "\u0628" to listOf(AffixRow("PREP", mapOf("pos" to "PREP", "diac" to "\u0628\u0650"))),
        )
        val suffixes = mapOf(
            "" to listOf(AffixRow("NSUFF", empty)),
            "\u0647" to listOf(AffixRow("POSS_PRON_3MS", mapOf("pos" to "PRON", "diac" to "\u0647\u064F"))),
        )
        val compat = setOf(
            Triple('a', "NOPREF", "VERB_STEM"), Triple('a', "NOPREF", "NOUN_STEM"),
            Triple('a', "DET", "NOUN_STEM"), Triple('a', "PREP", "NOUN_STEM"),
            Triple('b', "VERB_STEM", "NSUFF"), Triple('b', "NOUN_STEM", "NSUFF"),
            Triple('b', "NOUN_STEM", "POSS_PRON_3MS"),
            Triple('c', "NOPREF", "NSUFF"), Triple('c', "DET", "NSUFF"),
            Triple('c', "PREP", "NSUFF"), Triple('c', "NOPREF", "POSS_PRON_3MS"),
            Triple('c', "DET", "POSS_PRON_3MS"), Triple('c', "PREP", "POSS_PRON_3MS"),
        )
        return object : CamelStore {
            override fun stems(stem: String) = stems[stem] ?: emptyList()
            override fun prefixes(form: String) = prefixes[form] ?: emptyList()
            override fun suffixes(form: String) = suffixes[form] ?: emptyList()
            override fun compatAllows(kind: Char, a: String, b: String) = Triple(kind, a, b) in compat
        }
    }

    private fun tashkeel(word: String) = Tashkeel.tashkeel(word, StoreTashkeelSource(store()))

    @Test
    fun addsHarakatToBareStem() {
        assertEquals("\u0630\u064E\u0647\u064E\u0628\u064E", tashkeel("\u0630\u0647\u0628"))
    }

    @Test
    fun keepsArticleDiacritics() {
        // артикль уже с сукуном в базе, стебель — с огласовкой
        assertEquals("\u0627\u0644\u0652\u0628\u064E\u064A\u0652\u062A", tashkeel("\u0627\u0644\u0628\u064A\u062A"))
    }

    @Test
    fun keepsPrefixVowel() {
        // بِ + بَيْت: огласовка предлога из feats diac префикса
        assertEquals("\u0628\u0650\u0628\u064E\u064A\u0652\u062A", tashkeel("\u0628\u0628\u064A\u062A"))
    }

    @Test
    fun keepsSuffixVowel() {
        assertEquals(B_YAYT + PRON_HU_DAMMA, tashkeel(BARE_BAYT + "\u0647"))
    }

    @Test
    fun returnsNullForUnknownWord() {
        assertNull(tashkeel("\u0645\u062F\u0631\u0633\u0629"))
    }

    @Test
    fun returnsNullWhenAlreadyVoweled() {
        assertNull(tashkeel("\u0630\u064E\u0647\u064E\u0628\u064E"))
    }

    @Test
    fun returnsNullForBlank() {
        assertNull(tashkeel("   "))
    }

    @Test
    fun composesFromParts() {
        val v = TashkeelVariant(prefix = "\u0648", prefixDiac = "", stem = "\u0630\u0647\u0628", stemDiac = "\u0630\u064E\u0647\u064E\u0628\u064E")
        assertEquals("\u0648\u0630\u064E\u0647\u064E\u0628\u064E", Tashkeel.compose(v))
    }

    @Test
    fun stripsTatweel() {
        val v = TashkeelVariant(stem = "\u0630\u0640\u0647\u0628", stemDiac = "\u0630\u0640\u064E\u0647\u064E\u0628\u064E")
        assertTrue('\u0640' !in Tashkeel.compose(v))
    }

    @Test
    fun prefersMostFrequentLexeme() {
        val source = object : TashkeelSource {
            override fun variants(word: String) = listOf(
                TashkeelVariant(stem = "\u0630\u0647\u0628", stemDiac = "\u0630\u064E\u0647\u064E\u0628", logprob = -3.0),
                TashkeelVariant(stem = "\u0630\u0647\u0628", stemDiac = "\u0630\u064E\u0647\u064E\u0628\u064E", logprob = -0.2),
            )
        }
        assertEquals("\u0630\u064E\u0647\u064E\u0628\u064E", Tashkeel.tashkeel("\u0630\u0647\u0628", source))
    }

    @Test
    fun rejectsVariantWhoseBareFormDiffers() {
        // источник «насочинял»: снятая огласовка не совпадает с исходной формой
        val source = object : TashkeelSource {
            override fun variants(word: String) = listOf(
                TashkeelVariant(stem = "\u0628\u064A\u062A", stemDiac = "\u0628\u064E\u064A\u0652\u062A", logprob = -1.0),
            )
        }
        assertNull(Tashkeel.tashkeel("\u0630\u0647\u0628", source))
    }

    @Test
    fun handlesEmptyVariants() {
        val source = object : TashkeelSource {
            override fun variants(word: String) = emptyList<TashkeelVariant>()
        }
        assertNull(Tashkeel.tashkeel("\u0630\u0647\u0628", source))
    }
}
