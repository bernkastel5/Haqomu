package com.bernkastel5.haqomu.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bernkastel5.haqomu.R

@Composable
fun HomeScreen(onOpen: (Screen) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 48.dp)
    ) {
        Text(
            text = stringResource(R.string.app_name),
            fontFamily = FontFamily.Serif,
            fontSize = 44.sp,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = stringResource(R.string.home_tagline),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp, bottom = 32.dp)
        )

        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

        HomeRow(R.string.home_import, R.string.home_import_sub) { onOpen(Screen.Import) }
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
        HomeRow(R.string.home_anki, R.string.home_anki_sub) { onOpen(Screen.Anki) }
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
        HomeRow(R.string.home_preview, R.string.home_preview_sub) { onOpen(Screen.PopupPreview) }
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
        HomeRow(R.string.home_settings, R.string.home_settings_sub) { onOpen(Screen.Settings) }
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
        HomeRow(R.string.home_about, R.string.home_about_sub) { onOpen(Screen.About) }
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
    }
}

@Composable
private fun HomeRow(title: Int, subtitle: Int, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = stringResource(subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        Text(
            text = "›",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp)
        )
    }
}
