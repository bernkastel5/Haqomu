package com.bernkastel5.haqomu.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.bernkastel5.haqomu.R
import com.bernkastel5.haqomu.gloss.BundleCheck
import com.bernkastel5.haqomu.gloss.BundleMeta
import com.bernkastel5.haqomu.gloss.ModelStore
import com.bernkastel5.haqomu.gloss.SenseModelCache
import com.bernkastel5.haqomu.pack.PackDownloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * «Модель смыслов» (Ф10, Ф13): опциональная загрузка ArabGlossBERT (int8 ~135 МБ,
 * fp16 ~270 МБ), прогресс, удаление. В APK весов нет и не будет (TZ §5.1, CC BY-SA 4.0):
 * пользователь качает бандл сам — по кнопке с указанного адреса или файлом с устройства.
 *
 * Порог уверенности 0.3 и правило «порядок смыслов внутри статьи, статьи не двигаются» —
 * в ADR-0008; здесь только установка и статус. Пока модель не установлена или выключена,
 * попап работает по словарному порядку — это штатный режим, а не ошибка.
 */
@Composable
fun ModelScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var installed by remember { mutableStateOf(ModelStore.isInstalled(context)) }
    var meta by remember { mutableStateOf(ModelStore.meta(context)) }
    var size by remember { mutableLongStateOf(ModelStore.sizeBytes(context)) }
    var enabled by remember { mutableStateOf(HaqomuPrefs.senseModelEnabled(context)) }
    var url by remember { mutableStateOf(HaqomuPrefs.senseModelUrl(context)) }
    var busy by remember { mutableStateOf(false) }
    var downloaded by remember { mutableLongStateOf(0L) }
    var total by remember { mutableLongStateOf(-1L) }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmRemove by remember { mutableStateOf(false) }

    fun refresh() {
        installed = ModelStore.isInstalled(context)
        meta = ModelStore.meta(context)
        size = ModelStore.sizeBytes(context)
        SenseModelCache.invalidate()
    }

    fun finish(outcome: BundleCheck) {
        when (outcome) {
            is BundleCheck.Ok -> {
                refresh()
                message = context.getString(R.string.model_installed, outcome.meta.model)
                HaqomuPrefs.setSenseModelEnabled(context, true)
                enabled = true
            }
            is BundleCheck.Failed -> error = outcome.reason
        }
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        message = null
        error = null
        downloaded = 0L
        total = -1L
        scope.launch {
            // Тяжёлое — в IO; состояние Compose меняется уже на главном потоке.
            // Деструктуризация пары — в локальные переменные без componentN на Pair в Compose.
            val io = withContext(Dispatchers.IO) {
                var extra: String? = null
                val result = runCatching {
                    val name = displayName(context, uri) ?: "model.zip"
                    val tmp = File(context.cacheDir, "model_import_${System.currentTimeMillis()}_$name")
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        tmp.outputStream().use { out -> input.copyTo(out) }
                    } ?: throw IllegalStateException("файл не открылся")
                    extra = formatSize(context, tmp.length())
                    val installed = if (name.endsWith(".zip", ignoreCase = true)) {
                        ModelStore.installFromZip(context, tmp)
                    } else {
                        BundleCheck.Failed(context.getString(R.string.model_need_zip))
                    }
                    tmp.delete()
                    installed
                }.getOrElse { BundleCheck.Failed(it.message ?: it.javaClass.simpleName) }
                result to extra
            }
            val outcome = io.first
            val note = io.second
            busy = false
            downloaded = 0L
            finish(outcome)
            if (note != null && message != null) message = "$message ($note)"
        }
    }

    fun download() {
        val address = url.trim()
        if (address.isEmpty()) {
            error = context.getString(R.string.model_need_url)
            return
        }
        busy = true
        message = null
        error = null
        downloaded = 0L
        total = -1L
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    HaqomuPrefs.setSenseModelUrl(context, address)
                    val dest = File(context.cacheDir, "model_download.zip")
                    PackDownloader.download(address, dest) { done, all ->
                        downloaded = done
                        total = all
                    }
                    val result = ModelStore.installFromZip(context, dest)
                    dest.delete()
                    result
                }.getOrElse { BundleCheck.Failed(it.message ?: it.javaClass.simpleName) }
            }
            busy = false
            finish(outcome)
        }
    }

    ScreenScaffold(title = stringResource(R.string.model_title), onBack = onBack) {
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            Text(
                text = stringResource(R.string.model_intro),
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
                        text = stringResource(R.string.model_status_label),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = if (installed) {
                            stringResource(
                                R.string.model_status_installed,
                                meta?.model ?: "?",
                                formatSize(context, size),
                            )
                        } else {
                            stringResource(R.string.model_status_missing)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    val m = meta
                    if (installed && m != null) {
                        Text(
                            text = stringResource(R.string.model_attribution, m.source, m.license),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.model_enable_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Text(
                        text = stringResource(R.string.model_enable_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Switch(
                    checked = enabled,
                    enabled = installed,
                    onCheckedChange = { value ->
                        enabled = value
                        HaqomuPrefs.setSenseModelEnabled(context, value)
                        SenseModelCache.invalidate()
                    },
                )
            }

            Spacer(Modifier.height(20.dp))
            Text(
                text = stringResource(R.string.model_download_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = stringResource(R.string.model_download_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
            )
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text(stringResource(R.string.model_url_label)) },
                singleLine = true,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 10.dp),
            ) {
                Button(onClick = { download() }, enabled = !busy) {
                    Text(stringResource(R.string.model_download))
                }
                OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !busy) {
                    Text(stringResource(R.string.model_pick_file))
                }
            }

            if (busy) {
                Spacer(Modifier.height(12.dp))
                if (total > 0) {
                    LinearProgressIndicator(
                        progress = { (downloaded.toFloat() / total.toFloat()).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = stringResource(
                            R.string.model_progress,
                            formatSize(context, downloaded),
                            formatSize(context, total),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(
                        text = stringResource(R.string.model_unpacking, formatSize(context, downloaded)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
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

            if (installed) {
                Spacer(Modifier.height(16.dp))
                OutlinedButton(
                    onClick = {
                        if (confirmRemove) {
                            ModelStore.remove(context)
                            HaqomuPrefs.setSenseModelEnabled(context, false)
                            enabled = false
                            confirmRemove = false
                            refresh()
                            message = context.getString(R.string.model_removed)
                        } else {
                            confirmRemove = true
                        }
                    },
                    enabled = !busy,
                ) {
                    Text(
                        stringResource(
                            if (confirmRemove) R.string.model_remove_confirm else R.string.model_remove
                        )
                    )
                }
            }

            Text(
                text = stringResource(R.string.model_privacy),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 20.dp, bottom = 24.dp),
            )
        }
    }
}

private fun displayName(context: Context, uri: Uri): String? {
    context.contentResolver.query(uri, null, null, null, null)?.use { c ->
        val index = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (index >= 0 && c.moveToFirst()) return c.getString(index)
    }
    return uri.lastPathSegment
}

private fun formatSize(context: Context, bytes: Long): String {
    val mb = bytes / 1048576.0
    return if (mb >= 1) {
        context.getString(R.string.size_mb, mb)
    } else {
        context.getString(R.string.size_kb, bytes / 1024)
    }
}
