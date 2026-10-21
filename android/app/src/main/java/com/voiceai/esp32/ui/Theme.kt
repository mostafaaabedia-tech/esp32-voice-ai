package com.voiceai.esp32.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color

// Deep navy + teal, the same colours as the OLED face
private val DarkColors = darkColorScheme(
    primary = Color(0xFF2EC4C9),
    onPrimary = Color(0xFF00262B),
    primaryContainer = Color(0x332EC4C9),
    onPrimaryContainer = Color(0xFF8FE9EC),
    secondary = Color(0xFF7FA8FF),        // input tokens
    tertiary = Color(0xFF6FD08C),         // online / good
    background = Color(0xFF0B1826),
    onBackground = Color(0xFFE6EEF5),
    surface = Color(0xFF122437),
    onSurface = Color(0xFFE6EEF5),
    surfaceVariant = Color(0xFF1A3150),
    onSurfaceVariant = Color(0xFF9DB1C5),
    surfaceContainer = Color(0xFF122437),
    surfaceContainerHigh = Color(0xFF1A3150),
    surfaceContainerLow = Color(0xFF0F1F30),
    outline = Color(0xFF61788F),
    outlineVariant = Color(0xFF223B57),
    error = Color(0xFFFF7A6E),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF14808C),
    onPrimary = Color.White,
    primaryContainer = Color(0x1A14808C),
    onPrimaryContainer = Color(0xFF0B5560),
    secondary = Color(0xFF3F6FD8),
    tertiary = Color(0xFF2E9A50),
    background = Color(0xFFF3F7FA),
    onBackground = Color(0xFF0F2236),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0F2236),
    surfaceVariant = Color(0xFFE5EEF5),
    onSurfaceVariant = Color(0xFF52687D),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFE5EEF5),
    surfaceContainerLow = Color(0xFFF8FBFD),
    outline = Color(0xFF8BA0B3),
    outlineVariant = Color(0xFFD5E1EB),
    error = Color(0xFFD1453B),
)

/** Logo gradient: teal -> ocean blue -> navy */
val Teal = Color(0xFF2EC4C9)
val Ocean = Color(0xFF3B82F6)
val Navy = Color(0xFF15406A)
val BrandGradient = listOf(Teal, Ocean)

val Warn = Color(0xFFE7B25B)

@Composable
fun VoiceAITheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) DarkColors else LightColors
    MaterialTheme(colorScheme = colors) {
        // Without this, every Text that has no colour of its own is drawn BLACK
        // (invisible on the dark navy background). Make the default text light.
        CompositionLocalProvider(LocalContentColor provides colors.onBackground, content = content)
    }
}
