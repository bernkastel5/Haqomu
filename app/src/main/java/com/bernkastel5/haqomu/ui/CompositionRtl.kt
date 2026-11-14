package com.bernkastel5.haqomu.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection

/**
 * Блок арабского текста всегда в RTL, независимо от языка интерфейса.
 * Общий для предпросмотра попапа и реального ACTION_PROCESS_TEXT-попапа.
 */
@Composable
internal fun CompositionRtl(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        content()
    }
}
