package com.bernkastel5.haqomu.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.bernkastel5.haqomu.R

/**
 * Копирование текста с экрана.
 *
 * Зачем: сообщения об ошибках на устройстве бесполезны, если их нельзя вынести из телефона
 * (перепечатывать «no such module: fts5 (code 1 SQLITE_ERROR)» руками — не дело).
 * Копируем в буфер обмена, дополнительно — «Отправить…» в любое приложение (почта, мессенджер),
 * чтобы проще было переслать. Ничего не сохраняется и никуда не уходит само.
 */
object Clipboard {

    /** Скопировать [text]; возвращает false, если буфер недоступен. */
    fun copy(context: Context, label: String, text: String): Boolean {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return false
        return try {
            manager.setPrimaryClip(ClipData.newPlainText(label, text))
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Открыть системный «поделиться» с готовым текстом. */
    fun share(context: Context, title: String, text: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            putExtra(Intent.EXTRA_SUBJECT, title)
        }
        try {
            context.startActivity(Intent.createChooser(intent, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            // нет приложений, принимающих текст — не повод падать
        }
    }
}

/** Строка «Скопировать · Отправить…» под сообщением об ошибке или отчётом. */
@Composable
fun CopyActions(
    text: String,
    modifier: Modifier = Modifier,
    title: String = stringResource(R.string.copy_title_error),
) {
    val context = LocalContext.current
    val copied = stringResource(R.string.copy_done)
    Row(
        modifier = modifier.padding(top = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        TextButton(onClick = {
            if (Clipboard.copy(context, title, text)) {
                android.widget.Toast.makeText(context, copied, android.widget.Toast.LENGTH_SHORT).show()
            }
        }) {
            Text(stringResource(R.string.copy_action), style = MaterialTheme.typography.labelLarge)
        }
        TextButton(onClick = { Clipboard.share(context, title, text) }) {
            Text(stringResource(R.string.share_action), style = MaterialTheme.typography.labelLarge)
        }
        // На Android 13+ система сама показывает превью скопированного — подсказка не нужна.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) Unit
    }
}
