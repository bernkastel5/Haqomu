package com.bernkastel5.haqomu.ui

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.bernkastel5.haqomu.pack.AndroidSQLitePackSource
import com.bernkastel5.haqomu.pack.PackChecks
import com.bernkastel5.haqomu.pack.PackReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

private data class InstalledPack(val name: String, val size: Long, val meta: Map<String, String>)
internal data class PickedPack(val report: PackReport, val tempFile: File, val displayName: String, val size: Long)

/** Экран «Импорт паков»: выбор файла, полная проверка по архитектуре §4, установка в приватную папку. */
@Composable
fun ImportPacksScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val packsDir = File(context.filesDir, "packs")
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val copiedDone = stringResource(R.string.import_install_done)
    val deleteDone = stringResource(R.string.import_delete_done)
    val corruptPack = stringResource(R.string.import_unreadable)

    var picked by remember { mutableStateOf<PickedPack?>(null) }
    var installed by remember { mutableStateOf(readInstalledPacks(packsDir)) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            val tmp = File.createTempFile("import_", ".sqlite", context.cacheDir)
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
            val copied = runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    tmp.outputStream().use { output -> input.copyTo(output) }
                } != null
            }.getOrDefault(false)
            if (!copied) {
                tmp.delete()
                picked = null
                snackbarHostState.showSnackbar(corruptPack)
                return@launch
            }
            val report = try {
                AndroidSQLitePackSource.open(tmp).use { ds -> PackChecks.verify(ds) }
            } catch (e: Exception) {
                PackReport(emptyMap(), listOf(com.bernkastel5.haqomu.pack.PackCheck("readable", false, e.message)), fatalError = e.message)
            }
            picked = PickedPack(report, tmp, name, if (tmp.length() > 0) tmp.length() else size)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        ScreenScaffold(title = stringResource(R.string.import_title), onBack = onBack) {
            Column(
                modifier = Modifier
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = stringResource(R.string.import_intro),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )

                PackWizardSection(onPacksChanged = { installed = readInstalledPacks(packsDir) })

                MorphologySection()

                if (installed.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.import_installed),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 20.dp, bottom = 8.dp),
                    )
                    for (pack in installed) {
                        InstalledPackRow(pack, onDelete = {
                            File(packsDir, pack.name).delete()
                            installed = readInstalledPacks(packsDir)
                            scope.launch { snackbarHostState.showSnackbar(deleteDone) }
                        })
                    }
                }

                PackInfo(stringResource(R.string.import_camel), stringResource(R.string.import_camel_sub))
                PackInfo(stringResource(R.string.import_ksaa), stringResource(R.string.import_ksaa_sub))
                PackInfo(stringResource(R.string.import_kaikki), stringResource(R.string.import_kaikki_sub))
                PackInfo(stringResource(R.string.import_hw), stringResource(R.string.import_hw_sub))

                OutlinedButton(
                    onClick = { launcher.launch(arrayOf("*/*")) },
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    Text(stringResource(R.string.import_pick))
                }

                val file = picked
                if (file != null) {
                    ReportCard(
                        picked = file,
                        onInstall = {
                            if (packsDir.mkdirs() || packsDir.isDirectory) {
                                val dest = uniquePackFile(packsDir, file.displayName)
                                if (file.tempFile.renameTo(dest)) {
                                    installed = readInstalledPacks(packsDir)
                                    picked = null
                                    scope.launch { snackbarHostState.showSnackbar(copiedDone) }
                                }
                            }
                        },
                        onDismiss = {
                            file.tempFile.delete()
                            picked = null
                        },
                    )
                }

                Text(
                    text = stringResource(R.string.import_copy_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 16.dp, bottom = 32.dp),
                )
            }
        }
    }
}

private fun readInstalledPacks(packsDir: File): List<InstalledPack> {
    val files = packsDir.listFiles { f -> f.isFile && f.name.endsWith(".sqlite") } ?: return emptyList()
    return files.map { f ->
        val meta = try {
            AndroidSQLitePackSource.open(f).use { ds ->
                val keys = listOf("source", "license", "build_date", "entry_count")
                keys.associateWith { k -> ds.metaValue(k) ?: "" }
            }
        } catch (_: Exception) {
            emptyMap()
        }
        InstalledPack(f.name, f.length(), meta)
    }.sortedBy { it.name }
}

private fun uniquePackFile(packsDir: File, displayName: String): File {
    val raw = displayName.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "pack" }
    val base = raw.removeSuffix(".sqlite")
    var candidate = File(packsDir, "$base.sqlite")
    var i = 2
    while (candidate.exists()) {
        candidate = File(packsDir, "$base-$i.sqlite")
        i += 1
    }
    return candidate
}

@Composable
private fun InstalledPackRow(pack: InstalledPack, onDelete: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 10.dp)) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = pack.name,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            val metaLine = listOfNotNull(
                pack.meta["source"]?.takeIf { it.isNotBlank() },
                pack.meta["license"]?.takeIf { it.isNotBlank() },
                pack.meta["entry_count"]?.takeIf { it.isNotBlank() }?.let { stringResource(R.string.import_meta_entries, it) },
            ).joinToString(" · ")
            if (metaLine.isNotEmpty()) {
                Text(
                    text = metaLine,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        OutlinedButton(onClick = onDelete) {
            Text(stringResource(R.string.import_delete))
        }
    }
}

