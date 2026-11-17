package com.bernkastel5.haqomu

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.bernkastel5.haqomu.ui.HaqomuPrefs
import com.bernkastel5.haqomu.ui.ReaderScreen
import com.bernkastel5.haqomu.ui.theme.HaqomuTheme

/**
 * Читалка как отдельное окно (Ф14): сюда приходит книга из «Поделиться» или
 * «Открыть с помощью» другого приложения.
 *
 * Почему отдельная Activity, а не только экран внутри [MainActivity]: файловые
 * менеджеры и браузеры отдают книгу как `content://` на старт приложения, и
 * пользователь ждёт увидеть книгу сразу, а не главный экран. Экран читалки —
 * тот же самый ([ReaderScreen]), кода чтения нет двух версий.
 *
 * Если ссылка не файл (нет прав на чтение, пустой uri) — открывается читалка без
 * книги, с честным предложением выбрать файл.
 */
class ReaderActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val themeMode = HaqomuPrefs.themeMode(this)
        setContent {
            HaqomuTheme(themeMode = themeMode) {
                // Ссылку на книгу читалка берёт из интента окна сама: так же
                // работает повторный вызов из другого приложения (onNewIntent).
                ReaderScreen(onBack = { finish() })
            }
        }
    }
}
