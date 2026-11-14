package com.bernkastel5.haqomu.morph

/**
 * Разбор слова по базе calima, подмножество CAMeL Tools (analyze only).
 * Порт: camel_tools.morphology.analyzer.{_segments_gen, Analyzer.analyze,
 * _combined_analyses} + merge_features с правилом «stem, затем suffix, затем prefix
 * перезаписывают не-('-','') значения». Произношение/caphi и token schemes не нужны.
 */
data class Analysis(
    val pos: String,
    val lex: String,
    val lexLogprob: Double,
    val root: String?,
    val stemCat: String,
    val stem: String,
)

class Analyzer(
    private val store: CamelStore,
    private val maxPrefix: Int = 3,
    private val maxSuffix: Int = 3,
) {

    fun segments(word: String): List<Triple<String, String, String>> {
        val out = mutableListOf<Triple<String, String, String>>()
        val w = word.length
        if (w < 1) return out
        for (p in 0..minOf(maxPrefix, w - 1)) {
            for (s in maxOf(1, w - p - maxSuffix)..(w - p)) {
                out.add(Triple(word.substring(0, p), word.substring(p, p + s), word.substring(p + s)))
            }
        }
        return out
    }

    fun analyze(word: String): List<Analysis> {
        val w = ArabicNormalize.un(word).trim()
        if (w.isEmpty()) return emptyList()
        val result = mutableListOf<Analysis>()
        for ((prefix, stem, suffix) in segments(w)) {
            val prefixRows = store.prefixes(prefix)
            if (prefixRows.isEmpty()) continue
            val stemRows = store.stems(stem)
            if (stemRows.isEmpty()) continue
            val suffixRows = store.suffixes(suffix)
            if (suffixRows.isEmpty()) continue

            for (stemRow in stemRows) {
                for (pRow in prefixRows) {
                    if (!store.compatAllows('a', pRow.cat, stemRow.cat)) continue
                    for (sRow in suffixRows) {
                        if (!store.compatAllows('b', stemRow.cat, sRow.cat)) continue
                        if (!store.compatAllows('c', pRow.cat, sRow.cat)) continue

                        val merged = stemFeats(stemRow).toMutableMap()
                        overrideFeats(merged, sRow.feats)
                        overrideFeats(merged, pRow.feats)

                        result.add(
                            Analysis(
                                pos = merged["pos"] ?: stemRow.pos,
                                lex = merged["lex"] ?: stemRow.lex,
                                lexLogprob = merged["lex_logprob"]?.toDoubleOrNull() ?: stemRow.lexLogprob,
                                root = merged["root"] ?: stemRow.root,
                                stemCat = stemRow.cat,
                                stem = stem,
                            )
                        )
                    }
                }
            }
        }
        return result
    }

    /** Леммы слова, отсортированные по убыванию lex_logprob (лучшая первая). */
    fun lemmas(word: String): List<String> {
        return analyze(word)
            .groupBy { it.lex }
            .map { (lex, analyses) -> lex to analyses.maxOf { it.lexLogprob } }
            .sortedByDescending { it.second }
            .map { it.first }
    }

    private fun stemFeats(row: StemRow): Map<String, String> {
        val base = mutableMapOf(
            "pos" to row.pos,
            "lex" to row.lex,
            "lex_logprob" to row.lexLogprob.toString(),
        )
        row.root?.let { base["root"] = it }
        return base
    }

    private fun overrideFeats(base: MutableMap<String, String>, feats: Map<String, String>) {
        for ((k, v) in feats) {
            if (v == "-" || v.isEmpty()) continue
            base[k] = v
        }
    }
}
