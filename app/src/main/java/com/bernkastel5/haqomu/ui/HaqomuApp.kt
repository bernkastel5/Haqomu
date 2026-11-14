package com.bernkastel5.haqomu.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.bernkastel5.haqomu.ui.theme.HaqomuTheme

/** Экраны приложения. Навигация через состояние, без внешних зависимостей. */
enum class Screen { Home, Import, Anki, Settings, Accessibility, About, PopupPreview }

@Composable
fun HaqomuApp() {
    val context = LocalContext.current
    var themeMode by remember { mutableStateOf(HaqomuPrefs.themeMode(context)) }
    HaqomuTheme(themeMode = themeMode) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            var setupDone by remember { mutableStateOf(HaqomuPrefs.isSetupDone(context)) }
            if (!setupDone) {
                SetupWizardScreen(onFinish = {
                    HaqomuPrefs.setSetupDone(context, true)
                    setupDone = true
                })
                return@Surface
            }
            var screen by remember { mutableStateOf(Screen.Home) }
            if (screen != Screen.Home) {
                BackHandler { screen = Screen.Home }
            }
            when (screen) {
                Screen.Home -> HomeScreen(onOpen = { screen = it })
                Screen.Import -> ImportPacksScreen(onBack = { screen = Screen.Home })
                Screen.Anki -> AnkiSetupScreen(onBack = { screen = Screen.Home })
                Screen.Settings -> SettingsScreen(
                    themeMode = themeMode,
                    onThemeMode = { mode ->
                        HaqomuPrefs.setThemeMode(context, mode)
                        themeMode = mode
                    },
                    onOpenAccessibility = { screen = Screen.Accessibility },
                    onReplaySetup = {
                        HaqomuPrefs.setSetupDone(context, false)
                        setupDone = false
                    },
                    onBack = { screen = Screen.Home },
                )
                Screen.Accessibility -> AccessibilityScreen(onBack = { screen = Screen.Home })
                Screen.About -> AboutScreen(onBack = { screen = Screen.Home })
                Screen.PopupPreview -> PopupPreviewScreen(onBack = { screen = Screen.Home })
            }
        }
    }
}
