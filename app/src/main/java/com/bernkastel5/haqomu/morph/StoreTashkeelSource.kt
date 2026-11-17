package com.bernkastel5.haqomu.morph

/**
 * Огласовка поверх готового морфологического разбора ([Analyzer] + [CamelStore]).
 *
 * Тот же перебор сегментов, что в [Analyzer.analyze], но результату нужны не
 * признаки, а **огласованные куски**: префикс (в calima его вид уже с огласовкой),
 * `stem_diac` стебля и огласованный суффикс. Огласованный вид суффикса в базе
 * приходит из feats (`diac`, иначе `gen`/`num`, иначе нет) — см. [suffixDiac].
 *
 * Отдельный проход, а не расширение [Analysis], потому что разбор нужен на каждом
 * выделении, а огласовка — только при сохранении карточки: не хочется платить
 * строками и огласовками за то, что не показывается.
 */
class StoreTashkeelSource(private val store: CamelStore) : TashkeelSource {

    override fun variants(word: String): List<TashkeelVariant> {
        val w = ArabicNormalize.un(word).trim()
        if (w.isEmpty()) return emptyList()
        val out = mutableListOf<TashkeelVariant>()
        // maxPrefix: раздел CAMeL «ال» + «بِ» даёт два знака; трёх хватает с запасом.
        for ((prefix, stem, suffix) in Analyzer(store, maxPrefix = 3, maxSuffix = 3).segments(w)) {
            val stemRows = store.stems(stem)
            if (stemRows.isEmpty()) continue
            val prefixRows = store.prefixes(prefix)
            if (prefixRows.isEmpty()) continue
            val suffixRows = store.suffixes(suffix)
            if (suffixRows.isEmpty()) continue
            for (stemRow in stemRows) {
                for (pRow in prefixRows) {
                    if (!store.compatAllows('a', pRow.cat, stemRow.cat)) continue
                    for (sRow in suffixRows) {
                        if (!store.compatAllows('b', stemRow.cat, sRow.cat)) continue
                        if (!store.compatAllows('c', pRow.cat, sRow.cat)) continue
                        out.add(
                            TashkeelVariant(
                                prefix = prefix,
                                prefixDiac = prefixDiac(prefix, pRow.feats),
                                stem = stem,
                                stemDiac = stemRow.diac,
                                suffix = suffix,
                                suffixDiac = suffixDiac(suffix, sRow.feats),
                                logprob = stemRow.lexLogprob,
                            )
                        )
                    }
                }
            }
        }
        return out
    }

    /**
     * Огласовка префикса. В calima вид префикса пишется уже с огласовкой (`بِ`, `الْ`),
     * но если её нет — берём `diac` из признаков (если база его даёт).
     */
    private fun prefixDiac(form: String, feats: Map<String, String>): String {
        if (form.any { it in HARAKAT }) return form
        return feats["diac"]?.takeIf { it.any { ch -> ch in HARAKAT } } ?: ""
    }

    /**
     * Огласовка суффикса. В calima огласованный вид суффикса — это feats `diac`
     * (`ونَ`, `هُ`); иногда отдельные правила дают `gen`+`num` (кясра при `هِ`).
     * Если ничего нет — пусто, и [Tashkeel.compose] берёт вид суффикса как есть.
     */
    private fun suffixDiac(form: String, feats: Map<String, String>): String {
        if (form.any { it in HARAKAT }) return form
        val diac = feats["diac"]
        if (!diac.isNullOrBlank() && diac.any { it in HARAKAT }) return diac
        val gen = feats["gen"]
        if (form.length == 1 && form[0] == 'ه' && gen == "c") return "هِ"
        return ""
    }

    private companion object {
        val HARAKAT = ('\u064B'..'\u0652').toSet() + '\u0670' + '\u0653' + '\u0654' + '\u0655'
    }
}
