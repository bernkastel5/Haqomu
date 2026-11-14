package com.bernkastel5.haqomu.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bernkastel5.haqomu.R
import kotlinx.coroutines.launch

/**
 * Предпросмотр рабочего попапа (фаза 6) на синтетической статье: структура «слово → статья → смыслы → В Anki».
 * Содержимое выдумано, словарных данных здесь нет и быть не может (ставки лицензий).
 */
@Composable
fun PopupPreviewScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scale = HaqomuPrefs.arabicTextScale(context)
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val snackText = stringResource(R.string.preview_snack)

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            TextButton(onClick = onBack) {
                Text(stringResource(R.string.nav_back))
            }
            Text(
                text = stringResource(R.string.preview_caption),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            CompositionRtl {
                Text(
                    text = "ذَهَبَ",
                    fontFamily = FontFamily.Serif,
                    fontSize = (32 * scale).sp,
                    lineHeight = (44 * scale).sp,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
            Text(
                text = "ḏahaba · VERB",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            CompositionRtl {
                Text(
                    text = "الجذر: ذ ه ب",
                    fontFamily = FontFamily.Serif,
                    fontSize = (15 * scale).sp,
                    lineHeight = (24 * scale).sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp, bottom = 12.dp),
                )
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(Modifier.padding(16.dp)) {
                    CompositionRtl {
                        Text(
                            text = "ذَهَبَ",
                            fontFamily = FontFamily.Serif,
                            fontSize = (20 * scale).sp,
                            lineHeight = (28 * scale).sp,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    SenseRow("١.", "سَارَ وَمَضَى وَخَرَجَ إِلَى جِهَةٍ", "to go, to set out", scale)
                    SenseRow("٢.", "اِنْتَقَلَ مِنَ الدُّنْيَا وَمَاتَ", "to pass away", scale)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.preview_source),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.preview_more),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(20.dp))
            Button(onClick = { scope.launch { snackbarHostState.showSnackbar(snackText) } }) {
                Text(stringResource(R.string.preview_in_anki))
            }
            Text(
                text = stringResource(R.string.preview_close),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp, bottom = 24.dp),
            )
        }
    }
}

@Composable
private fun SenseRow(number: String, definition: String, gloss: String, scale: Float) {
    CompositionRtl {
    Row(modifier = Modifier.padding(bottom = 10.dp)) {
        Text(
            text = number,
            fontFamily = FontFamily.Serif,
            fontSize = (17 * scale).sp,
            lineHeight = (28 * scale).sp,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(end = 10.dp),
        )
        Column {
            CompositionRtl {
                Text(
                    text = definition,
                    fontFamily = FontFamily.Serif,
                    fontSize = (17 * scale).sp,
                    lineHeight = (28 * scale).sp,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
            Text(
                text = gloss,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    }
}

/** Блок арабского текста всегда в RTL, независимо от языка интерфейса. */
@Composable
private fun CompositionRtl(content: @Composable () -> Unit) {
    androidx.compose.runtime.CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        content()
    }
}
