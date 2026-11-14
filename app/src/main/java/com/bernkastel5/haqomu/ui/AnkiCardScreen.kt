package com.bernkastel5.haqomu.ui

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.bernkastel5.haqomu.R
import com.bernkastel5.haqomu.anki.AnkiDroidBridge
import com.bernkastel5.haqomu.anki.AnkiModel
import com.bernkastel5.haqomu.anki.PendingCard
import com.bernkastel5.haqomu.anki.PendingStore

/**
 * Ф11 / TZ §3.3: редактор карточки перед сохранением. Поля предзаполнены выбором
 * из попапа; пользователь правит и сохраняет. Куда уходит: AnkiDroid (ContentProvider)
 * если установлен и разрешил доступ; иначе — локальное накопление, экспорт .apkg/AnkiConnect
 * из «Настройки Anki».
 */
@Composable
fun AnkiCardScreen(
    initialExpression: String,
    initialTashkeel: String,
    initialMeaning: String,
    initialSentence: String,
    initialRoot: String,
    initialDictionary: String,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val ankidroidInstalled = remember { AnkiDroidBridge(context).isAnkiDroidInstalled() }

    var expression by remember { mutableStateOf(initialExpression) }
    var tashkeel by remember { mutableStateOf(initialTashkeel) }
    var meaning by remember { mutableStateOf(initialMeaning) }
    var sentence by remember { mutableStateOf(initialSentence) }
    var root by remember { mutableStateOf(initialRoot) }
    var dictionary by remember { mutableStateOf(initialDictionary) }
    var notes by remember { mutableStateOf("") }
    var errorText by remember { mutableStateOf<String?>(null) }

    BackHandler(onBack = onClose)

    fun card(): PendingCard = PendingCard(
        expression = expression.trim(),
        tashkeel = tashkeel.trim(),
        meaning = meaning.trim(),
        sentence = sentence.trim(),
        root = root.trim(),
        dictionary = dictionary.trim(),
        notes = notes.trim(),
    )

    fun saveLocally(messageRes: Int) {
        PendingStore.add(context, card())
        Toast.makeText(context, messageRes, Toast.LENGTH_SHORT).show()
        onClose()
    }

    fun addViaAnkiDroid(bridge: AnkiDroidBridge) {
        val deckName = HaqomuPrefs.ankiDeck(context)
        val modelId = bridge.findModelId(AnkiModel.MODEL_NAME)
        if (modelId == null) {
            saveLocally(R.string.anki_card_no_model)
            return
        }
        var deckId = bridge.findDeckId(deckName)
        if (deckId == null) {
            deckId = bridge.createDeck(deckName)
        }
        if (deckId == null) {
            saveLocally(R.string.anki_card_pending)
            return
        }
        if (bridge.findDuplicates(modelId, expression.trim())) {
            errorText = context.getString(R.string.anki_card_duplicate)
            return
        }
        val noteId = bridge.addNote(modelId, deckId, card().toFields(), setOf("haqomu"))
        if (noteId != null) {
            Toast.makeText(context, R.string.anki_card_added, Toast.LENGTH_SHORT).show()
            onClose()
        } else {
            saveLocally(R.string.anki_card_pending)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            addViaAnkiDroid(AnkiDroidBridge(context))
        } else {
            saveLocally(R.string.anki_card_permission)
        }
    }

    fun performSave() {
        errorText = null
        if (!PendingCard.isValid(expression, meaning)) {
            errorText = context.getString(R.string.anki_card_invalid)
            return
        }
        val bridge = AnkiDroidBridge(context)
        if (bridge.isAnkiDroidInstalled()) {
            if (bridge.hasWritePermission()) {
                addViaAnkiDroid(bridge)
            } else {
                permissionLauncher.launch(AnkiDroidBridge.PERMISSION)
            }
        } else {
            saveLocally(R.string.anki_card_pending)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                TextButton(onClick = onClose) {
                    Text(stringResource(R.string.nav_back))
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = stringResource(R.string.anki_card_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(Modifier.weight(1f))
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Field(value = expression, onChange = { expression = it }, label = stringResource(R.string.anki_card_expression))
                Field(value = tashkeel, onChange = { tashkeel = it }, label = stringResource(R.string.anki_card_tashkeel))
                Field(
                    value = meaning,
                    onChange = { meaning = it },
                    label = stringResource(R.string.anki_card_meaning),
                    minLines = 3,
                )
                Field(
                    value = sentence,
                    onChange = { sentence = it },
                    label = stringResource(R.string.anki_card_sentence),
                    minLines = 2,
                )
                Field(value = root, onChange = { root = it }, label = stringResource(R.string.anki_card_root))
                Field(value = dictionary, onChange = { dictionary = it }, label = stringResource(R.string.anki_card_dictionary))
                Field(
                    value = notes,
                    onChange = { notes = it },
                    label = stringResource(R.string.anki_card_notes),
                    minLines = 2,
                )
            }

            errorText?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(vertical = 6.dp),
                )
            }

            Row(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                TextButton(onClick = onClose) {
                    Text(stringResource(R.string.popup_close))
                }
                Spacer(Modifier.weight(1f))
                Button(onClick = { performSave() }) {
                    Text(
                        if (ankidroidInstalled) {
                            stringResource(R.string.anki_card_save)
                        } else {
                            stringResource(R.string.anki_card_save_local)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun Field(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    minLines: Int = 1,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        minLines = minLines,
        singleLine = minLines == 1,
        modifier = Modifier.fillMaxWidth(),
    )
}
