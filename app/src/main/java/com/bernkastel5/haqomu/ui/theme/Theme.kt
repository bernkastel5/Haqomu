package com.bernkastel5.haqomu.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

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
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val scheme = if (darkTheme) DarkScheme else LightScheme
    MaterialTheme(
        colorScheme = scheme,
        content = content,
    )
}
