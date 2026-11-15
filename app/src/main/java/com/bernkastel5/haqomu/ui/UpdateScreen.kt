package com.bernkastel5.haqomu.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.bernkastel5.haqomu.R
import com.bernkastel5.haqomu.update.PrefsBridge
import com.bernkastel5.haqomu.update.UpdateChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * «Обновление» (Ф16, ADR-0010): сравнение с релизом `apk-latest`, скачивание APK и передача
 * его системному установщику. Данные пользователя (паки, настройки, очередь Anki) лежат вне
 * APK и при обновлении «поверх» сохраняются.
 *
 * Отдельного сервера нет: приложение читает только `BUILD.txt` (номер версии) и APK из того же
 * релиза, что публикует CI. Ничего о пользователе не отправляется.
 */
@Composable
fun UpdateScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val bridge = remember {
        object : PrefsBridge {
            override fun checkedAt(): Long = HaqomuPrefs.updateCheckedAt(context)
            override fun setCheckedAt(value: Long) = HaqomuPrefs.setUpdateCheckedAt(context, value)
        }
    }

    val current = remember { UpdateChecker.currentVersion(context) }
    var info by remember { mutableStateOf<UpdateChecker.Info?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0L to -1L) }
    var message by remember { mutableStateOf<String?>(null) }

    fun check(force: Boolean) {
        if (busy) return
        busy = true
        error = null
        message = null
        scope.launch {
            val result = withContext(Dispatchers.IO) { UpdateChecker.check(context, force, bridge) }
            busy = false
            when (result) {
                is UpdateChecker.Result.Found -> {
                    info = result.info
                    HaqomuPrefs.setUpdateSeenCode(context, result.info.availableCode ?: 0)
                }
                UpdateChecker.Result.UpToDate -> info = null
                is UpdateChecker.Result.Failed -> error = result.reason
            }
        }
    }

    fun install() {
        if (busy) return
        busy = true
        error = null
        message = null
        progress = 0L to -1L
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    val apk: File = UpdateChecker.downloadApk(context) { done, total ->
                        progress = done to total
                    }
                    apk
                }
            }
            busy = false
            outcome.onSuccess { apk ->
                val launched = UpdateChecker.install(context, apk)
                if (launched) {
                    HaqomuPrefs.setInstallAttempts(context, 0)
                    message = context.getString(R.string.update_installer_opened)
                } else {
                    // Нет разрешения «установка из неизвестных источников» — открыли настройки.
                    val attempts = HaqomuPrefs.installAttempts(context) + 1
                    HaqomuPrefs.setInstallAttempts(context, attempts)
                    message = context.getString(R.string.update_need_permission)
                }
            }.onFailure { error = it.message ?: it.javaClass.simpleName }
        }
    }

    ScreenScaffold(title = stringResource(R.string.update_title), onBack = onBack) {
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            Text(
                text = stringResource(R.string.update_intro),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )

            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.update_current, current.second, current.first),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    info?.let { found ->
                        Text(
                            text = stringResource(
                                R.string.update_available,
                                found.availableText ?: "",
                                found.availableCode ?: 0,
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                        found.commit?.let { commit ->
                            Text(
                                text = stringResource(R.string.update_commit, commit),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }
                }
            }

            Row(modifier = Modifier.padding(top = 16.dp)) {
                OutlinedButton(onClick = { check(true) }, enabled = !busy) {
                    Text(stringResource(R.string.update_check))
                }
                if (info?.hasUpdate == true) {
                    Button(onClick = { install() }, enabled = !busy, modifier = Modifier.padding(start = 8.dp)) {
                        Text(stringResource(R.string.update_install))
                    }
                }
            }

            if (busy) {
                Spacer(Modifier.height(12.dp))
                val (done, total) = progress
                Text(
                    text = if (total > 0) {
                        stringResource(R.string.update_progress, (done * 100 / total).toInt())
                    } else {
                        stringResource(R.string.update_working)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            message?.let { text ->
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
            error?.let { text ->
                Text(
                    text = stringResource(R.string.model_failed, text),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(top = 10.dp),
                )
                CopyActions(text = text, modifier = Modifier.padding(top = 0.dp))
            }

            Text(
                text = stringResource(R.string.update_keeps_data),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
            )
            Text(
                text = UpdateChecker.releasePage(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 24.dp),
            )
        }
    }
}
