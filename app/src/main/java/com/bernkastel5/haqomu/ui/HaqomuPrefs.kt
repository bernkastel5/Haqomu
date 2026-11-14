package com.bernkastel5.haqomu.ui

import android.content.Context
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
}
