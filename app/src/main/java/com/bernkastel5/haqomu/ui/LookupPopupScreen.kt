package com.bernkastel5.haqomu.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.widget.Toast
import com.bernkastel5.haqomu.AnkiCardActivity
import com.bernkastel5.haqomu.R
import com.bernkastel5.haqomu.anki.CardSaver
import com.bernkastel5.haqomu.anki.CardText
import com.bernkastel5.haqomu.anki.PendingCard
import com.bernkastel5.haqomu.anki.SaveOutcome
import com.bernkastel5.haqomu.camel.MorphologyPack
import com.bernkastel5.haqomu.lookup.LookupQuery
import com.bernkastel5.haqomu.lookup.PopupFormat
import com.bernkastel5.haqomu.morph.Analyzer
import com.bernkastel5.haqomu.morph.AndroidSqliteCamelStore
import com.bernkastel5.haqomu.pack.AndroidPackDictionary
import com.bernkastel5.haqomu.pipeline.PipelineEngine
import com.bernkastel5.haqomu.pipeline.PopupArticle
import com.bernkastel5.haqomu.pipeline.PopupContent
import com.bernkastel5.haqomu.pipeline.PopupMode
import com.bernkastel5.haqomu.pipeline.WordKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private sealed interface PopupState {
    data object Loading : PopupState
    data object Invalid : PopupState
    data object MissingPacks : PopupState
    data class Ready(val content: PopupContent) : PopupState
    data class Error(val message: String) : PopupState
}

/**
 * Попап словаря (Ф1, Ф9): до трёх статей из §4-паков, ответ пайплайна первым,
 * все смыслы видны; «имя собственное» — честная плашка. Словарных данных в APK нет;
 * паки читаются из filesDir/packs, морфология — filesDir/camel_pack.sqlite.
 * Первый кадр — скелетон; движок работает на Dispatchers.IO.
 */