@Composable
private fun ReportCard(picked: PickedPack, onInstall: () -> Unit, onDismiss: () -> Unit) {
    val ok = picked.report.passed
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = stringResource(if (ok) R.string.import_verify_ok else R.string.import_verify_bad),
                style = MaterialTheme.typography.titleSmall,
                color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
            )
            Text(
                text = picked.displayName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                text = formatSize(LocalContext.current, picked.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val meta = picked.report.meta
            Text(
                text = listOfNotNull(
                    meta["source"]?.let { stringResource(R.string.import_meta_source, it) },
                    meta["license"]?.let { stringResource(R.string.import_meta_license, it) },
                    meta["build_date"]?.let { stringResource(R.string.import_meta_date, it) },
                    meta["entry_count"]?.let { stringResource(R.string.import_meta_entries, it) },
                    (meta["lang_from"]?.let { from -> meta["lang_to"]?.let { to -> stringResource(R.string.import_meta_langs, from, to) } }),
                ).joinToString("\n"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
            )
            for (check in picked.report.checks) {
                CheckRow(check.id, check.ok, check.detail)
            }
            // Текст отчёта — целиком в буфер: перепечатывать проверки руками не нужно.
            val reportText = remember(picked) { reportToText(picked) }
            CopyActions(text = reportText, modifier = Modifier.padding(top = 6.dp))
            Row(modifier = Modifier.padding(top = 16.dp)) {
                Button(onClick = onInstall, enabled = ok) {
                    Text(stringResource(R.string.import_install))
                }
                OutlinedButton(onClick = onDismiss, modifier = Modifier.padding(start = 8.dp)) {
                    Text(stringResource(R.string.import_cancel))
                }
            }
        }
    }
}

/**
 * Отчёт о проверке пака в виде текста для буфера обмена и «Отправить…».
 * Пишем и человеческую строку, и технический id проверки: по первому понятно, что не так,
 * по второму можно искать в коде. Формат стабильный — им же пользуются тесты.
 */
internal fun reportToText(picked: PickedPack): String {
    val ok = picked.report.passed
    val head = if (ok) "Проверка пака пройдена" else "Проверка пака не пройдена"
    val lines = mutableListOf(
        head,
        "файл: ${picked.displayName}",
        "размер: ${picked.size} байт",
    )
    picked.report.fatalError?.let { lines += "критическая ошибка: $it" }
    val meta = picked.report.meta
    for (key in listOf("source", "license", "build_date", "entry_count", "lang_from", "lang_to", "fts")) {
        val value = meta[key]?.takeIf { it.isNotBlank() } ?: continue
        lines += "$key: $value"
    }
    lines += "проверки:"
    for (check in picked.report.checks) {
        val mark = if (check.ok) "ok" else "FAIL"
        val detail = check.detail?.takeIf { it.isNotBlank() }?.let { " — $it" } ?: ""
        lines += "  [$mark] ${check.id}$detail"
    }
    return lines.joinToString("\n")
}

@Composable
private fun CheckRow(id: String, ok: Boolean, detail: String?) {
    Row(modifier = Modifier.padding(bottom = 4.dp)) {
        Text(
            text = if (ok) "✓" else "✗",
            style = MaterialTheme.typography.bodyMedium,
            color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
            modifier = Modifier.padding(end = 10.dp),
        )
        Column {
            Text(
                text = checkLabel(id),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
            if (!detail.isNullOrBlank()) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun checkLabel(id: String): String {
    val table = Regex("^table:(.*)$").find(id)?.groupValues?.get(1)
    if (table != null) return stringResource(R.string.check_table, table)
    val columns = Regex("^columns:(.*)$").find(id)?.groupValues?.get(1)
    if (columns != null) return stringResource(R.string.check_columns, columns)
    val meta = Regex("^meta:(.*)$").find(id)?.groupValues?.get(1)
    if (meta != null) return stringResource(R.string.check_meta, meta)
    return when (id) {
        "readable" -> stringResource(R.string.check_readable)
        "lang_pair" -> stringResource(R.string.check_langs)
        "schema_version" -> stringResource(R.string.check_schema_version)
        "entry_count_present", "entry_count_match" -> stringResource(R.string.check_entry_count)
        "entry_nonempty" -> stringResource(R.string.check_entry_nonempty)
        "senses_nonempty" -> stringResource(R.string.check_senses_nonempty)
        "orphan_senses" -> stringResource(R.string.check_orphans)
        "head_norm_filled" -> stringResource(R.string.check_headnorm)
        "fts_available" -> stringResource(R.string.check_fts)
        else -> id
    }
}


@Composable
private fun PackInfo(title: String, body: String) {
    Column(modifier = Modifier.padding(bottom = 14.dp)) {
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
