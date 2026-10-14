package com.gantoine.muxer.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

val BrandGreen = Color(0xFF3DDC84)

private val brandDark = darkColorScheme(
    primary = Color(0xFF6FE3A2),
    onPrimary = Color(0xFF00391D),
    primaryContainer = Color(0xFF00522D),
    onPrimaryContainer = Color(0xFF8CFFBD),
    secondary = Color(0xFFB5CCBB),
    onSecondary = Color(0xFF213529),
    secondaryContainer = Color(0xFF374B3F),
    onSecondaryContainer = Color(0xFFD1E8D7),
    tertiary = Color(0xFFA3CDDE),
    onTertiary = Color(0xFF033543),
    tertiaryContainer = Color(0xFF224C5A),
    onTertiaryContainer = Color(0xFFBFE9FB),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF0E1210),
    onBackground = Color(0xFFDEE4DE),
    surface = Color(0xFF0E1210),
    onSurface = Color(0xFFDEE4DE),
    surfaceVariant = Color(0xFF3F4943),
    onSurfaceVariant = Color(0xFFBFC9C0),
    outline = Color(0xFF89938B),
    outlineVariant = Color(0xFF3F4943),
    surfaceContainerLowest = Color(0xFF090D0B),
    surfaceContainerLow = Color(0xFF161A18),
    surfaceContainer = Color(0xFF1A1F1C),
    surfaceContainerHigh = Color(0xFF252926),
    surfaceContainerHighest = Color(0xFF303431),
    inverseSurface = Color(0xFFDEE4DE),
    inverseOnSurface = Color(0xFF2B312D),
    inversePrimary = Color(0xFF006D3C),
)

private val brandLight = lightColorScheme(
    primary = Color(0xFF006D3C),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF8CFFBD),
    onPrimaryContainer = Color(0xFF00210E),
    secondary = Color(0xFF4F6354),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD1E8D7),
    onSecondaryContainer = Color(0xFF0D1F14),
    tertiary = Color(0xFF3B6472),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFBFE9FB),
    onTertiaryContainer = Color(0xFF001F28),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF6FBF4),
    onBackground = Color(0xFF181D19),
    surface = Color(0xFFF6FBF4),
    onSurface = Color(0xFF181D19),
    surfaceVariant = Color(0xFFDCE5DC),
    onSurfaceVariant = Color(0xFF404943),
    outline = Color(0xFF707972),
    outlineVariant = Color(0xFFC0C9C0),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF0F5EE),
    surfaceContainer = Color(0xFFEAEFE8),
    surfaceContainerHigh = Color(0xFFE5EAE3),
    surfaceContainerHighest = Color(0xFFDFE4DD),
    inverseSurface = Color(0xFF2C322E),
    inverseOnSurface = Color(0xFFEEF2EC),
    inversePrimary = Color(0xFF6FE3A2),
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun CmuxTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && dark -> dynamicDarkColorScheme(context)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> dynamicLightColorScheme(context)
        dark -> brandDark
        else -> brandLight
    }
    MaterialExpressiveTheme(colorScheme = colors, motionScheme = MotionScheme.expressive(), content = content)
}
