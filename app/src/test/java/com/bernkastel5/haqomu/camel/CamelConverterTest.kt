package com.bernkastel5.haqomu.camel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Тот же синтетический дамп, что в tools/build_camel_pack.py --selftest. */
private val SAMPLE = """
###DEFINES###
DEFINE pos VERB:VRB NOUN:NOM
###DEFAULTS###
DEFAULT pos:NOUN
###ORDER###
ORDER pos lex
###TOKENIZATIONS###
###STEMBACKOFF###
###PREFIXES###
و	CONJ	pos:CC
ال	DET	pos:DET
###SUFFIXES###
	NSUFF	pos:- lex:-
ون	M_PL_NOM	pos:- lex:-
###STEMS###
ذهب	VERB_STEM	pos:VERB lex:ذهب lex_logprob:-0.50 root:ذ ه ب
بيت	NOUN_STEM	pos:NOUN lex:بيت lex_logprob:-0.80 root:ب ي ت
###TABLE AB###
CONJ VERB_STEM
CONJ NOUN_STEM
DET NOUN_STEM
###TABLE BC###
VERB_STEM NSUFF
NOUN_STEM M_PL_NOM
NOUN_STEM NSUFF
###TABLE AC###
CONJ NSUFF
CONJ M_PL_NOM
DET M_PL_NOM
DET NSUFF
""".trimIndent()

private class CollectingSink : PackSink {
    val stems = mutableListOf<StemEntry>()
    val prefixes = mutableListOf<AffixEntry>()
    val suffixes = mutableListOf<AffixEntry>()
    val compat = mutableListOf<CompatEntry>()
    var counts: Map<String, String> = emptyMap()

    override fun onStem(entry: StemEntry) { stems.add(entry) }
    override fun onPrefix(entry: AffixEntry) { prefixes.add(entry) }
    override fun onSuffix(entry: AffixEntry) { suffixes.add(entry) }
    override fun onCompat(entry: CompatEntry) { compat.add(entry) }
    override fun onCounts(counts: Map<String, String>) { this.counts = counts }
}

class CamelConverterTest {

    private fun parse(sample: String): Pair<CollectingSink, Long> {
        val sink = CollectingSink()
        val lines = CamelMorphologyParser.parse(sample.lineSequence(), sink)
        return sink to lines
    }

    @Test
    fun parsesStemsWithFeats() {
        val (sink, _) = parse(SAMPLE)
        assertEquals(2, sink.stems.size)
        val go = sink.stems[0]
        assertEquals("ذهب", go.stem)
        assertEquals("VERB_STEM", go.cat)
        assertEquals("VERB", go.pos)
        assertEquals("ذهب", go.lex)
        assertEquals(-0.50, go.lexLogprob, 1e-9)
        // quirk эталона: root записан "root:ذ ه ب", после токенизации остаётся первый токен
        assertEquals("ذ", go.root)
        assertNull(go.featsJson)
        val house = sink.stems[1]
        assertEquals("NOUN", house.pos)
        assertEquals("بيت", house.lex)
    }

    @Test
    fun parsesAffixesAndCompat() {
        val (sink, _) = parse(SAMPLE)
        assertEquals(listOf("و", "ال"), sink.prefixes.map { it.form })
        assertEquals("CONJ", sink.prefixes[0].cat)
        assertTrue(sink.prefixes[0].featsJson.contains("\"pos\":\"CC\""))
        assertEquals("DET", sink.prefixes[1].cat)
        // пустая строка-суффикс: форма из строки вида "\tNSUFF\tpos:- lex:-"
        assertEquals(2, sink.suffixes.size)
        assertEquals("NSUFF", sink.suffixes[0].cat)
        assertEquals("M_PL_NOM", sink.suffixes[1].cat)
        assertEquals(
            listOf("ab" to ("CONJ" to "VERB_STEM"), "ab" to ("CONJ" to "NOUN_STEM"), "ab" to ("DET" to "NOUN_STEM")),
            sink.compat.filter { it.kind == "ab" }.map { it.kind to (it.a to it.b) },
        )
        assertEquals(4, sink.compat.count { it.kind == "ac" })
        assertEquals(3, sink.compat.count { it.kind == "bc" })
    }

    @Test
    fun countsAndMaxSizes() {
        val (sink, lines) = parse(SAMPLE)
        assertEquals(
            mapOf(
                "stems" to "2", "prefixes" to "2", "suffixes" to "2",
                "max_prefix_size" to "2", "max_suffix_size" to "2",
                "compat_ab" to "3", "compat_bc" to "3", "compat_ac" to "4",
            ),
            sink.counts,
        )
        assertEquals(SAMPLE.lineSequence().count().toLong(), lines)
    }

    @Test
    fun malformedRowsAreSkipped() {
        val sample = SAMPLE + "\nбитый\tряд\n###STEMS###\nفقط\n###STEMS###\nقf\tX\tpos:VERB lex:ق lex_logprob:-1 root:ق"
        val (sink, _) = parse(sample)
        // добавлена одна валидная строчка стебла после повторного заголовка
        assertEquals(3, sink.stems.size)
    }

    @Test
    fun stripLexSemantics() {
        assertEquals("ذهب", CamelMorphologyParser.stripLex("ذهب_1"))
        assertEquals("كتب", CamelMorphologyParser.stripLex("كتب-2"))
        assertEquals("abc", CamelMorphologyParser.stripLex("abc"))
        assertEquals("", CamelMorphologyParser.stripLex(null))
        assertEquals("", CamelMorphologyParser.stripLex("_"))
    }

    @Test
    fun jsonIsEscaped() {
        assertEquals("""{"a":"b\"c"}""", CamelMorphologyParser.toJsonObject(mapOf("a" to "b\"c")))
        assertEquals("""{"k":"v\\w"}""", CamelMorphologyParser.toJsonObject(mapOf("k" to "v\\w")))
        assertEquals("{}", CamelMorphologyParser.toJsonObject(emptyMap()))
    }

    @Test
    fun featsTokensEdgeCases() {
        assertEquals(mapOf("pos" to "VERB", "lex" to "ذهب"),
            CamelMorphologyParser.parseFeatsTokens(listOf("pos:VERB", "lex:ذهب", "", "nocolon")))
        // токен из одного двоеточия: ключ и значение пустые (семантика Python: split(':',1))
        assertEquals("", CamelMorphologyParser.parseFeatsTokens(listOf(":"))[""])
    }

    @Test
    fun metaMapLayoutAndDate() {
        val counts = mapOf(
            "stems" to "2", "prefixes" to "2", "suffixes" to "2",
            "max_prefix_size" to "2", "max_suffix_size" to "2",
            "compat_ab" to "3", "compat_bc" to "3", "compat_ac" to "4",
        )
        val meta = CamelMorphologyParser.metaMap(counts, "2026-11-14")
        assertEquals("1", meta["schema_version"])
        assertEquals("calima-msa-r13", meta["source"])
        assertEquals("GPL-2", meta["license"])
        assertEquals("2026-11-14", meta["build_date"])
        assertEquals("2", meta["stems"])
        assertEquals("4", meta["compat_ac"])
        assertEquals(12, meta.size)
    }

    @Test
    fun logprobDefaultsToMinus99() {
        val sample = "###STEMS###\nذهب\tVERB_STEM\tpos:VERB lex:ذهب broken"
        val (sink, _) = parse(sample)
        assertEquals(-99.0, sink.stems.single().lexLogprob, 1e-9)
    }
}
