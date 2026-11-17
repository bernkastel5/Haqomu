package com.bernkastel5.haqomu.camel

/**
 * Порт tools/build_camel_pack.py (calima-msa-r13: текстовый morphology.db → camel_pack.sqlite).
 *
 * Отличия от Python-эталона (осознанные, см. docs/adr/0005):
 *  - потоковый разбор: строки отдаём в [PackSink] по одной, память не растёт на 2М строк;
 *  - поля trim-ятся (Python держал stem нетримленным; иначе огрехи дампа попадают в индекс);
 *  - JSON аффиксов собирается руками (org.json недоступен в JVM-тестах);
 *  - поведение stripLex/parseFeats сохранено 1:1, включая quirk с полем root.
 *
 * Quirk референса: в calima root записан как "root:ذ ه ب", токенизация по пробелу даёт
 * root="ذ". Сохранено намеренно: сверка с Python-эталоном важнее косметики.
 */

data class StemEntry(
    val stem: String, val cat: String, val pos: String, val lex: String,
    val lexLogprob: Double, val root: String, val featsJson: String?,
    /**
     * Огласованный вид стебля из calima (`diac`). Хранится отдельной колонкой, чтобы
     * карточка могла показать слово с огласовкой (Ф15). Пусто — база огласовку не дала.
     */
    val stemDiac: String = "",
)

data class AffixEntry(val form: String, val kind: String, val cat: String, val featsJson: String) {
    companion object {
        const val PREFIX = "prefix"
        const val SUFFIX = "suffix"
    }
}

data class CompatEntry(val kind: String, val a: String, val b: String)

/** Приёмник разобранных данных: Android-запись пишет в SQLite потоково, тесты собирают в память. */
interface PackSink {
    fun onStem(entry: StemEntry)
    fun onPrefix(entry: AffixEntry)
    fun onSuffix(entry: AffixEntry)
    fun onCompat(entry: CompatEntry)
    /** Вызывается один раз в конце: счётчики для meta (строки) и max sizes. */
    fun onCounts(counts: Map<String, String>)
}

object CamelMorphologyParser {
    const val SCHEMA_VERSION = "1"
    const val SOURCE = "calima-msa-r13"
    const val LICENSE = "GPL-2"

    private const val SEC_PREFIXES = "###PREFIXES###"
    private const val SEC_SUFFIXES = "###SUFFIXES###"
    private const val SEC_STEMS = "###STEMS###"
    private const val SEC_AB = "###TABLE AB###"
    private const val SEC_BC = "###TABLE BC###"
    private const val SEC_AC = "###TABLE AC###"
    private val SECTIONS = setOf(SEC_PREFIXES, SEC_SUFFIXES, SEC_STEMS, SEC_AB, SEC_BC, SEC_AC)

    /** strip_lex camel_tools: отрезает хвост после первого '_' или '-' (lex-идентификаторы 'ذهب_1'). */
    internal fun stripLex(lex: String?): String {
        val t = lex?.trim() ?: return ""
        val i = t.indexOfAny(charArrayOf('_', '-'))
        return (if (i < 0) t else t.substring(0, i)).trim()
    }

    internal fun parseFeatsTokens(tokens: Iterable<String>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (tok in tokens) {
            if (tok.isEmpty()) continue
            val i = tok.indexOf(':')
            if (i < 0) continue
            out[tok.substring(0, i)] = tok.substring(i + 1)
        }
        return out
    }