@Composable
fun LookupPopupScreen(
    rawText: String?,
    onClose: () -> Unit,
    onOpenApp: () -> Unit,
    /** Предложение вокруг выделения из сервиса доступности (Ф3); пусто на пути PROCESS_TEXT. */
    sentence: String = "",
) {
    val context = LocalContext.current
    var state by remember { mutableStateOf<PopupState>(PopupState.Loading) }

    LaunchedEffect(rawText, sentence) {
        state = withContext(Dispatchers.IO) {
            val word = LookupQuery.extract(rawText) ?: return@withContext PopupState.Invalid
            val packsDir = File(context.filesDir, "packs")
            val camelFile = MorphologyPack.file(context)
            val dict = AndroidPackDictionary.open(packsDir)
            var camel: AndroidSqliteCamelStore? = null
            try {
                if (!camelFile.isFile || camelFile.length() == 0L || dict.packCount == 0) {
                    return@withContext PopupState.MissingPacks
                }
                camel = try {
                    AndroidSqliteCamelStore(camelFile.absolutePath)
                } catch (_: Exception) {
                    return@withContext PopupState.MissingPacks
                }
                PopupState.Ready(PipelineEngine(Analyzer(camel), dict).lookup(word))
            } catch (e: Exception) {
                PopupState.Error(e.message ?: e.javaClass.simpleName)
            } finally {
                dict.close()
                camel?.close()
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // тап мимо попапа закрывает его (ТЗ 3.1)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.45f))
                .clickable(onClick = onClose),
        )
        Column(
            modifier = Modifier
                .align(androidx.compose.ui.Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(0.86f)
                .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 18.dp, vertical = 14.dp),
        ) {
            when (val st = state) {
                PopupState.Loading -> LoadingSheet(modifier = Modifier.weight(1f))
                PopupState.Invalid -> PlateSheet(
                    modifier = Modifier.weight(1f),
                    title = stringResource(R.string.plate_invalid_title),
                    body = stringResource(R.string.plate_invalid_body),
                    onClose = onClose,
                )
                PopupState.MissingPacks -> PlateSheet(
                    modifier = Modifier.weight(1f),
                    title = stringResource(R.string.plate_missing_title),
                    body = stringResource(R.string.plate_missing_body),
                    actionLabel = stringResource(R.string.plate_open_app),
                    onAction = onOpenApp,
                    onClose = onClose,
                )
                is PopupState.Error -> PlateSheet(
                    modifier = Modifier.weight(1f),
                    title = stringResource(R.string.plate_error_title),
                    body = stringResource(R.string.plate_error_body, st.message),
                    onClose = onClose,
                )
                is PopupState.Ready -> ReadySheet(
                    modifier = Modifier.weight(1f),
                    content = st.content,
                    sentence = sentence,
                    onClose = onClose,
                )
            }
        }
    }
}

// ---------------------------------------------------------------- состояния

@Composable
private fun LoadingSheet(modifier: Modifier = Modifier) {
    val bar = MaterialTheme.colorScheme.surfaceVariant
    Column(modifier = modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(modifier = Modifier.fillMaxWidth(0.42f).height(32.dp).background(bar, RoundedCornerShape(6.dp)))
        Box(modifier = Modifier.fillMaxWidth(0.3f).height(16.dp).background(bar, RoundedCornerShape(6.dp)))
        Spacer(Modifier.height(4.dp))
        Box(modifier = Modifier.fillMaxWidth().height(150.dp).background(bar.copy(alpha = 0.5f), RoundedCornerShape(16.dp)))
        Box(modifier = Modifier.fillMaxWidth().height(150.dp).background(bar.copy(alpha = 0.35f), RoundedCornerShape(16.dp)))
    }
}

@Composable
private fun PlateSheet(
    modifier: Modifier = Modifier,
    title: String,
    body: String,
    onClose: () -> Unit,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(modifier = modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
        Spacer(Modifier.weight(1f))
        Row(modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = onClose) { Text(stringResource(R.string.popup_close)) }
            Spacer(Modifier.weight(1f))
            if (actionLabel != null && onAction != null) {
                Button(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}

@Composable
private fun ReadySheet(
    modifier: Modifier = Modifier,
    content: PopupContent,
    sentence: String,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scale = HaqomuPrefs.arabicTextScale(context)

    Column(modifier = modifier.fillMaxWidth().padding(top = 4.dp)) {
        CompositionRtl {
            Text(
                text = content.original,
                fontFamily = FontFamily.Serif,
                fontSize = (30 * scale).sp,
                lineHeight = (40 * scale).sp,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
            )
        }
        val root = PopupFormat.rootLetters(content.articles.firstNotNullOfOrNull { it.root })
        if (root != null) {
            CompositionRtl {
                Text(
                    text = "الجذر: $root",
                    fontFamily = FontFamily.Serif,
                    fontSize = (15 * scale).sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        if (sentence.isNotEmpty()) {
            CompositionRtl {
                Text(
                    text = sentence,
                    fontFamily = FontFamily.Serif,
                    fontSize = (15 * scale).sp,
                    lineHeight = (24 * scale).sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    modifier = Modifier.padding(top = 4.dp),
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
        }
        if (content.wordKind == WordKind.CLITIC && content.cliticBase != null) {
            CompositionRtl {
                Text(
                    text = stringResource(R.string.popup_clitic_base, content.cliticBase),
                    fontSize = (15 * scale).sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(top = 12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (content.mode == PopupMode.NAME_PLATE) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            text = stringResource(R.string.plate_name_title),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = stringResource(R.string.plate_name_body),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            } else if (content.articles.isEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            text = stringResource(R.string.plate_no_articles_title),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = stringResource(R.string.plate_no_articles_body),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            } else {
                content.articles.forEach { article ->
                    ArticleCard(
                        article = article,
                        content = content,
                        scale = scale,
                        sentence = sentence,
                        onClose = onClose,
                    )
                }
            }
        }

        if (content.mode == PopupMode.ARTICLES) {
            Text(
                text = stringResource(R.string.popup_attribution),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
        Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            TextButton(onClick = onClose) { Text(stringResource(R.string.popup_close)) }
        }
    }
}

// ---------------------------------------------------------------- статья

@Composable
private fun ArticleCard(
    article: PopupArticle,
    content: PopupContent,
    scale: Float,
    sentence: String,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val dictionaryLabel = sourceLabel(article.sourceFamily)

    // Слово в карточке — так, как оно выделено в тексте (TZ §3.3); голова статьи с
    // огласовкой — отдельно, в поле Tashkeel. Без выделения (предпросмотр) — голова.
    val expression = content.original.ifBlank { article.head }

    fun cardIntent(meaning: String) = AnkiCardActivity.newIntent(
        context = context,
        expression = expression,
        tashkeel = article.head,
        meaning = meaning,
        sentence = sentence,
        root = article.root.orEmpty(),
        dictionary = dictionaryLabel,
    )

    fun openEditor(meaning: String) {
        context.startActivity(cardIntent(meaning))
        onClose()
    }

    fun ankiClick(senseDefinition: String, glossEn: String?) {
        val meaning = CardText.meaningText(senseDefinition, glossEn)
        if (HaqomuPrefs.ankiEditBeforeSave(context)) {
            openEditor(meaning)
            return
        }
        // стандартный сценарий: нажать «В Anki» и больше не взаимодействовать с карточкой
        val card = PendingCard(
            expression = expression,
            tashkeel = article.head,
            meaning = meaning,
            sentence = sentence,
            root = article.root.orEmpty(),
            dictionary = dictionaryLabel,
        )
        when (val outcome = CardSaver.save(context, HaqomuPrefs.ankiDeck(context), card)) {
            SaveOutcome.Added ->
                Toast.makeText(context, R.string.anki_card_added, Toast.LENGTH_SHORT).show()
            SaveOutcome.NeedsPermission -> openEditor(meaning)
            SaveOutcome.Invalid -> openEditor(meaning)
            SaveOutcome.Duplicate ->
                Toast.makeText(context, R.string.anki_card_duplicate, Toast.LENGTH_SHORT).show()
            is SaveOutcome.Pending ->
                Toast.makeText(context, outcome.messageRes, Toast.LENGTH_SHORT).show()
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)),
    ) {
        Column(Modifier.padding(14.dp)) {
            CompositionRtl {
                Text(
                    text = article.head,
                    fontFamily = FontFamily.Serif,
                    fontSize = (20 * scale).sp,
                    lineHeight = (28 * scale).sp,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Row(modifier = Modifier.padding(top = 2.dp, bottom = 8.dp)) {
                Text(
                    text = sourceLabel(article.sourceFamily),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                val pos = article.pos
                if (!pos.isNullOrBlank()) {
                    Text(
                        text = " · ${pos.uppercase()}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            article.senses.forEachIndexed { index, sense ->
                SenseRow(
                    number = PopupFormat.arabicIndicNumber(index + 1),
                    definition = sense.definition,
                    glossEn = sense.glossEn,
                    scale = scale,
                    onAnki = { ankiClick(sense.definition, sense.glossEn) },
                )
            }
        }
    }
}

@Composable
private fun SenseRow(number: String, definition: String, glossEn: String?, scale: Float, onAnki: () -> Unit) {
    Row(
        modifier = Modifier.padding(bottom = 8.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Text(
            text = number,
            fontFamily = FontFamily.Serif,
            fontSize = (16 * scale).sp,
            lineHeight = (26 * scale).sp,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(28.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            CompositionRtl {
                Text(
                    text = definition,
                    fontFamily = FontFamily.Serif,
                    fontSize = (16 * scale).sp,
                    lineHeight = (26 * scale).sp,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            if (!glossEn.isNullOrBlank() && glossEn.trim() != definition.trim()) {
                Text(
                    text = glossEn,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        TextButton(onClick = onAnki) {
            Text(stringResource(R.string.popup_in_anki), style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun sourceLabel(family: String): String = when (family) {
    "kaikki" -> stringResource(R.string.popup_source_kaikki)
    "wehr" -> stringResource(R.string.popup_source_wehr)
    "ksaa" -> stringResource(R.string.popup_source_ksaa)
    else -> stringResource(R.string.popup_source_other)
}
