package com.bernkastel5.haqomu.anki

import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri

/**
 * Мост к AnkiDroid через официальный ContentProvider (без внешних зависимостей —
 * протокол повторяет com.ichi2.anki.flashcards из открытого API AnkiDroid):
 *
 *   notes    — insert(MID, FLDS \\u001f-joined, TAGS) → Uri заметки;
 *   notes/<id>/cards — запрос карточек заметки (ord, deck_id);
 *   notes/<id>/cards/<ord> — перенести карточку в целевую колоду (DECK_ID);
 *   decks    — query списка колод / insert новой (DECK_NAME);
 *   models   — query списка note type / find Model по имени.
 *
 * READ_WRITE_DATABASE — dangerous-разрешение AnkiDroid; запрашивается у пользователя
 * при первом добавлении. Если AnkiDroid не установлен или доступ запрещён —
 * вызывающая сторона сохраняет карточку локально (PendingStore).
 */
class AnkiDroidBridge(private val context: Context) {

    private val resolver = context.contentResolver

    fun isAnkiDroidInstalled(): Boolean = try {
        context.packageManager.getPackageInfo(PACKAGE, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    } catch (_: Exception) {
        false
    }

    fun hasWritePermission(): Boolean =
        context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    /** id note type по имени (у нас — «Haqomu Arabic» из .apkg), либо null. */
    fun findModelId(name: String): Long? = queryIdsByName(MODELS_URI, COL_ID, COL_NAME, name)

    /** id колоды по имени; null — колоды нет (пользователь ещё не импортировал .apkg). */
    fun findDeckId(name: String): Long? =
        queryIdsByName(DECKS_URI, DECK_COL_ID, DECK_COL_NAME, name)

    /** Создать колоду; null — не удалось. */
    fun createDeck(name: String): Long? = try {
        val uri = resolver.insert(DECKS_URI, ContentValues().apply { put(DECK_COL_NAME, name) })
        uri?.lastPathSegment?.toLongOrNull()
    } catch (_: Exception) {
        null
    }

    /**
     * Добавить заметку (без проверки дублей — см. findDuplicates).
     * @return id заметки или null.
     */
    fun addNote(modelId: Long, deckId: Long, fields: Array<String>, tags: Set<String>): Long? {
        return try {
            val values = ContentValues().apply {
                put(COL_MID, modelId)
                put(COL_FLDS, fields.joinToString(AnkiModel.FIELD_SEPARATOR.toString()))
                put(COL_TAGS, if (tags.isEmpty()) " " else tags.joinToString(" ", prefix = " ", postfix = " "))
            }
            val noteUri = resolver.insert(NOTES_URI, values) ?: return null
            moveCardsToDeck(noteUri, deckId)
            noteUri.lastPathSegment?.toLongOrNull()
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Поиск дублей по первому полю (best effort через поиск Anki).
     * При сбое запроса возвращает false — дубль не должен блокировать добавление.
     */
    fun findDuplicates(modelId: Long, key: String): Boolean = try {
        val selection = "mid:$modelId \"${key.replace("\\", "\\\\").replace("\"", "\\\"")}\""
        resolver.query(NOTES_URI, arrayOf(COL_ID), selection, null, null)?.use { cursor ->
            cursor.count > 0
        } ?: false
    } catch (_: Exception) {
        false
    }

    private fun moveCardsToDeck(noteUri: Uri, deckId: Long) {
        val cardsUri = Uri.withAppendedPath(noteUri, "cards")
        val cursor = try {
            resolver.query(cardsUri, null, null, null, null)
        } catch (_: Exception) {
            null
        } ?: return
        cursor.use {
            while (it.moveToNext()) {
                val ordIndex = it.getColumnIndex(COL_ORD)
                if (ordIndex < 0) continue
                val ord = it.getString(ordIndex)
                val cardUri = Uri.withAppendedPath(cardsUri, ord)
                try {
                    resolver.update(
                        cardUri,
                        ContentValues().apply { put(COL_DECK_ID, deckId) },
                        null,
                        null,
                    )
                } catch (_: Exception) {
                    // Карточка останется в дефолтной колоде модели — не критично.
                }
            }
        }
    }

    private fun queryIdsByName(uri: Uri, idCol: String, nameCol: String, name: String): Long? = try {
        resolver.query(uri, null, null, null, null)?.use { cursor ->
            val idIndex = cursor.getColumnIndex(idCol)
            val nameIndex = cursor.getColumnIndex(nameCol)
            if (idIndex < 0 || nameIndex < 0) return null
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIndex) == name) {
                    return cursor.getLong(idIndex)
                }
            }
            null
        }
    } catch (_: Exception) {
        null
    }

    companion object {
        const val PACKAGE = "com.ichi2.anki"
        const val PERMISSION = "com.ichi2.anki.permission.READ_WRITE_DATABASE"

        private const val AUTHORITY = "com.ichi2.anki.flashcards"
        private val BASE = Uri.parse("content://$AUTHORITY")
        private val NOTES_URI: Uri = Uri.withAppendedPath(BASE, "notes")
        private val MODELS_URI: Uri = Uri.withAppendedPath(BASE, "models")
        private val DECKS_URI: Uri = Uri.withAppendedPath(BASE, "decks")

        private const val COL_ID = "_id"
        private const val COL_NAME = "name"
        private const val COL_MID = "mid"
        private const val COL_FLDS = "flds"
        private const val COL_TAGS = "tags"
        private const val COL_ORD = "ord"
        private const val COL_DECK_ID = "deck_id"

        private const val DECK_COL_ID = "deck_id"
        private const val DECK_COL_NAME = "deck_name"
    }
}
