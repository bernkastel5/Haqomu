package com.bernkastel5.haqomu.ui

import android.content.Context
import com.bernkastel5.haqomu.service.AccessibilityMode
import com.bernkastel5.haqomu.ui.theme.ThemeMode

/**
 * Настройки приложения. Хранятся локально в SharedPreferences; ничего не покидает устройство.
 * Ключи читаются Compose-экранами через remember { ... } при входе на экран.
 */
object HaqomuPrefs {
    private const val FILE = "haqomu"
    private const val KEY_SETUP_DONE = "setup_done"
    private const val KEY_THEME = "theme_mode"
    private const val KEY_TEXT_SCALE = "arabic_text_scale"
    private const val KEY_DECK = "anki_deck"
    private const val KEY_CONNECT_URL = "anki_connect_url"
    private const val KEY_ANKI_EDIT_BEFORE_SAVE = "anki_edit_before_save"
    private const val KEY_ACCESSIBILITY = "accessibility_mode"
    private const val KEY_FLOATING_NOTICE_SEEN = "floating_notice_seen"
    private const val KEY_SENSE_MODEL = "sense_model_enabled"
    private const val KEY_MODEL_URL = "sense_model_url"

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun isSetupDone(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SETUP_DONE, false)

    fun setSetupDone(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_SETUP_DONE, value).apply()

    fun themeMode(context: Context): ThemeMode =
        when (prefs(context).getString(KEY_THEME, "system")) {
            "light" -> ThemeMode.Light
            "dark" -> ThemeMode.Dark
            else -> ThemeMode.System
        }

    fun setThemeMode(context: Context, mode: ThemeMode) =
        prefs(context).edit().putString(
            KEY_THEME,
            when (mode) {
                ThemeMode.System -> "system"
                ThemeMode.Light -> "light"
                ThemeMode.Dark -> "dark"
            }
        ).apply()

    fun arabicTextScale(context: Context): Float =
        prefs(context).getFloat(KEY_TEXT_SCALE, 1.0f)

    fun setArabicTextScale(context: Context, scale: Float) =
        prefs(context).edit().putFloat(KEY_TEXT_SCALE, scale).apply()

    fun ankiDeck(context: Context): String =
        prefs(context).getString(KEY_DECK, "Haqomu") ?: "Haqomu"

    fun setAnkiDeck(context: Context, deck: String) =
        prefs(context).edit().putString(KEY_DECK, deck.trim().ifEmpty { "Haqomu" }).apply()

    fun ankiConnectUrl(context: Context): String =
        prefs(context).getString(KEY_CONNECT_URL, "http://localhost:8765") ?: "http://localhost:8765"

    fun setAnkiConnectUrl(context: Context, url: String) =
        prefs(context).edit().putString(KEY_CONNECT_URL, url.trim().ifEmpty { "http://localhost:8765" }).apply()

    /** false (по умолчанию): кнопка «В Anki» сохраняет сразу. true: открывать редактор. */
    fun ankiEditBeforeSave(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ANKI_EDIT_BEFORE_SAVE, false)

    fun setAnkiEditBeforeSave(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_ANKI_EDIT_BEFORE_SAVE, value).apply()

    /**
     * Режим сервиса доступности (Ф2): выключен по умолчанию. Значение — настройка
     * приложения; сам сервис включается только в системных настройках.
     */
    fun accessibilityMode(context: Context): AccessibilityMode =
        AccessibilityMode.fromStored(prefs(context).getString(KEY_ACCESSIBILITY, "off"))

    fun setAccessibilityMode(context: Context, mode: AccessibilityMode) =
        prefs(context).edit().putString(KEY_ACCESSIBILITY, AccessibilityMode.storedValue(mode)).apply()

    /** Показано ли объяснение при первом включении плавающей кнопки (TZ §8). */
    fun floatingNoticeSeen(context: Context): Boolean =
        prefs(context).getBoolean(KEY_FLOATING_NOTICE_SEEN, false)

    fun setFloatingNoticeSeen(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_FLOATING_NOTICE_SEEN, value).apply()

    /**
     * Включена ли модель разрешения смыслов (Ф10). По умолчанию **выключена**:
     * модель опциональна, скачивается отдельно (D-014) и меняет порядок смыслов,
     * только когда уверена (порог 0.3).
     */
    fun senseModelEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SENSE_MODEL, false)

    fun setSenseModelEnabled(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_SENSE_MODEL, value).apply()

    /** Адрес бандла модели для кнопки «Скачать» (по умолчанию — пусто, файл вручную). */
    fun senseModelUrl(context: Context): String =
        prefs(context).getString(KEY_MODEL_URL, "") ?: ""

    fun setSenseModelUrl(context: Context, url: String) =
        prefs(context).edit().putString(KEY_MODEL_URL, url.trim()).apply()
}
