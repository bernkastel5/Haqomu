package com.bernkastel5.haqomu.anki

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Локальное хранилище накопленных карточек: `filesDir/anki_pending.json`.
 * Ничего не покидает устройство; используется, если AnkiDroid недоступен
 * (или для переноса в Anki Desktop файлом .apkg).
 */
object PendingStore {
    private const val FILE_NAME = "anki_pending.json"

    private fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    fun count(context: Context): Int = load(context).size

    fun load(context: Context): List<PendingCard> {
        val f = file(context)
        if (!f.isFile) return emptyList()
        return try {
            val root = JSONObject(f.readText())
            val arr = root.optJSONArray("cards") ?: return emptyList()
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                PendingCard(
                    expression = o.optString("expression"),
                    tashkeel = o.optString("tashkeel"),
                    meaning = o.optString("meaning"),
                    sentence = o.optString("sentence"),
                    root = o.optString("root"),
                    dictionary = o.optString("dictionary"),
                    notes = o.optString("notes"),
                    createdAt = o.optLong("createdAt"),
                )
            }
        } catch (_: Exception) {
            // Битый файл не должен ронять приложение; возвращаем пустое не трогая файл.
            emptyList()
        }
    }

    fun add(context: Context, card: PendingCard): Int {
        val cards = load(context) + card.copy(createdAt = System.currentTimeMillis())
        save(context, cards)
        return cards.size
    }

    fun removeAt(context: Context, index: Int): List<PendingCard> {
        val cards = load(context).toMutableList()
        if (index in cards.indices) cards.removeAt(index)
        save(context, cards)
        return cards
    }

    fun clear(context: Context) {
        save(context, emptyList())
    }

    private fun save(context: Context, cards: List<PendingCard>) {
        val arr = JSONArray()
        for (card in cards) {
            arr.put(
                JSONObject().apply {
                    put("expression", card.expression)
                    put("tashkeel", card.tashkeel)
                    put("meaning", card.meaning)
                    put("sentence", card.sentence)
                    put("root", card.root)
                    put("dictionary", card.dictionary)
                    put("notes", card.notes)
                    put("createdAt", card.createdAt)
                }
            )
        }
        val root = JSONObject().put("cards", arr)
        val tmp = File(context.filesDir, "$FILE_NAME.tmp")
        tmp.writeText(root.toString())
        if (!tmp.renameTo(file(context))) {
            tmp.delete()
            file(context).writeText(root.toString())
        }
    }
}
