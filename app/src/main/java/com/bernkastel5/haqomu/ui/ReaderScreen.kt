package com.bernkastel5.haqomu.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bernkastel5.haqomu.R
import com.bernkastel5.haqomu.lookup.LookupPopupActivity
import com.bernkastel5.haqomu.reader.Book
import com.bernkastel5.haqomu.reader.BookText
import com.bernkastel5.haqomu.reader.ReaderSelection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Что сейчас показывает читалка. */
private sealed interface ReaderState {
    data object Idle : ReaderState
    data class Loading(val name: String) : ReaderState
    data class Ready(val book: Book) : ReaderState
    data class Failed(val name: String) : ReaderState
}

/**
 * Встроенная читалка (Ф14).
 *
 * Что делает: открывает книгу из памяти телефона (EPUB, PDF с текстовым слоем, FB2,
 * HTML, TXT), показывает главы/страницы и отдаёт тап по слову в тот же попап, что и
 * выделение в других приложениях. Предложение-контекст вырезается тем же кодом
 * ([com.bernkastel5.haqomu.lookup.SentenceExtractor]), поэтому смысл в попапе и
 * карточка в Anki получают контекст без сервиса доступности.
 *
 * Чего не делает и говорит об этом прямо: не рендерит PDF как картинку (читается
 * текстовый слой), не распознаёт текст на сканах (OCR — отдельная задача), не
 * открывает книги с DRM. Для таких файлов экран объясняет причину, а не показывает
 * пустоту.
 *
 * Буфер обмена не используется и ничего не копируется: тап даёт слово и предложение
 * прямо в попап.
 */
@Composable
fun ReaderScreen(
    onBack: () -> Unit,
    /**
     * Книга, с которой надо начать (открытие файла из другого приложения).
     * null — обычный вход из главного экрана: пользователь выбирает книгу сам.
     */
    initialUri: Uri? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<ReaderState>(ReaderState.Idle) }
    var chapterIndex by remember { mutableStateOf(0) }
    val scale = HaqomuPrefs.arabicTextScale(context)
    // Книга могла прийти вторым интентом (singleTask): читаем при каждом входе.
    val intentBookUri: () -> Uri? = {
        if (context is android.app.Activity) {
            val i = context.intent
            when (i?.action) {
                Intent.ACTION_VIEW -> i.data
                Intent.ACTION_SEND -> @Suppress("DEPRECATION") i.getParcelableExtra(Intent.EXTRA_STREAM)
                else -> null
            }
        } else {
            null
        }
    }

    val pickerLauncher = rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        state = ReaderState.Loading(uri.lastPathSegment?.substringAfterLast('/') ?: "…")
        scope.launch {
            state = withContext(Dispatchers.IO) {
                val file = copyToCache(context, uri)
                    ?: return@withContext ReaderState.Failed(uri.lastPathSegment.orEmpty())
                val book = BookText.open(file)
                if (book == null) ReaderState.Failed(file.name) else ReaderState.Ready(book)
            }
            chapterIndex = 0
        }
    }
    val picker: () -> Unit = { pickerLauncher.launch(BookText.MIME_TYPES) }

    // Книга, открытая извне («Поделиться», «Открыть с помощью»).
    LaunchedEffect(initialUri) {
        val uri = initialUri ?: (intentBookUri() ?: return@LaunchedEffect)
        val name = uri.lastPathSegment?.substringAfterLast('/') ?: "…"
        state = ReaderState.Loading(name)
        state = withContext(Dispatchers.IO) {
            val file = copyToCache(context, uri) ?: return@withContext ReaderState.Failed(name)
            val book = BookText.open(file)
            if (book == null) ReaderState.Failed(name) else ReaderState.Ready(book)
        }
        chapterIndex = 0
    }

    BackHandler {
        if (state !is ReaderState.Idle) {
            state = ReaderState.Idle
        } else {
            onBack()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 16.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            TextButton(onClick = {
                if (state is ReaderState.Idle) onBack() else state = ReaderState.Idle
            }) { Text(stringResource(R.string.nav_back)) }
            Text(
                text = stringResource(R.string.reader_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(start = 8.dp),
            )
        }

        when (val st = state) {
            is ReaderState.Idle -> ReaderEmpty(onPick = { picker() })
            is ReaderState.Loading -> Text(
                text = stringResource(R.string.reader_loading, st.name),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 16.dp),
            )
            is ReaderState.Failed -> ReaderFailed(
                name = st.name,
                onPick = { picker() },
            )
            is ReaderState.Ready -> ReaderBook(
                book = st.book,
                index = chapterIndex.coerceIn(0, st.book.chapters.lastIndex),
                scale = scale,
                onChapter = { chapterIndex = it },
                onWord = { word, sentence ->
                    context.startActivity(LookupPopupActivity.newIntent(context, word, sentence))
                },
            )
        }
    }
}

