package com.voiceai.esp32.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFFD97757),
    onPrimary = Color.White,
    primaryContainer = Color(0x24D97757),
    onPrimaryContainer = Color(0xFFF0A483),
    secondary = Color(0xFF8FA8D8),        // input tokens
    tertiary = Color(0xFF7CC47F),         // online / good
    background = Color(0xFF1A1915),
    onBackground = Color(0xFFECE9E1),
    surface = Color(0xFF23221E),
    onSurface = Color(0xFFECE9E1),
    surfaceVariant = Color(0xFF2D2C27),
    onSurfaceVariant = Color(0xFF9B978C),
    surfaceContainer = Color(0xFF23221E),
    surfaceContainerHigh = Color(0xFF2D2C27),
    surfaceContainerLow = Color(0xFF1F1E1A),
    outline = Color(0xFF6D6A62),
    outlineVariant = Color(0xFF3A3832),
    error = Color(0xFFE5675B),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFFC4623F),
    onPrimary = Color.White,
    primaryContainer = Color(0x1AC4623F),
    onPrimaryContainer = Color(0xFF9A4527),
    secondary = Color(0xFF5B7CC0),
    tertiary = Color(0xFF3E9A47),
    background = Color(0xFFF8F6F1),
    onBackground = Color(0xFF23211C),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF23211C),
    surfaceVariant = Color(0xFFF0EDE5),
    onSurfaceVariant = Color(0xFF6F6B62),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFF0EDE5),
    surfaceContainerLow = Color(0xFFFBFAF7),
    outline = Color(0xFFA19D93),
    outlineVariant = Color(0xFFE2DED4),
    error = Color(0xFFC4473B),
)

val Warn = Color(0xFFE7B25B)

@Composable
fun VoiceAITheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
