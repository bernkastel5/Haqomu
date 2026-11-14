package com.bernkastel5.haqomu.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.bernkastel5.haqomu.R

/** «Настройка Anki»: .apkg как базовый путь, AnkiConnect опционален (D-009, D-016). */
@Composable
fun AnkiSetupScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val deck = remember { mutableStateOf(HaqomuPrefs.ankiDeck(context)) }

    ScreenScaffold(title = stringResource(R.string.anki_title), onBack = onBack) {
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            Text(
                text = stringResource(R.string.anki_intro),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )

            Step(1, stringResource(R.string.anki_step1))
            Step(2, stringResource(R.string.anki_step2))
            Step(3, stringResource(R.string.anki_step3))
            Step(4, stringResource(R.string.anki_step4))

            Text(
                text = stringResource(R.string.anki_apkg_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp, bottom = 24.dp),
            )

            OutlinedTextField(
                value = deck.value,
                onValueChange = {
                    deck.value = it
                    HaqomuPrefs.setAnkiDeck(context, it)
                },
                label = { Text(stringResource(R.string.anki_deck_label)) },
                supportingText = { Text(stringResource(R.string.anki_deck_hint)) },
                singleLine = true,
                modifier = Modifier.padding(bottom = 16.dp),
            )

            Text(
                text = stringResource(R.string.anki_connect_note),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Step(number: Int, body: String) {
    Row(modifier = Modifier.padding(top = 14.dp)) {
        Text(
            text = "$number.",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(end = 12.dp),
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}
