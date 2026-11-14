package com.bernkastel5.haqomu.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bernkastel5.haqomu.R
import com.bernkastel5.haqomu.ui.theme.ThemeMode

/** Настройки: тема, размер арабского текста (Ф15), плавающая кнопка (пока выключена), мастер. */
@Composable
fun SettingsScreen(
    themeMode: ThemeMode,
    onThemeMode: (ThemeMode) -> Unit,
    onOpenAccessibility: () -> Unit,
    onReplaySetup: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val textScale = remember { mutableStateOf(HaqomuPrefs.arabicTextScale(context)) }
    val ankiEditMode = remember { mutableStateOf(HaqomuPrefs.ankiEditBeforeSave(context)) }

    ScreenScaffold(title = stringResource(R.string.settings_title), onBack = onBack) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()),
        ) {
            SectionLabel(stringResource(R.string.settings_theme))
            Row {
                ThemeButton(stringResource(R.string.theme_system), themeMode == ThemeMode.System) {
                    onThemeMode(ThemeMode.System)
                }
                ThemeButton(stringResource(R.string.theme_light), themeMode == ThemeMode.Light) {
                    onThemeMode(ThemeMode.Light)
                }
                ThemeButton(stringResource(R.string.theme_dark), themeMode == ThemeMode.Dark) {
                    onThemeMode(ThemeMode.Dark)
                }
            }

            Spacer(Modifier.height(24.dp))
            SectionLabel(stringResource(R.string.settings_text_size))
            Row {
                ScaleButton(stringResource(R.string.text_size_normal), textScale.value == 1.0f) {
                    textScale.value = 1.0f
                    HaqomuPrefs.setArabicTextScale(context, 1.0f)
                }
                ScaleButton(stringResource(R.string.text_size_large), textScale.value == 1.15f) {
                    textScale.value = 1.15f
                    HaqomuPrefs.setArabicTextScale(context, 1.15f)
                }
                ScaleButton(stringResource(R.string.text_size_xlarge), textScale.value == 1.3f) {
                    textScale.value = 1.3f
                    HaqomuPrefs.setArabicTextScale(context, 1.3f)
                }
            }
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
            ) {
                androidx.compose.runtime.CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Text(
                        text = stringResource(R.string.text_size_sample),
                        fontFamily = FontFamily.Serif,
                        fontSize = (20 * textScale.value).sp,
                        lineHeight = (30 * textScale.value).sp,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            Text(
                text = stringResource(R.string.text_size_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenAccessibility),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.settings_floating),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Text(
                        text = stringResource(R.string.settings_floating_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Text(
                    text = "›",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp),
                )
            }

            Spacer(Modifier.height(24.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.settings_anki_edit_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Text(
                        text = stringResource(R.string.settings_anki_edit_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Switch(
                    checked = ankiEditMode.value,
                    onCheckedChange = { value ->
                        ankiEditMode.value = value
                        HaqomuPrefs.setAnkiEditBeforeSave(context, value)
                    },
                )
            }

            Spacer(Modifier.height(24.dp))
            OutlinedButton(onClick = onReplaySetup) {
                Text(stringResource(R.string.settings_replay))
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

@Composable
private fun ThemeButton(label: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) {
        Button(onClick = onClick, modifier = Modifier.padding(end = 8.dp)) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick, modifier = Modifier.padding(end = 8.dp)) { Text(label) }
    }
}

@Composable
private fun ScaleButton(label: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) {
        Button(onClick = onClick, modifier = Modifier.padding(end = 8.dp)) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick, modifier = Modifier.padding(end = 8.dp)) { Text(label) }
    }
}
