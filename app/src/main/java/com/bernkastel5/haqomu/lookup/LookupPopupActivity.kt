package com.bernkastel5.haqomu.lookup

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.bernkastel5.haqomu.MainActivity
import com.bernkastel5.haqomu.service.SelectionHub
import com.bernkastel5.haqomu.ui.HaqomuPrefs
import com.bernkastel5.haqomu.ui.LookupPopupScreen
import com.bernkastel5.haqomu.ui.theme.HaqomuTheme

/**
 * Попап словаря (Ф1, Ф3, Ф9): открывается из меню выделения (ACTION_PROCESS_TEXT)
 * и из плавающей кнопки сервиса доступности.
 *
 * Транслюцентная Activity без Recents и с singleTop: разбирает EXTRA_PROCESS_TEXT
 * либо готовые слово и предложение из [EXTRA_CONTEXT_SENTENCE] (путь сервиса,
 * контекст уже вырезан — читать экран повторно не нужно), работу движка выполняет
 * на фоне (Dispatchers.IO), первый кадр — скелетон.
 * На пути PROCESS_TEXT предложение берётся из последнего выделения сервиса, если оно
 * относится к тому же слову и свежо (Ф3); без сервиса попап работает как раньше.
 * Повторный вызов в открытый попап (singleTop) обновляет запрос через [onNewIntent] —
 * второго окна нет и статей от прошлого слова тоже.
 * Тап мимо попапа и «Назад» закрывают его; словарных данных в APK нет.
 */
class LookupPopupActivity : ComponentActivity() {

    /** Текущий запрос: слово и контекст. Меняется на onNewIntent без пересоздания окна. */
    private var request by mutableStateOf<LookupRequest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        request = readRequest(intent)
        val themeMode = HaqomuPrefs.themeMode(this)
        setContent {
            HaqomuTheme(themeMode = themeMode) {
                val current = request
                LookupPopupScreen(
                    rawText = current?.raw,
                    sentence = current?.sentence.orEmpty(),
                    onClose = { finish() },
                    onOpenApp = {
                        startActivity(Intent(this, MainActivity::class.java))
                        finish()
                    },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        request = readRequest(intent)
    }

    /**
     * Контекст для попапа. Прямой путь (кнопка сервиса) приносит предложение в интенте.
     * На пути PROCESS_TEXT берём свежее выделение сервиса, если это то же слово (Ф3);
     * иначе — пусто, и попап показывает словарный порядок (штатная деградация).
     */
    private fun readRequest(intent: Intent?): LookupRequest {
        val raw = intent?.getStringExtra(Intent.EXTRA_PROCESS_TEXT)
            ?: intent?.getStringExtra(Intent.EXTRA_PROCESS_TEXT_READONLY)
        val explicit = intent?.getStringExtra(EXTRA_CONTEXT_SENTENCE)
        val sentence = if (!explicit.isNullOrBlank()) {
            explicit.trim()
        } else {
            val word = LookupQuery.extract(raw) ?: return LookupRequest(raw, "")
            SelectionHub.recentFor(word)?.sentence.orEmpty()
        }
        return LookupRequest(raw, sentence)
    }

    /**
     * Запрос попапа. [raw] — текст из интента (может содержать лишние слова),
     * [sentence] — предложение вокруг выделения, уже вырезанное.
     */
    data class LookupRequest(val raw: String?, val sentence: String)

    companion object {
        /** Предложение вокруг выделения, вырезанное сервисом доступности (Ф3). */
        const val EXTRA_CONTEXT_SENTENCE = "haqomu.lookup.sentence"

        /** Интент от плавающей кнопки: слово и предложение уже готовы. */
        fun newIntent(context: Context, word: String, sentence: String): Intent =
            Intent(context, LookupPopupActivity::class.java).apply {
                putExtra(Intent.EXTRA_PROCESS_TEXT, word)
                putExtra(EXTRA_CONTEXT_SENTENCE, sentence)
            }
    }
}
