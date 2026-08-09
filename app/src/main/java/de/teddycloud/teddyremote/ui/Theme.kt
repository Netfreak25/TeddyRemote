package de.teddycloud.teddyremote.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import de.teddycloud.teddyremote.model.ThemeMode

private val LightColors = lightColorScheme(
    primary = Color(0xFF4255D7),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDFE2FF),
    secondary = Color(0xFF006C6B),
    tertiary = Color(0xFF77536D),
    surface = Color(0xFFFAF8FF),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFBBC3FF),
    onPrimary = Color(0xFF0E247F),
    primaryContainer = Color(0xFF293CA7),
    secondary = Color(0xFF77D7D5),
    tertiary = Color(0xFFE6B9D8),
    surface = Color(0xFF121318),
)

@Composable
fun TeddyRemoteTheme(themeMode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val context = LocalContext.current
    val colors = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else if (dark) DarkColors else LightColors
    MaterialTheme(colorScheme = colors, content = content)
}
