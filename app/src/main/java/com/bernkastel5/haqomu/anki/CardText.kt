package com.bernkastel5.haqomu.anki

/** Сборка текста карточки из выбранного смысла (общий для быстрого сохранения и редактора). */
object CardText {
    /**
     * Значение карточки: арабское определение; английский глосс добавляется после пустой строки,
     * если он есть и не совпадает с определением.
     */
    fun meaningText(definition: String, glossEn: String?): String {
        val def = definition.trim()
        val gloss = glossEn?.trim().orEmpty()
        return if (gloss.isNotEmpty() && gloss != def) "$def\n\n$gloss" else def
    }
}
