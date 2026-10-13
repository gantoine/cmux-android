package dev.cmuxandroid.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val colors = darkColorScheme(
    primary = Color(0xFF8CAAEE),
    onPrimary = Color(0xFF1E1F29),
    primaryContainer = Color(0xFF8CAAEE),
    onPrimaryContainer = Color(0xFF1E1F29),
    secondary = Color(0xFFA6D189),
    secondaryContainer = Color(0xFF414559),
    onSecondaryContainer = Color(0xFFC6D0F5),
    background = Color(0xFF1E1F29),
    onBackground = Color(0xFFC6D0F5),
    surface = Color(0xFF232634),
    onSurface = Color(0xFFC6D0F5),
    surfaceVariant = Color(0xFF303446),
    onSurfaceVariant = Color(0xFFA5ADCE),
    error = Color(0xFFE78284),
)

@Composable
fun CmuxTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, content = content)
}
