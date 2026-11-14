package com.bernkastel5.haqomu.morph

import java.text.Normalizer

/**
 * Единая нормализация: снятие огласовки и татвиля, унификация букв.
 * Эквивалент un()/key() из tools/name_class.py и DEFAULT_NORMALIZE_MAP CAMeL
 * (إ أ آ ٱ → ا, ى → ي, ة → ه). Оригинал строки хранить отдельно для показа.
 */
object ArabicNormalize {

    private val HARAKAT_AND_TATWEEL = Regex("[\\u0640\\u064B-\\u0652\\u0670]")

    fun un(s: String?): String {
        if (s == null) return ""
        return s
            .replace(HARAKAT_AND_TATWEEL, "")
            .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا').replace('ٱ', 'ا')
            .replace('ى', 'ي').replace('ة', 'ه')
    }

    fun key(s: String?): String = un(s)

    // ------------------------------------------------------------------
    // Ф4 (TZ §4): нормализация ключей словарных паков.
    // Та же схема, что в сборщиках паков (KaikkiPolicy.norm / HwPolicy.normKey):
    // NFC → снятие диакритики (широкий диапазон) → унификация → только арабские буквы.
    // ------------------------------------------------------------------

    private val DIACRITICS_BROAD = Regex("[\\u0610-\\u061A\\u064B-\\u065F\\u0670\\u0640]")
    private val NON_ARABIC = Regex("[^\\u0600-\\u06FF]")
    private val ALEF_FORMS = Regex("[أإآٱ]")
    private val YAA_FORMS = Regex("[ىئ]")

    /** Ключ поиска по словарному паку: без диакритики и не-арабских символов. */
    fun nk(s: String?): String {
        if (s == null) return ""
        var out = DIACRITICS_BROAD.replace(Normalizer.normalize(s, Normalizer.Form.NFC), "")
        out = out.replace(ALEF_FORMS, "ا").replace(YAA_FORMS, "ي").replace('ؤ', 'و').replace('ة', 'ه')
        return NON_ARABIC.replace(out, "")
    }

    /** Сравнение заголовков, устойчивое к артиклю и соединительным союзам; эталон pipeline_full.py::lemma_key. */
    fun lemmaKey(s: String?): String {
        var t = nk(s)
        for (p in listOf("وال", "بال", "كال", "فال")) {
            if (t.startsWith(p) && t.length > 5) {
                t = t.substring(3)
                break
            }
        }
        if (t.startsWith("ال") && t.length > 4) t = t.substring(2)
        return t
    }

    /** Корень для сравнения; пустой, если корень короче двух букв после снятия хамзы. */
    fun normRoot(s: String?): String {
        val t = nk(s).replace("ء", "")
        return if (t.length >= 2) t else ""
    }
}