    internal fun escapeJson(s: String): String = buildString(s.length + 8) {
        for (c in s) when (c) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c < ' ') append("\\u" + c.code.toString(16).padStart(4, '0')) else append(c)
        }
    }

    internal fun toJsonObject(map: Map<String, String>): String =
        map.entries.joinToString(",", "{", "}") { "\"${escapeJson(it.key)}\":\"${escapeJson(it.value)}\"" }

    private fun whitespaceTokens(s: String): List<String> =
        s.split(Regex("\\s+")).filter { it.isNotEmpty() }

    /**
     * Разбор дампа. Возвращает число строк. Sink получает записи по мере разбора.
     * Прогресс: onProgress каждые [progressEvery] строк.
     */
    fun parse(
        lines: Sequence<String>,
        sink: PackSink,
        onProgress: ((Long) -> Unit)? = null,
        progressEvery: Long = 50_000,
    ): Long {
        var section: String? = null
        var linesRead = 0L
        var nStems = 0L; var nPrefixes = 0L; var nSuffixes = 0L
        var nAb = 0L; var nBc = 0L; var nAc = 0L
        var maxPrefix = 0; var maxSuffix = 0

        for (raw in lines) {
            linesRead += 1
            val line = raw.trimEnd('\n', '\r')
            val s = line.trim()
            if (s in SECTIONS) {
                section = s
                continue
            }
            when (section) {
                SEC_PREFIXES -> if (s.isNotEmpty()) {
                    val parts = line.split('\t')
                    if (parts.size == 3) {
                        val form = parts[0].trim()
                        val cat = parts[1]
                        val feats = parseFeatsTokens(whitespaceTokens(parts[2]))
                        sink.onPrefix(AffixEntry(form, AffixEntry.PREFIX, cat, toJsonObject(feats)))
                        nPrefixes += 1
                        if (form.length > maxPrefix) maxPrefix = form.length
                    }
                }
                SEC_SUFFIXES -> if (s.isNotEmpty()) {
                    val parts = line.split('\t')
                    if (parts.size == 3) {
                        val form = parts[0].trim()
                        val cat = parts[1]
                        val feats = parseFeatsTokens(whitespaceTokens(parts[2]))
                        sink.onSuffix(AffixEntry(form, AffixEntry.SUFFIX, cat, toJsonObject(feats)))
                        nSuffixes += 1
                        if (form.length > maxSuffix) maxSuffix = form.length
                    }
                }
                SEC_STEMS -> if (s.isNotEmpty()) {
                    val parts = line.split('\t')
                    if (parts.size == 3) {
                        val feats = parseFeatsTokens(whitespaceTokens(parts[2]))
                        val logprob = feats["lex_logprob"]?.toDoubleOrNull() ?: -99.0
                        sink.onStem(
                            StemEntry(
                                stem = parts[0].trim(),
                                cat = parts[1],
                                pos = feats["pos"] ?: "",
                                lex = stripLex(feats["lex"]),
                                lexLogprob = logprob,
                                root = feats["root"] ?: "",
                                featsJson = null,
                                stemDiac = feats["diac"] ?: "",
                            )
                        )
                        nStems += 1
                    }
                }
                SEC_AB -> {
                    val toks = whitespaceTokens(s)
                    if (toks.size == 2) {
                        sink.onCompat(CompatEntry("ab", toks[0], toks[1]))
                        nAb += 1
                    }
                }
                SEC_BC -> {
                    val toks = whitespaceTokens(s)
                    if (toks.size == 2) {
                        sink.onCompat(CompatEntry("bc", toks[0], toks[1]))
                        nBc += 1
                    }
                }
                SEC_AC -> {
                    val toks = whitespaceTokens(s)
                    if (toks.size == 2) {
                        sink.onCompat(CompatEntry("ac", toks[0], toks[1]))
                        nAc += 1
                    }
                }
            }
            if (onProgress != null && linesRead % progressEvery == 0L) onProgress(linesRead)
        }

        sink.onCounts(
            linkedMapOf(
                "stems" to nStems.toString(),
                "prefixes" to nPrefixes.toString(),
                "suffixes" to nSuffixes.toString(),
                "max_prefix_size" to maxPrefix.toString(),
                "max_suffix_size" to maxSuffix.toString(),
                "compat_ab" to nAb.toString(),
                "compat_bc" to nBc.toString(),
                "compat_ac" to nAc.toString(),
            )
        )
        return linesRead
    }

    /** meta, как в Python (schema_version, source, license, build_date, затем счётчики). */
    fun metaMap(counts: Map<String, String>, buildDate: String): Map<String, String> = linkedMapOf(
        "schema_version" to SCHEMA_VERSION,
        "source" to SOURCE,
        "license" to LICENSE,
        "build_date" to buildDate,
        "stems" to (counts["stems"] ?: "0"),
        "prefixes" to (counts["prefixes"] ?: "0"),
        "suffixes" to (counts["suffixes"] ?: "0"),
        "max_prefix_size" to (counts["max_prefix_size"] ?: "0"),
        "max_suffix_size" to (counts["max_suffix_size"] ?: "0"),
        "compat_ab" to (counts["compat_ab"] ?: "0"),
        "compat_bc" to (counts["compat_bc"] ?: "0"),
        "compat_ac" to (counts["compat_ac"] ?: "0"),
    )
}
