package com.bernkastel5.haqomu.ui

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.bernkastel5.haqomu.R

private data class PickedPack(val name: String, val size: Long, val readable: Boolean)

/** Экран «Импорт паков»: фаза 4 добавит проверку структуры и установку. Сейчас: выбор файла и базовая читаемость. */
@Composable
fun ImportPacksScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val picked = remember { mutableStateOf<PickedPack?>(null) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        } catch (_: SecurityException) {
            // не все схемы разрешают удержание права; читаться файл всё равно будет
        }
        var name = "?"
        var size = -1L
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val iName = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val iSize = c.getColumnIndex(OpenableColumns.SIZE)
            if (c.moveToFirst()) {
                name = if (iName >= 0) c.getString(iName) else "?"
                size = if (iSize >= 0) c.getLong(iSize) else -1L
            }
        }
        val readable = try {
            context.contentResolver.openInputStream(uri)?.use { it.read() >= 0 } ?: false
        } catch (_: Exception) {
            false
        }
        picked.value = PickedPack(name, size, readable)
    }

    ScreenScaffold(title = stringResource(R.string.import_title), onBack = onBack) {
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            Text(
                text = stringResource(R.string.import_intro),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )

            PackInfo(stringResource(R.string.import_camel), stringResource(R.string.import_camel_sub))
            PackInfo(stringResource(R.string.import_ksaa), stringResource(R.string.import_ksaa_sub))
            PackInfo(stringResource(R.string.import_kaikki), stringResource(R.string.import_kaikki_sub))
            PackInfo(stringResource(R.string.import_hw), stringResource(R.string.import_hw_sub))

            OutlinedButton(
                onClick = { launcher.launch(arrayOf("*/*")) },
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Text(stringResource(R.string.import_pick))
            }

            val file = picked.value
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Column(Modifier.padding(16.dp)) {
                    if (file == null) {
                        Text(
                            text = stringResource(R.string.import_no_file),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.import_file_name, file.name),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (file.size >= 0) {
                            Text(
                                text = stringResource(
                                    R.string.import_file_size,
                                    formatSize(context, file.size),
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            text = stringResource(
                                if (file.readable) R.string.import_file_readable else R.string.import_file_unreadable
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (file.readable) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.secondary,
                        )
                    }
                    Text(
                        text = stringResource(R.string.import_next_build),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun PackInfo(title: String, body: String) {
    Column(modifier = Modifier.padding(bottom = 16.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

private fun formatSize(context: android.content.Context, bytes: Long): String {
    val mb = bytes / 1048576.0
    return if (mb >= 1) context.getString(R.string.size_mb, mb) else context.getString(R.string.size_kb, bytes / 1024)
}
