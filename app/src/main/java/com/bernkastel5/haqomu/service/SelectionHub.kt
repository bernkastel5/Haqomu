package com.bernkastel5.haqomu.service

import com.bernkastel5.haqomu.lookup.SelectionInfo

/**
 * Передача последнего выделения от сервиса доступности к остальному приложению (Ф3).
 *
 * Живёт только в памяти процесса: ни файла, ни настроек. Срок — [TTL_MS], чтобы
 * попап не показывал контекст от давно прошедшего выделения. Пока сервис выключен,
 * контекста нет вовсе (TZ §8: текст экрана не сохраняется и не передаётся).
 */
object SelectionHub {

    /** Сколько живёт запомненное выделение. */
    const val TTL_MS = 20_000L

    private class Entry(val info: SelectionInfo, val at: Long)

    @Volatile
    private var entry: Entry? = null

    @Volatile
    private var enabled: Boolean = false

    /** Сервис подключился / сменил режим. */
    fun setEnabled(value: Boolean) {
        enabled = value
        if (!value) entry = null
    }

    /** Сервис увидел выделение. */
    fun remember(info: SelectionInfo, now: Long = System.currentTimeMillis()) {
        entry = Entry(info, now)
    }

    /** Сервис отключился. */
    fun reset() {
        enabled = false
        entry = null
    }

    /**
     * Свежее выделение для попапа. Совпадение проверяется по слову: контекст берётся,
     * только если попап открыт для того же слова, которое выделил сервис.
     */
    fun recentFor(word: String, now: Long = System.currentTimeMillis()): SelectionInfo? {
        if (!enabled) return null
        val e = entry ?: return null
        if (now - e.at > TTL_MS) return null
        if (e.info.word != word) return null
        return e.info
    }

    /** Только для тестов и отладки. */
    fun clear() {
        entry = null
    }
}