// ---------------------------------------------------------------- состояния

@Composable
private fun ReaderEmpty(onPick: () -> Unit) {
    Column(modifier = Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = stringResource(R.string.reader_empty_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = stringResource(R.string.reader_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onPick) { Text(stringResource(R.string.reader_pick)) }
        Text(
            text = stringResource(R.string.reader_formats),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ReaderFailed(name: String, onPick: () -> Unit) {
    Column(modifier = Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = stringResource(R.string.reader_failed_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = stringResource(R.string.reader_failed_body, name),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onPick) { Text(stringResource(R.string.reader_pick)) }
    }
}

@Composable
private fun ReaderBook(
    book: Book,
    index: Int,
    scale: Float,
    onChapter: (Int) -> Unit,
    onWord: (String, String) -> Unit,
) {
    val chapter = book.chapters.getOrNull(index) ?: return
    var tapMark by remember(chapter.id) { mutableStateOf<IntRange?>(null) }

    Column(modifier = Modifier.fillMaxSize().padding(top = 8.dp)) {
        Text(
            text = book.title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        val author = book.author
        Text(
            text = listOfNotNull(author, book.format).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.reader_chapter_of, chapter.title, index + 1, book.chapters.size),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 6.dp),
        )
        HorizontalDivider(
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        ChapterText(
            modifier = Modifier.weight(1f),
            text = chapter.text,
            scale = scale,
            mark = tapMark,
            onTapWord = { offset ->
                val at = ReaderSelection.select(chapter.text, offset)
                if (at != null) {
                    tapMark = at.start until at.end
                    onWord(at.word, at.sentence)
                } else {
                    tapMark = null
                }
            },
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            TextButton(
                onClick = { onChapter(index - 1) },
                enabled = index > 0,
            ) { Text(stringResource(R.string.reader_prev)) }
            Text(
                text = stringResource(R.string.reader_tap_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
            TextButton(
                onClick = { onChapter(index + 1) },
                enabled = index < book.chapters.lastIndex,
            ) { Text(stringResource(R.string.reader_next)) }
        }
    }
}

@Composable
private fun ChapterText(
    modifier: Modifier = Modifier,
    text: String,
    scale: Float,
    mark: IntRange?,
    onTapWord: (Int) -> Unit,
) {
    val scroll = rememberScrollState()
    var layout by remember(text) { mutableStateOf<TextLayoutResult?>(null) }
    val annotated: AnnotatedString = remember(text, mark) {
        buildAnnotatedString {
            append(text)
            val m = mark
            if (m != null && m.first in text.indices && m.last < text.length && m.first <= m.last) {
                addStyle(SpanStyle(background = androidx.compose.ui.graphics.Color(0x33FFC107)), m.first, m.last + 1)
            }
        }
    }
    Column(modifier = modifier) {
        CompositionRtl {
            Text(
                text = annotated,
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(scroll)
                    .pointerInput(text) {
                        detectTapGestures { position ->
                            val result = layout
                            val offset = result?.getOffsetForPosition(position) ?: return@detectTapGestures
                            onTapWord(offset)
                        }
                    },
                // Гарнитурный выбор тот же, что в попапе: системный Serif с арабским
                // покрытием; своих шрифтов в APK нет (TZ §7: только свободные лицензии).
                fontFamily = FontFamily.Serif,
                fontSize = (19 * scale).sp,
                lineHeight = (32 * scale).sp,
                color = MaterialTheme.colorScheme.onBackground,
                onTextLayout = { layout = it },
            )
        }
        // Подпись под текстом: какое слово сейчас выбрано (попап мог уже закрыться).
        if (mark != null) {
            Text(
                text = text.substring(mark.first, (mark.last + 1).coerceAtMost(text.length)),
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.Medium,
                fontSize = (14 * scale).sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/**
 * Копия выбранного документа во внутренний кэш: провайдеры часто дают поток,
 * который нельзя перечитать, а читалке нужен случайный доступ (zip-оглавление EPUB).
 * Файл называется по имени документа: расширение важно для выбора парсера.
 */
private fun copyToCache(context: android.content.Context, uri: Uri): File? = try {
    val name = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "book"
    val dir = File(context.cacheDir, "reader")
    dir.mkdirs()
    val ext = BookText.extensionOf(name).ifEmpty { "bin" }
    val out = File(dir, name.substringBeforeLast('.', name).ifEmpty { "book" } + "." + ext)
    context.contentResolver.openInputStream(uri)?.use { input ->
        out.outputStream().use { output -> input.copyTo(output, 256 * 1024) }
    }
    if (out.isFile && out.length() > 0) out else null
} catch (_: Exception) {
    null
}
