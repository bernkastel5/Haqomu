package com.bernkastel5.haqomu.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import android.provider.OpenableColumns
import com.bernkastel5.haqomu.R
import com.bernkastel5.haqomu.camel.AndroidCamelPackWriter
import com.bernkastel5.haqomu.camel.MorphologyPack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Кнопка «Подготовить морфологию» (D-019): официальный архив CAMeL-Lab или сам morphology.db
 * конвертируется на устройстве в camel_pack.sqlite (ADR-0003). Ничего никуда не отправляется.
 */
@Composable
fun MorphologySection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var working by remember { mutableStateOf(false) }
    var linesRead by remember { mutableLongStateOf(0L) }
    var error by remember { mutableStateOf<String?>(null) }
    var done by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf(readStatus(context)) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        var name: String? = null
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) name = c.getString(i)
        }
        working = true
        error = null
        done = false
        linesRead = 0L
        scope.launch(Dispatchers.IO) {
            try {
                val stream = context.contentResolver.openInputStream(uri)
                    ?: throw IllegalStateException("не удалось открыть файл")
                stream.use { input ->
                    val out = MorphologyPack.file(context).absolutePath
                    AndroidCamelPackWriter.convert(
                        input = input,
                        displayName = name,
                        outPath = out,
                        onProgress = { n -> linesRead = n },
                    )
                }
                done = true
                status = readStatus(context)
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
            } finally {
                working = false
            }
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.morph_title),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 20.dp, bottom = 8.dp),
        )
        val current = status
        Text(
            text = if (current == null) {
                stringResource(R.string.morph_status_missing)
            } else {
                stringResource(
                    R.string.morph_status_ready,
                    current.stems,
                    formatSize(context, current.sizeBytes),
                    current.buildDate,
                )
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = stringResource(R.string.morph_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp, bottom = 12.dp),
        )
        OutlinedButton(
            onClick = { launcher.launch(arrayOf("*/*")) },
            enabled = !working,
        ) {
            Text(stringResource(R.string.morph_pick))
        }
        if (working) {
            CircularProgressIndicator(
                modifier = Modifier.padding(top = 12.dp),
            )
            Text(
                text = stringResource(R.string.morph_working, linesRead),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        if (done && !working) {
            Text(
                text = stringResource(R.string.morph_done),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
        val failure = error
        if (failure != null && !working) {
            Text(
                text = stringResource(R.string.morph_failed, failure),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

private fun readStatus(context: android.content.Context) = MorphologyPack.status(context)

private fun formatSize(context: android.content.Context, bytes: Long): String {
    val mb = bytes / 1048576.0
    return if (mb >= 1) context.getString(R.string.size_mb, mb) else context.getString(R.string.size_kb, bytes / 1024)
}
