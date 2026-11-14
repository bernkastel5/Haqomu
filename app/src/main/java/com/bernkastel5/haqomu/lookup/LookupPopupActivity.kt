package com.bernkastel5.haqomu.lookup

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.bernkastel5.haqomu.ui.HaqomuPrefs
import com.bernkastel5.haqomu.ui.LookupPopupScreen
import com.bernkastel5.haqomu.MainActivity
import com.bernkastel5.haqomu.ui.theme.HaqomuTheme

/**
 * Попап словаря (Ф1, Ф9): пункт «Словарь» в меню выделения текста (ACTION_PROCESS_TEXT).
 *
 * Транслюцентная Activity без Recents и с singleTop: разбирает EXTRA_PROCESS_TEXT,
 * работу движка выполняет на фоне (Dispatchers.IO), первый кадр — скелетон.
 * Тап мимо попапа и «Назад» закрывают его; словарных данных в APK нет.
 */
class LookupPopupActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val raw = intent.getStringExtra(Intent.EXTRA_PROCESS_TEXT)
            ?: intent.getStringExtra(Intent.EXTRA_PROCESS_TEXT_READONLY)
        val themeMode = HaqomuPrefs.themeMode(this)
        setContent {
            HaqomuTheme(themeMode = themeMode) {
                LookupPopupScreen(
                    rawText = raw,
                    onClose = { finish() },
                    onOpenApp = {
                        startActivity(Intent(this, MainActivity::class.java))
                        finish()
                    },
                )
            }
        }
    }
}
