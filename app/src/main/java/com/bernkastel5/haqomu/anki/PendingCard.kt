package com.bernkastel5.haqomu.anki

/**
 * Накопленная карточка (ещё не добавленная в Anki). Чистый класс без зависимостей —
 * хранится в JSON-файле (PendingStore, только Android), экспортируется .apkg (ApkgPackager).
 */
data class PendingCard(
    val expression: String,
    val tashkeel: String,
    val meaning: String,
    val sentence: String = "",
    val root: String = "",
    val dictionary: String = "",
    val notes: String = "",
    val createdAt: Long = 0L,
) {
    /** Поля заметки в порядке AnkiModel.FIELDS. */
    fun toFields(): Array<String> = AnkiModel.fieldsArray(
        expression = expression,
        tashkeel = tashkeel,
        meaning = meaning,
        sentence = sentence,
        root = root,
        dictionary = dictionary,
        notes = notes,
    )

    companion object {
        /** Карточка годится, если есть хотя бы слово и значение. */
        fun isValid(expression: String, meaning: String): Boolean =
            expression.isNotBlank() && meaning.isNotBlank()
    }
}
