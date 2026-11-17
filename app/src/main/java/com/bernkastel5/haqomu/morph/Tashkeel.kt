package com.bernkastel5.haqomu.morph

/**
 * Огласовка слова по данным морфологической базы CAMeL (Ф15, огласовка в карточке).
 *
 * Мотивация: в карточке Anki поле «слово» берётся из текста, где выделено, и обычно
 * оно **без огласовки**. Поле Tashkeel заполняется головой словарной статьи и с
 * выделенным словом может не совпадать (артикль, клитика, другая форма). Хочется
 * показать ту же словоформу, но с огласовкой.
 *
 * Почему не нейросетевой Tashkeel (CATT, Mishkal и пр.): это ещё одна модель на
 * десятки-сотни мегабайт и ещё один путь «скачать и поставить». Здесь уже лежит
 * морфологическая база, где у каждой словоформы есть огласованный разбор calima
 * (`diac`), так что огласовка — это поиск, а не предсказание. Ограничение честное:
 * если формы нет в базе, огласовки не будет (возвращаем null, карточка остаётся как
 * была). Никаких выдуманных огласовок.
 *
 * Как собирается. В паке (`camel_pack.sqlite`, см. tools/build_camel_pack.py и
 * CamelMorphologyParser) у стебля есть огласованный вид `stem_diac` и `stem_feats`,
 * у аффиксов — `cat` и `feats`:
 *  - префиксы calima уже содержат свою огласовку в самом виде (`بِ`, `الْ`, `وَ`);
 *  - вид стебля с огласовкой лежит в feats `diac` (без огласовки — `stem`);
 *  - у суффиксов огласовка приходит из feats (`diac`, `gen`, `num`, `per`, `asp`),
 *    а когда их нет — из самого вида аффикса.
 * Сборка: `префикс(вид) + стебель(diac) + суффикс(diac)`; татвиль вычищается,
 * потому что в карточке он не нужен.
 */
object Tashkeel {

    /** Сколько вариантов рассматривать: больше — только медленнее, а нужен один. */
    private const val MAX_VARIANTS = 40

    /**
     * Огласованный вид словоформы [word] или null, если база форму не знает.
     * [lookup] отдаёт кандидатов разбора; в приложении — чтение camel_pack.
     */
    fun tashkeel(word: String, lookup: TashkeelSource): String? {
        val raw = word.trim()
        if (raw.isEmpty()) return null
        // Уже с огласовкой — второй раз расставлять нечего.
        if (raw.any { it in HARAKAT }) return null
        val bare = ArabicNormalize.un(raw)
        if (bare.isEmpty()) return null

        val variants = lookup.variants(bare).take(MAX_VARIANTS)
        if (variants.isEmpty()) return null
        val best = variants.maxByOrNull { it.logprob } ?: return null
        val composed = compose(best)
        // Проверка «не насочиняли»: снятая огласовка обязана совпасть с исходной формой.
        if (ArabicNormalize.un(composed) != bare) return null
        return composed
    }

    /** Сборка огласованного вида из частей разбора. */
    fun compose(v: TashkeelVariant): String {
        val prefix = v.prefixDiac.ifBlank { v.prefix }
        val stem = v.stemDiac.ifBlank { v.stem }
        val suffix = v.suffixDiac.ifBlank { v.suffix }
        return (prefix + stem + suffix).replace("\u0640", "")
    }

    /** Харакат: фатха, дамма, кясра, сукун, шадда, танвин, надстрочный алиф и т.п. */
    private val HARAKAT = ('\u064B'..'\u0652').toSet() + '\u0670' + '\u0653' + '\u0654' + '\u0655'
}

/** Один разбор с огласовкой: части формы и их огласованные виды. */
data class TashkeelVariant(
    val prefix: String = "",
    val prefixDiac: String = "",
    val stem: String = "",
    val stemDiac: String = "",
    val suffix: String = "",
    val suffixDiac: String = "",
    /** lex_logprob лексемы стебля: больше — частотнее. */
    val logprob: Double = 0.0,
)

/** Источник разборов для огласовки. В приложении — чтение camel_pack, в тестах — синтетика. */
interface TashkeelSource {
    /** Все разборы словоформы (без огласовки) с их огласованными частями. */
    fun variants(word: String): List<TashkeelVariant>
}
