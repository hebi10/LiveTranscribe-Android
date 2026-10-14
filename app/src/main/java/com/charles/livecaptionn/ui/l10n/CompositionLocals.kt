package com.charles.livecaptionn.ui.l10n

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf

val LocalUiStrings = staticCompositionLocalOf { KoreanUiStrings.values }

@Composable
fun UiStringsProvider(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalUiStrings provides KoreanUiStrings.values) {
        content()
    }
}

@Composable
fun rememberUiStrings(): UiStrings = LocalUiStrings.current
