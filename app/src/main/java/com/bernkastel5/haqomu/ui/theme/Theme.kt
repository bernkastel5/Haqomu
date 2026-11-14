package com.bernkastel5.haqomu.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Как приложение выбирает тему. Хранится в настройках (HaqomuPrefs). */
enum class ThemeMode { System, Light, Dark }

private val LightScheme = lightColorScheme(
    primary = DeepGreen,
    onPrimary = OnDeepGreen,
    primaryContainer = Color(0xFFDDEBE6),
    onPrimaryContainer = DeepGreenPressed,
    secondary = Terracotta,
    onSecondary = OnDeepGreen,
    background = Paper,
    onBackground = Ink,
    surface = PaperElevated,
    onSurface = Ink,
    surfaceVariant = Color(0xFFEDE9E0),
    onSurfaceVariant = InkMuted,
)

private val DarkScheme = darkColorScheme(
    primary = NightGreen,
    onPrimary = NightOnGreen,
    primaryContainer = Color(0xFF173B33),
    onPrimaryContainer = NightGreen,
    secondary = NightTerracotta,
    onSecondary = NightOnGreen,
    background = NightBackground,
    onBackground = NightInk,
    surface = NightSurface,
    onSurface = NightInk,
    surfaceVariant = Color(0xFF252C2E),
    onSurfaceVariant = NightMuted,
)

@Composable
fun HaqomuTheme(
    themeMode: ThemeMode = ThemeMode.System,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeMode) {
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
        ThemeMode.System -> isSystemInDarkTheme()
    }
    val scheme = if (darkTheme) DarkScheme else LightScheme
    MaterialTheme(
        colorScheme = scheme,
        content = content,
    )
}
