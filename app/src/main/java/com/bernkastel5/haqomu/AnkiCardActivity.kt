package com.bernkastel5.haqomu

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.bernkastel5.haqomu.ui.AnkiCardScreen
import com.bernkastel5.haqomu.ui.HaqomuPrefs
import com.bernkastel5.haqomu.ui.theme.HaqomuTheme

/**
 * Экран карточки Anki (Ф11): TZ §3.3 — вызов из попапа для выбранного смысла,
 * поля предзаполнены, пользователь правит и сохраняет; заметка уходит в AnkiDroid
 * (ContentProvider) либо сохраняется в приложении для экспорта .apkg.
 */
class AnkiCardActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val raw = intent
        val themeMode = HaqomuPrefs.themeMode(this)
        setContent {
            HaqomuTheme(themeMode = themeMode) {
                AnkiCardScreen(
                    initialExpression = raw.getStringExtra(EXTRA_EXPRESSION).orEmpty(),
                    initialTashkeel = raw.getStringExtra(EXTRA_TASHKEEL).orEmpty(),
                    initialMeaning = raw.getStringExtra(EXTRA_MEANING).orEmpty(),
                    initialSentence = raw.getStringExtra(EXTRA_SENTENCE).orEmpty(),
                    initialRoot = raw.getStringExtra(EXTRA_ROOT).orEmpty(),
                    initialDictionary = raw.getStringExtra(EXTRA_DICTIONARY).orEmpty(),
                    onClose = { finish() },
                )
            }
        }
    }

    companion object {
        const val EXTRA_EXPRESSION = "haqomu.anki.expression"
        const val EXTRA_TASHKEEL = "haqomu.anki.tashkeel"
        const val EXTRA_MEANING = "haqomu.anki.meaning"
        const val EXTRA_SENTENCE = "haqomu.anki.sentence"
        const val EXTRA_ROOT = "haqomu.anki.root"
        const val EXTRA_DICTIONARY = "haqomu.anki.dictionary"

        /** Intent для открытия редактора карточки из попапа. */
        fun newIntent(
            context: android.content.Context,
            expression: String,
            tashkeel: String,
            meaning: String,
            sentence: String,
            root: String,
            dictionary: String,
        ): Intent = Intent(context, AnkiCardActivity::class.java).apply {
            putExtra(EXTRA_EXPRESSION, expression)
            putExtra(EXTRA_TASHKEEL, tashkeel)
            putExtra(EXTRA_MEANING, meaning)
            putExtra(EXTRA_SENTENCE, sentence)
            putExtra(EXTRA_ROOT, root)
            putExtra(EXTRA_DICTIONARY, dictionary)
        }
    }
}
