package com.bernkastel5.haqomu.ui

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.bernkastel5.haqomu.R
import com.bernkastel5.haqomu.anki.AnkiConnectClient
import com.bernkastel5.haqomu.anki.AnkiDroidBridge
import com.bernkastel5.haqomu.anki.ApkgPackager
import com.bernkastel5.haqomu.anki.PendingStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * «Настройка Anki» (Ф11, D-009, D-016, D-018): .apkg как базовый путь на обе платформы
 * (набор вшит в APK и делится отсюда же либо скачивается из релизов); AnkiDroid —
 * через ContentProvider; Anki Desktop — AnkiConnect (опционально) с фолбэком на .apkg.
 */
@Composable
fun AnkiSetupScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val deck = remember { mutableStateOf(HaqomuPrefs.ankiDeck(context)) }
    val connectUrl = remember { mutableStateOf(HaqomuPrefs.ankiConnectUrl(context)) }
    val ankidroidInstalled = remember { AnkiDroidBridge(context).isAnkiDroidInstalled() }

    var pendingCount by remember { mutableStateOf(PendingStore.count(context)) }
    var confirmClear by remember { mutableStateOf(false) }
    var connectStatus by remember { mutableStateOf<String?>(null) }
    var connectOk by remember { mutableStateOf<Boolean?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun shareFile(file: File, mimeType: String = "application/octet-stream") {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val share = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            context.startActivity(
                Intent.createChooser(share, context.getString(R.string.anki_share_button))
            )
        } catch (_: Exception) {
            connectStatus = context.getString(R.string.anki_share_error)
            connectOk = false
        }
    }

    fun shareBundledApkg() {
        val file = File(context.cacheDir, "haqomu.apkg")
        try {
            context.resources.openRawResource(R.raw.haqomu).use { input ->
                file.outputStream().use { input.copyTo(it) }
            }
            shareFile(file)
        } catch (_: Exception) {
            connectStatus = context.getString(R.string.anki_share_error)
            connectOk = false
        }
    }

    fun exportPendingApkg() {
        scope.launch {
            busy = true
            val file = withContext(Dispatchers.IO) { ApkgPackager.exportToFile(context) }
            busy = false
            if (file != null) {
                shareFile(file, "application/octet-stream")
            } else {
                connectStatus = context.getString(R.string.anki_export_error)
                connectOk = false
            }
        }
    }

    fun checkConnect() {
        val url = connectUrl.value
        scope.launch {
            busy = true
            connectStatus = null
            val result = withContext(Dispatchers.IO) { AnkiConnectClient.check(url) }
            busy = false
            connectOk = result.ok
            connectStatus = if (result.ok) {
                context.getString(R.string.anki_connect_ok)
            } else {
                context.getString(R.string.anki_connect_failed, result.message)
            }
        }
    }

    fun sendPendingViaConnect() {
        val url = connectUrl.value
        val deckName = HaqomuPrefs.ankiDeck(context)
        scope.launch {
            busy = true
            val (sent, failed) = withContext(Dispatchers.IO) {
                val cards = PendingStore.load(context)
                var ok = 0
                var fail = 0
                for (card in cards) {
                    if (AnkiConnectClient.addNote(url, deckName, card).ok) ok += 1 else fail += 1
                }
                if (fail == 0 && ok > 0) PendingStore.clear(context)
                ok to fail
            }
            busy = false
            pendingCount = PendingStore.count(context)
            connectOk = failed == 0
            connectStatus = if (sent == 0 && failed == 0) {
                context.getString(R.string.anki_connect_empty)
            } else if (failed == 0) {
                context.getString(R.string.anki_connect_sent, sent)
            } else {
                context.getString(R.string.anki_connect_failed_count, failed)
            }
        }
    }

    ScreenScaffold(title = stringResource(R.string.anki_title), onBack = onBack) {
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            Text(
                text = stringResource(
                    if (ankidroidInstalled) R.string.anki_ankidroid_installed
                    else R.string.anki_ankidroid_missing
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = if (ankidroidInstalled) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Text(
                text = stringResource(R.string.anki_intro),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(top = 8.dp),
            )

            Step(1, stringResource(R.string.anki_step1))
            Step(2, stringResource(R.string.anki_step2))
            Step(3, stringResource(R.string.anki_step3))
            Step(4, stringResource(R.string.anki_step4))

            Button(
                onClick = { shareBundledApkg() },
                modifier = Modifier.padding(top = 12.dp),
            ) {
                Text(stringResource(R.string.anki_share_button))
            }
            Text(
                text = stringResource(R.string.anki_apkg_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp, bottom = 20.dp),
            )

            // ---- накопленные карточки (фолбэк без AnkiDroid)
            Text(
                text = stringResource(R.string.anki_pending_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = stringResource(R.string.anki_pending_count, pendingCount),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
            )
            Row {
                Button(onClick = { exportPendingApkg() }, enabled = pendingCount > 0 && !busy) {
                    Text(stringResource(R.string.anki_export_button))
                }
                Spacer(Modifier.padding(horizontal = 4.dp))
                TextButton(
                    onClick = {
                        if (confirmClear) {
                            PendingStore.clear(context)
                            pendingCount = 0
                            confirmClear = false
                        } else {
                            confirmClear = true
                        }
                    },
                    enabled = pendingCount > 0,
                ) {
                    Text(
                        if (confirmClear) {
                            stringResource(R.string.anki_pending_clear_confirm)
                        } else {
                            stringResource(R.string.anki_pending_clear)
                        }
                    )
                }
            }

            // ---- AnkiConnect (опционально, D-016)
            Text(
                text = stringResource(R.string.anki_connect_note),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 20.dp, bottom = 8.dp),
            )
            OutlinedTextField(
                value = connectUrl.value,
                onValueChange = {
                    connectUrl.value = it
                    HaqomuPrefs.setAnkiConnectUrl(context, it)
                    connectStatus = null
                    connectOk = null
                },
                label = { Text(stringResource(R.string.anki_connect_url_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(modifier = Modifier.padding(top = 8.dp)) {
                Button(onClick = { checkConnect() }, enabled = !busy) {
                    Text(stringResource(R.string.anki_connect_check))
                }
                Spacer(Modifier.padding(horizontal = 4.dp))
                TextButton(onClick = { sendPendingViaConnect() }, enabled = !busy && pendingCount > 0) {
                    Text(stringResource(R.string.anki_connect_send))
                }
            }
            connectStatus?.let { status ->
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (connectOk == false) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    modifier = Modifier.padding(top = 6.dp, bottom = 4.dp),
                )
            }

            OutlinedTextField(
                value = deck.value,
                onValueChange = {
                    deck.value = it
                    HaqomuPrefs.setAnkiDeck(context, it)
                },
                label = { Text(stringResource(R.string.anki_deck_label)) },
                supportingText = { Text(stringResource(R.string.anki_deck_hint)) },
                singleLine = true,
                modifier = Modifier.padding(top = 16.dp, bottom = 16.dp),
            )
        }
    }
}

@Composable
private fun Step(number: Int, body: String) {
    Row(modifier = Modifier.padding(top = 14.dp)) {
        Text(
            text = "$number.",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(end = 12.dp),
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}
