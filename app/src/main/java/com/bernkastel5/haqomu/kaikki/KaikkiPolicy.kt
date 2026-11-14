package com.bernkastel5.haqomu.kaikki

/**
 * Чистая политика отбора и нормализации для Kaikki (Wiktionary ar→en).
 * Порт tools/build_kaikki_index.py: нормализация ключей и отбор смыслов
 * воспроизводят эталон (pick_gloss), при этом §4-пак хранит ВСЕ подходящие
 * смыслы по порядку (Ф9), а не только первый. Тестируется на JVM.
 */
object KaikkiPolicy {

    private val SKIP_SENSE_TAGS = setOf("obsolete", "archaic", "no-gloss")
    private val DIAC = Regex("[\\u0610-\\u061A\\u064B-\\u065F\\u0670\\u0640]")
    private val NON_ARABIC = Regex("[^\\u0600-\\u06FF]")
    private val ROOT_CATEGORY = Regex("^Arabic terms belonging to the root (.+)$")

    data class RawSense(val gloss: String, val tags: List<String>, val example: String?)

    data class SensePlan(
        val ord: Int,
        val definition: String,
        val glossEn: String,
        val example: String?,
        val tags: List<String>,
    )

    fun stripDiac(s: String?): String =
        DIAC.replace(java.text.Normalizer.normalize(s ?: "", java.text.Normalizer.Form.NFC), "").trim()

    /** Ключ поиска: без диакритики, унифицированные алиф/йа/та-марбута, только арабские буквы. */
    fun norm(s: String?): String {
        var out = stripDiac(s)
        out = out.replace(Regex("[أإآٱ]"), "ا").replace(Regex("[ىئ]"), "ي").replace('ة', 'ه')
        return NON_ARABIC.replace(out, "")
    }

    /**
     * Только арабское слово: эталон clean_ar(w) = bool(w) and not re.search(r"[^\u0600-\u06FF\s]", w).
     */
    fun isArabicWord(s: String?): Boolean {
        if (s.isNullOrBlank()) return false
        return s.all { it.code in 0x0600..0x06FF || it.code == 0x0020 || it.code == 0x00A0 }
    }

    fun rootFromCategories(categoryNames: List<String>): String? {
        for (name in categoryNames) {
            val m = ROOT_CATEGORY.find(name.trim())
            if (m != null) return m.groupValues[1].replace(" ", "")
        }
        return null
    }

    /**
     * Смыслы статьи: эталон pick_gloss оставляет смыслы, не помеченные obsolete/archaic/no-gloss,
     * и отбрасывает глоссы, начинающиеся с form of / verbal noun of / plural of.
     * Если ни одного — берёт «хоть что-то» (первый непустой глосс). Здесь сохраняем ВСЕ
     * подходящие смыслы по порядку (Ф9).
     */
    fun planSenses(senses: List<RawSense>): List<SensePlan>? {
        val usable = senses.filter { s ->
            val g = s.gloss.trim()
            g.isNotEmpty() &&
                s.tags.none { it in SKIP_SENSE_TAGS } &&
                !g.startsWith("form of") && !g.startsWith("verbal noun of") && !g.startsWith("plural of")
        }
        val chosen = if (usable.isNotEmpty()) usable else senses.filter { it.gloss.trim().isNotEmpty() }
        if (chosen.isEmpty()) return null
        return chosen.mapIndexed { i, s ->
            val g = s.gloss.trim()
            SensePlan(
                ord = i + 1,
                definition = g.take(300),
                glossEn = g.take(300),
                example = s.example?.trim()?.take(300),
                tags = s.tags.sorted(),
            )
        }
    }
}
