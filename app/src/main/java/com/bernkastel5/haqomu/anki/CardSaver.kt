package com.bernkastel5.haqomu.anki

import android.content.Context
import com.bernkastel5.haqomu.R

/** Результат попытки сохранить карточку. Карточка уже лежит в PendingStore, если Pending. */
sealed interface SaveOutcome {
    /** Заметка добавлена в AnkiDroid. */
    data object Added : SaveOutcome

    /** AnkiDroid установлен, но не выдано разрешение — нужен запрос у пользователя. */
    data object NeedsPermission : SaveOutcome

    /** Ни слова, ни значения — сохранять нечего. */
    data object Invalid : SaveOutcome

    /** Такая заметка уже есть в колоде. */
    data object Duplicate : SaveOutcome

    /** Карточка сохранена в приложении (AnkiDroid недоступен / нет note type / ошибка). */
    data class Pending(val messageRes: Int) : SaveOutcome
}

/**
 * Единое сохранение карточки: AnkiDroid (ContentProvider) при наличии
 * и доступе, иначе локальное накопление (PendingStore). Используется
 * редактором карточки и быстрым сохранением из попапа — поведение идентично.
 */
object CardSaver {

    fun save(context: Context, deckName: String, card: PendingCard): SaveOutcome {
        if (!PendingCard.isValid(card.expression, card.meaning)) return SaveOutcome.Invalid

        val bridge = AnkiDroidBridge(context)
        if (!bridge.isAnkiDroidInstalled()) {
            PendingStore.add(context, card)
            return SaveOutcome.Pending(R.string.anki_card_pending)
        }
        if (!bridge.hasWritePermission()) return SaveOutcome.NeedsPermission

        val modelId = bridge.findModelId(AnkiModel.MODEL_NAME)
        if (modelId == null) {
            PendingStore.add(context, card)
            return SaveOutcome.Pending(R.string.anki_card_no_model)
        }
        val deckId = bridge.findDeckId(deckName) ?: bridge.createDeck(deckName)
        if (deckId == null) {
            PendingStore.add(context, card)
            return SaveOutcome.Pending(R.string.anki_card_pending)
        }
        if (bridge.findDuplicates(modelId, card.expression)) return SaveOutcome.Duplicate

        val noteId = bridge.addNote(modelId, deckId, card.toFields(), setOf("haqomu"))
        return if (noteId != null) {
            SaveOutcome.Added
        } else {
            PendingStore.add(context, card)
            SaveOutcome.Pending(R.string.anki_card_pending)
        }
    }

    /** Сохранить локально в накопление (используется и при отказе в разрешении). */
    fun saveLocally(context: Context, card: PendingCard, messageRes: Int): SaveOutcome {
        PendingStore.add(context, card)
        return SaveOutcome.Pending(messageRes)
    }
}
