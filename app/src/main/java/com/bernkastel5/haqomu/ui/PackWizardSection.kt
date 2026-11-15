package com.bernkastel5.haqomu.ui

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
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
import com.bernkastel5.haqomu.camel.AndroidCamelPackWriter
import com.bernkastel5.haqomu.camel.MorphologyPack
import com.bernkastel5.haqomu.hw.AndroidHansWehrPackBuilder
import com.bernkastel5.haqomu.kaikki.AndroidKaikkiPackBuilder
import com.bernkastel5.haqomu.pack.PackDownloader
import com.bernkastel5.haqomu.pack.PackInstaller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

/** Мастер установки (D-019): одна кнопка — скачать у источников, сконвертировать, проверить, установить. */
private enum class WizardStep(val id: String) { CAMEL("camel"), HANS_WEHR("hw"), KAIKKI("kaikki") }

private enum class StepStatus { WAITING, DOWNLOADING, CONVERTING, VERIFYING, DONE, FAILED }

private data class StepState(
    val status: StepStatus = StepStatus.WAITING,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = -1L,
    val lines: Long = 0L,
    val detail: String? = null,
)

@Composable
fun PackWizardSection(onPacksChanged: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var consent by remember { mutableStateOf(false) }
    val steps = remember { mutableStateMapOf(WizardStep.CAMEL to StepState(), WizardStep.HANS_WEHR to StepState(), WizardStep.KAIKKI to StepState()) }
    var running by remember { mutableStateOf(false) }

    fun update(step: WizardStep, transform: (StepState) -> StepState) {
        steps[step] = transform(steps[step] ?: StepState())
    }

    suspend fun runCamel() {
        update(WizardStep.CAMEL) { StepState(StepStatus.DOWNLOADING) }
        val zip = File(context.cacheDir, "camel_calima_r13.zip")
        PackDownloader.download(PackDownloader.CAMEL_MSA_R13_ZIP, zip) { done, total ->
            update(WizardStep.CAMEL) { it.copy(downloadedBytes = done, totalBytes = total) }
        }
        update(WizardStep.CAMEL) { it.copy(status = StepStatus.CONVERTING) }
        val out = File(context.cacheDir, "camel_pack.sqlite")
        zip.inputStream().use { input ->
            AndroidCamelPackWriter.convert(
                input = input,
                displayName = zip.name,
                outPath = out.absolutePath,
                onProgress = { n -> update(WizardStep.CAMEL) { it.copy(lines = n) } },
            )
        }
        zip.delete()
        // подмена рабочего файла морфологии готовым паком (MorphologyPack читает filesDir/camel_pack.sqlite)
        val dest = MorphologyPack.file(context)
        val tmp = File(context.cacheDir, "camel_pack.sqlite.tmp")
        out.copyTo(tmp, overwrite = true)
        if (dest.exists()) dest.delete()
        if (!tmp.renameTo(dest)) {
            tmp.copyTo(dest, overwrite = true)
            tmp.delete()
        }
        out.delete()
        update(WizardStep.CAMEL) { it.copy(status = StepStatus.DONE, detail = formatSize(context, dest.length())) }
    }

    suspend fun runHansWehr() {
        update(WizardStep.HANS_WEHR) { StepState(StepStatus.DOWNLOADING) }
        val src = File(context.cacheDir, "hanswehr_src.sqlite")
        PackDownloader.download(PackDownloader.HANS_WEHR_SQLITE, src) { done, total ->
            update(WizardStep.HANS_WEHR) { it.copy(downloadedBytes = done, totalBytes = total) }
        }
        update(WizardStep.HANS_WEHR) { it.copy(status = StepStatus.CONVERTING) }
        val built = File(context.cacheDir, "hanswehr_pack.sqlite")
        try {
            AndroidHansWehrPackBuilder.convert(src.absolutePath, built.absolutePath) { n ->
                update(WizardStep.HANS_WEHR) { it.copy(lines = n) }
            }
        } finally {
            src.delete()
        }
        update(WizardStep.HANS_WEHR) { it.copy(status = StepStatus.VERIFYING) }
        val result = PackInstaller.install(context, built, "hanswehr.sqlite")
        built.delete()
        if (!result.ok) throw IllegalStateException(result.error ?: "install failed")
        val entries = result.report?.meta?.get("actual_entry_count") ?: "?"
        update(WizardStep.HANS_WEHR) { it.copy(status = StepStatus.DONE, detail = context.getString(R.string.import_meta_entries, entries)) }
    }

    suspend fun runKaikki() {
        update(WizardStep.KAIKKI) { StepState(StepStatus.DOWNLOADING) }
        val src = File(context.cacheDir, "kaikki_ar.jsonl.gz")
        PackDownloader.download(PackDownloader.KAIKKI_AR_JSONL_GZ, src) { done, total ->
            update(WizardStep.KAIKKI) { it.copy(downloadedBytes = done, totalBytes = total) }
        }
        update(WizardStep.KAIKKI) { it.copy(status = StepStatus.CONVERTING) }
        val built = File(context.cacheDir, "kaikki_pack.sqlite")
        try {
            src.inputStream().use { input ->
                AndroidKaikkiPackBuilder.convert(input, src.name, built.absolutePath) { n ->
                    update(WizardStep.KAIKKI) { it.copy(lines = n) }
                }
            }
        } finally {
            src.delete()
        }
        update(WizardStep.KAIKKI) { it.copy(status = StepStatus.VERIFYING) }
        val result = PackInstaller.install(context, built, "kaikki.sqlite")
        built.delete()
        if (!result.ok) throw IllegalStateException(result.error ?: "install failed")
        val entries = result.report?.meta?.get("actual_entry_count") ?: "?"
        update(WizardStep.KAIKKI) { it.copy(status = StepStatus.DONE, detail = context.getString(R.string.import_meta_entries, entries)) }
    }

    fun launchStep(step: WizardStep) {
        if (running) return
        running = true
        scope.launch(Dispatchers.IO) {
            try {
                when (step) {
                    WizardStep.CAMEL -> runCamel()
                    WizardStep.HANS_WEHR -> runHansWehr()
                    WizardStep.KAIKKI -> runKaikki()
                }
                onPacksChanged()
            } catch (e: Exception) {
                update(step) { it.copy(status = StepStatus.FAILED, detail = e.message ?: e.javaClass.simpleName) }
            } finally {
                running = false
            }
        }
    }

    suspend fun dispatch(step: WizardStep) {
        when (step) {
            WizardStep.CAMEL -> runCamel()
            WizardStep.HANS_WEHR -> runHansWehr()
            WizardStep.KAIKKI -> runKaikki()
        }
    }

    fun installAll() {
        if (running || !consent) return
        running = true
        scope.launch(Dispatchers.IO) {
            for (step in listOf(WizardStep.CAMEL, WizardStep.HANS_WEHR, WizardStep.KAIKKI)) {
                val current = steps[step]
                if (current?.status == StepStatus.DONE) continue
                try {
                    dispatch(step)
                } catch (e: Exception) {
                    update(step) { it.copy(status = StepStatus.FAILED, detail = e.message ?: e.javaClass.simpleName) }
                }
            }
            onPacksChanged()
            running = false
        }
    }

    Surface(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.wizard_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = stringResource(R.string.wizard_intro),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
            )

            WizardStepRow(WizardStep.CAMEL, stringResource(R.string.wizard_camel_name), stringResource(R.string.wizard_camel_desc), steps[WizardStep.CAMEL] ?: StepState(), running) { launchStep(WizardStep.CAMEL) }
            WizardStepRow(WizardStep.HANS_WEHR, stringResource(R.string.wizard_hw_name), stringResource(R.string.wizard_hw_desc), steps[WizardStep.HANS_WEHR] ?: StepState(), running) { launchStep(WizardStep.HANS_WEHR) }
            WizardStepRow(WizardStep.KAIKKI, stringResource(R.string.wizard_kaikki_name), stringResource(R.string.wizard_kaikki_desc), steps[WizardStep.KAIKKI] ?: StepState(), running) { launchStep(WizardStep.KAIKKI) }

            Text(
                text = stringResource(R.string.wizard_ksaa_name),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(top = 4.dp),
            )
            Text(
                text = stringResource(R.string.wizard_ksaa_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 10.dp),
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = consent, onCheckedChange = { consent = it })
                Text(
                    text = stringResource(R.string.wizard_consent),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
            Button(
                onClick = { installAll() },
                enabled = consent && !running,
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Text(stringResource(R.string.wizard_install_all))
            }
            // Подтверждение результата: после мастера пользователю нужно видеть,
            // что всё установлено и проверено, а не догадываться по строкам шагов.
            val allDone = WizardStep.entries.all { steps[it]?.status == StepStatus.DONE }
            if (allDone && !running) {
                Text(
                    text = stringResource(R.string.wizard_all_done),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
            Text(
                text = stringResource(R.string.wizard_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun WizardStepRow(
    step: WizardStep,
    name: String,
    desc: String,
    state: StepState,
    running: Boolean,
    onRetry: () -> Unit,
) {
    val context = LocalContext.current
    Column(modifier = Modifier.padding(bottom = 10.dp)) {
        Text(
            text = name,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = desc,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when (state.status) {
            StepStatus.WAITING -> Unit
            StepStatus.DOWNLOADING -> Text(
                text = if (state.totalBytes > 0) {
                    stringResource(R.string.wizard_step_downloading_pct, (state.downloadedBytes * 100 / state.totalBytes).toInt())
                } else {
                    stringResource(R.string.wizard_step_downloading_mb, formatSize(context, state.downloadedBytes))
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 2.dp),
            )
            StepStatus.CONVERTING -> Text(
                text = stringResource(R.string.wizard_step_converting, state.lines),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 2.dp),
            )
            StepStatus.VERIFYING -> Text(
                text = stringResource(R.string.wizard_step_verifying),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 2.dp),
            )
            StepStatus.DONE -> Text(
                text = stringResource(R.string.wizard_step_done, state.detail ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 2.dp),
            )
            StepStatus.FAILED -> {
                Text(
                    text = stringResource(R.string.wizard_step_failed, state.detail ?: "?"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(top = 2.dp),
                )
                // Ошибку конвертации/установки копируем целиком: по ней видно, на каком шаге
                // и на каком файле всё встало, без перепечатывания с телефона.
                CopyActions(
                    text = "$name — ${state.detail ?: "?"}",
                    modifier = Modifier.padding(top = 0.dp),
                )
                OutlinedButton(onClick = onRetry, enabled = !running, modifier = Modifier.padding(top = 6.dp)) {
                    Text(stringResource(R.string.wizard_retry))
                }
            }
        }
        if (state.status == StepStatus.DOWNLOADING || state.status == StepStatus.CONVERTING || state.status == StepStatus.VERIFYING) {
            CircularProgressIndicator(modifier = Modifier.padding(top = 6.dp))
        }
    }
}

private fun formatSize(context: Context, bytes: Long): String {
    val mb = bytes / 1048576.0
    return if (mb >= 1) context.getString(R.string.size_mb, mb) else context.getString(R.string.size_kb, bytes / 1024)
}
