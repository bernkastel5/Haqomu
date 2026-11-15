package com.bernkastel5.haqomu.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.bernkastel5.haqomu.R
import com.bernkastel5.haqomu.service.AccessibilityMode
import com.bernkastel5.haqomu.service.AccessibilityStatus

/**
 * «Доступ к экрану» (Ф2, Ф3, TZ §8): режим сервиса доступности (выключен / плавающая
 * кнопка / только контекст), состояние системного разрешения и явное уведомление
 * о том, что сервис читает и чего не делает.
 *
 * Приложение не включает сервис за пользователя: только объясняет и открывает
 * системный экран. Плавающая кнопка выключена по умолчанию; отдельного разрешения
 * «поверх других приложений» не требуется — окно рисуется как окно доступности.
 */
@Composable
fun AccessibilityScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    // Активность — это LifecycleOwner; через него узнаём о возвращении из системных настроек.
    val lifecycleOwner = context as? LifecycleOwner

    var mode by remember { mutableStateOf(HaqomuPrefs.accessibilityMode(context)) }
    var systemEnabled by remember { mutableStateOf(AccessibilityStatus.isEnabled(context)) }
    var noticeFor by remember { mutableStateOf<AccessibilityMode?>(null) }

    // Возвращаемся из системных настроек — перечитываем состояние, ничего не кэшируем.
    DisposableEffect(lifecycleOwner) {
        val owner = lifecycleOwner ?: return@DisposableEffect onDispose { }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                systemEnabled = AccessibilityStatus.isEnabled(context)
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }

    ScreenScaffold(title = stringResource(R.string.settings_accessibility_screen), onBack = onBack) {
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            Text(
                text = stringResource(R.string.accessibility_mode_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = stringResource(R.string.accessibility_mode_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ModeButton(stringResource(R.string.accessibility_mode_off), mode == AccessibilityMode.Off) {
                    selectMode(context, AccessibilityMode.Off) { mode = it }
                }
                ModeButton(stringResource(R.string.accessibility_mode_floating), mode == AccessibilityMode.Floating) {
                    selectMode(context, AccessibilityMode.Floating) { chosen ->
                        mode = chosen
                        // Первое включение: явное уведомление о чтении экрана (TZ §8).
                        if (!HaqomuPrefs.floatingNoticeSeen(context)) noticeFor = chosen
                    }
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 8.dp),
            ) {
                ModeButton(stringResource(R.string.accessibility_mode_context), mode == AccessibilityMode.Context) {
                    selectMode(context, AccessibilityMode.Context) { mode = it }
                }
            }

            Spacer(Modifier.height(20.dp))
            StatusRow(
                text = if (systemEnabled) {
                    stringResource(R.string.accessibility_system_on)
                } else {
                    stringResource(R.string.accessibility_system_off)
                },
                positive = systemEnabled,
            )
            if (!systemEnabled) {
                OutlinedButton(
                    onClick = { context.startActivity(AccessibilityStatus.settingsIntent()) },
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    Text(stringResource(R.string.accessibility_open_settings))
                }
            }

            if (!mode.isEnabled) {
                Text(
                    text = stringResource(R.string.accessibility_disabled_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }

            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.accessibility_notice_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(R.string.accessibility_notice_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (noticeFor != null) {
        AlertDialog(
            onDismissRequest = {
                HaqomuPrefs.setFloatingNoticeSeen(context, true)
                noticeFor = null
            },
            title = { Text(stringResource(R.string.accessibility_notice_title)) },
            text = { Text(stringResource(R.string.accessibility_notice_body)) },
            confirmButton = {
                Button(onClick = {
                    HaqomuPrefs.setFloatingNoticeSeen(context, true)
                    noticeFor = null
                    if (!AccessibilityStatus.isEnabled(context)) {
                        context.startActivity(AccessibilityStatus.settingsIntent())
                    }
                }) {
                    Text(stringResource(R.string.accessibility_notice_ok))
                }
            },
        )
    }
}

/**
 * Выбор режима: сохраняет настройку и подсказывает системный шаг.
 * Включение сервиса остаётся действием пользователя в системных настройках.
 */
private fun selectMode(
    context: android.content.Context,
    target: AccessibilityMode,
    onApplied: (AccessibilityMode) -> Unit,
) {
    HaqomuPrefs.setAccessibilityMode(context, target)
    onApplied(target)
}

@Composable
private fun ModeButton(label: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) {
        Button(onClick = onClick) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick) { Text(label) }
    }
}

@Composable
private fun StatusRow(text: String, positive: Boolean) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = if (positive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
    )
}
