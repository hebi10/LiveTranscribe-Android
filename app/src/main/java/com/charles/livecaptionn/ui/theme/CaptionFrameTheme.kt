package com.charles.livecaptionn.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

internal val CaptionFrameLightColors = lightColorScheme(
    primary = Color(0xFF191919),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFF5F5F5),
    onPrimaryContainer = Color(0xFF171717),
    secondary = Color(0xFF444444),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFEAEAEA),
    onSecondaryContainer = Color(0xFF171717),
    tertiary = Color(0xFF666666),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF0F0F0),
    onTertiaryContainer = Color(0xFF171717),
    error = Color(0xFFB42318),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFEBE9),
    onErrorContainer = Color(0xFF721C17),
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF171717),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF171717),
    surfaceVariant = Color(0xFFF5F5F5),
    onSurfaceVariant = Color(0xFF666666),
    outline = Color(0xFF8A8A8A),
    outlineVariant = Color(0xFFDEDEDE),
    inverseSurface = Color(0xFF171717),
    inverseOnSurface = Color(0xFFFFFFFF),
    inversePrimary = Color(0xFFEAEAEA),
    surfaceDim = Color(0xFFF0F0F0),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFAFAFA),
    surfaceContainer = Color(0xFFF5F5F5),
    surfaceContainerHigh = Color(0xFFEEEEEE),
    surfaceContainerHighest = Color(0xFFE5E5E5),
    surfaceTint = Color(0xFF191919)
)

internal val CaptionFrameDarkColors = darkColorScheme(
    primary = Color(0xFFF1F1F1),
    onPrimary = Color(0xFF171717),
    primaryContainer = Color(0xFF303030),
    onPrimaryContainer = Color(0xFFF5F5F5),
    secondary = Color(0xFFD5D5D5),
    onSecondary = Color(0xFF171717),
    secondaryContainer = Color(0xFF333333),
    onSecondaryContainer = Color(0xFFF5F5F5),
    tertiary = Color(0xFFB5B5B5),
    onTertiary = Color(0xFF101010),
    tertiaryContainer = Color(0xFF303030),
    onTertiaryContainer = Color(0xFFF5F5F5),
    error = Color(0xFFFF8D82),
    onError = Color(0xFF410002),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF101010),
    onBackground = Color(0xFFF5F5F5),
    surface = Color(0xFF101010),
    onSurface = Color(0xFFF5F5F5),
    surfaceVariant = Color(0xFF202020),
    onSurfaceVariant = Color(0xFFB5B5B5),
    outline = Color(0xFF8A8A8A),
    outlineVariant = Color(0xFF404040),
    inverseSurface = Color(0xFFF5F5F5),
    inverseOnSurface = Color(0xFF171717),
    inversePrimary = Color(0xFF303030),
    surfaceDim = Color(0xFF101010),
    surfaceBright = Color(0xFF353535),
    surfaceContainerLowest = Color(0xFF0C0C0C),
    surfaceContainerLow = Color(0xFF191919),
    surfaceContainer = Color(0xFF202020),
    surfaceContainerHigh = Color(0xFF292929),
    surfaceContainerHighest = Color(0xFF333333),
    surfaceTint = Color(0xFFF1F1F1)
)

@Composable
fun CaptionFrameTheme(darkTheme: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) CaptionFrameDarkColors else CaptionFrameLightColors,
        content = content
    )
}
