package com.bernkastel5.haqomu.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.bernkastel5.haqomu.R

/** Первый запуск: пустое состояние. APK не содержит словарных данных (TZ §5.1). */
@Composable
fun SetupWizardScreen(onFinish: () -> Unit) {
    val pages = listOf(
        R.string.wizard_title_1 to R.string.wizard_body_1,
        R.string.wizard_title_2 to R.string.wizard_body_2,
        R.string.wizard_title_3 to R.string.wizard_body_3,
    )
    val page = rememberSaveable { mutableStateOf(0) }
    val last = pages.size - 1

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 40.dp)
    ) {
        Text(
            text = stringResource(R.string.wizard_step, page.value + 1, pages.size),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(24.dp))
        Text(
            text = stringResource(pages[page.value].first),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(pages[page.value].second),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(32.dp))
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            TextButton(onClick = { if (page.value > 0) page.value -= 1 }, enabled = page.value > 0) {
                Text(stringResource(R.string.wizard_back))
            }
            Spacer(Modifier.weight(1f))
            if (page.value < last) {
                Button(onClick = { page.value += 1 }) {
                    Text(stringResource(R.string.wizard_next))
                }
            } else {
                Button(onClick = onFinish) {
                    Text(stringResource(R.string.wizard_done))
                }
            }
        }
    }
}
