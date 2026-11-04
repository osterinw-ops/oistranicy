package ru.moistranicy.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Violet = Color(0xFF7B6CF6)
val VioletDeep = Color(0xFF3A2D9C)
val Coral = Color(0xFFFF7A59)

private val Dark = darkColorScheme(
    primary = Color(0xFF9D91FF),
    onPrimary = Color(0xFF1B1347),
    primaryContainer = Color(0xFF3A2D9C),
    onPrimaryContainer = Color(0xFFE5E0FF),
    secondary = Coral,
    onSecondary = Color(0xFF3A0F00),
    tertiary = Coral,
    background = Color(0xFF15131F),
    onBackground = Color(0xFFE8E5F2),
    surface = Color(0xFF15131F),
    onSurface = Color(0xFFE8E5F2),
    surfaceVariant = Color(0xFF2A2638),
    onSurfaceVariant = Color(0xFFB9B4D6),
    surfaceContainer = Color(0xFF1E1B2B),
    surfaceContainerHigh = Color(0xFF262236),
    outline = Color(0xFF4A4560),
)

private val Light = lightColorScheme(
    primary = Color(0xFF5B4BDB),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE5E0FF),
    onPrimaryContainer = Color(0xFF1B1347),
    secondary = Color(0xFFE5603E),
    tertiary = Color(0xFFE5603E),
    background = Color(0xFFFBFAFF),
    surface = Color(0xFFFBFAFF),
    surfaceVariant = Color(0xFFECE9F7),
    onSurfaceVariant = Color(0xFF555070),
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        content = content,
    )
}
