package com.bernkastel5.haqomu.gloss

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ф10: токенизатор BERT-типа для пары «предложение — толкование». Словарь синтетический:
 * словарных данных здесь нет и быть не может (правила §3 PROJECT_STATE).
 */
class BertTokenizerTest {

    private val vocab = listOf(
        "[PAD]", "[UNK]", "[CLS]", "[SEP]",
        "ذهب", "الطفل", "إلى", "ال", "##بيت", "بيت", "##ت",
        "سار", "و", "##مضى",
        "to", "go", "##ing", "the", "house",
    )

    private fun tokenizer(lowercase: Boolean = false, stripAccents: Boolean = true) =
        BertTokenizer(vocab, lowercase = lowercase, stripAccents = stripAccents)

    @Test
    fun singleSequenceHasSpecialTokens() {
        val enc = tokenizer().encode("ذهب الطفل")
        assertEquals(listOf(2L, 4L, 5L, 3L), enc.inputIds.toList())
        assertEquals(listOf(0L, 0L, 0L, 0L), enc.tokenTypeIds.toList())
    }

    @Test
    fun pairSeparatesSegmentsWithTokenTypes() {
        val enc = tokenizer().encodePair("ذهب الطفل", "to go")
        // [CLS] ذهب الطفل [SEP] to go [SEP]
        assertEquals(7, enc.length)
        assertEquals(listOf(2L, 4L, 5L, 3L, 14L, 15L, 3L), enc.inputIds.toList())
        assertEquals(listOf(0L, 0L, 0L, 0L, 1L, 1L, 1L), enc.tokenTypeIds.toList())
    }

    @Test
    fun wordPieceSplitsUnknownWordIntoPieces() {
        // «البيت» → ال + ##بيت (в словаре есть обе части)
        val enc = tokenizer().encode("البيت")
        assertEquals(listOf(2L, 7L, 8L, 3L), enc.inputIds.toList())
    }

    @Test
    fun unknownChunkBecomesUnk() {
        val enc = tokenizer().encode("زززززز")
        assertTrue(enc.inputIds.contains(1L))
    }

    @Test
    fun harakatAreStrippedWhenStripAccentsIsOn() {
        val withMarks = tokenizer().encode("ذَهَبَ")
        val plain = tokenizer().encode("ذهب")
        assertEquals(plain.inputIds.toList(), withMarks.inputIds.toList())
    }

    @Test
    fun stripAccentsCanBeDisabled() {
        val enc = tokenizer(stripAccents = false).encode("ذَهَبَ")
        // с огласовкой слово не собирается из словаря — неизвестный токен
        assertTrue(enc.inputIds.contains(1L))
    }

    @Test
    fun lowercaseFlagIsHonoured() {
        val upper = tokenizer(lowercase = false).encodePair("ذهب", "TO GO")
        val lower = tokenizer(lowercase = true).encodePair("ذهب", "TO GO")
        assertTrue(upper.inputIds.contains(1L))
        assertTrue(!lower.inputIds.contains(1L))
    }

    @Test
    fun punctuationBecomesSeparateTokens() {
        val enc = tokenizer().encode("ذهب، الطفل.")
        // «،» и «.» — отдельные токены, их нет в словаре → [UNK]
        assertEquals(6, enc.length)
    }

    @Test
    fun pairIsTruncatedLongestFirst() {
        val long = List(40) { "الطفل" }.joinToString(" ")
        val enc = tokenizer().encodePair(long, "to go", maxLength = 12)
        assertEquals(12, enc.length)
        assertTrue(enc.tokenTypeIds.contains(1L))
    }

    @Test
    fun emptySecondSequenceMeansSingleSequence() {
        val single = tokenizer().encode("ذهب")
        assertEquals(single.inputIds.toList(), tokenizer().encodePair("ذهب", "").inputIds.toList())
    }

    @Test
    fun idsAreStableForTheSameInput() {
        val t = tokenizer()
        assertEquals(t.encode("ذهب الطفل").inputIds.toList(), t.encode("  ذهب   الطفل ").inputIds.toList())
    }

    @Test
    fun vocabFromTextSkipsBlankLines() {
        val t = BertTokenizer.fromVocabText("[PAD]\n[UNK]\n\n[CLS]\n[SEP]\nذهب\n")
        assertEquals(5, t.vocabularySize)
        assertEquals(4, t.idOf("ذهب"))
    }
}
