package com.robertlangat.todoplanner.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.robertlangat.todoplanner.data.ThemeMode

data class Accent(val name: String, val light: Color, val dark: Color)

/** Index 0 is the wallpaper-based dynamic palette on Android 12+, otherwise Blue. */
val Accents = listOf(
    Accent("Wallpaper", Color(0xFF2E5BFF), Color(0xFFB5C4FF)),
    Accent("Blue", Color(0xFF2E5BFF), Color(0xFFB5C4FF)),
    Accent("Teal", Color(0xFF00696E), Color(0xFF4FD8DF)),
    Accent("Purple", Color(0xFF6E3FD1), Color(0xFFD0BCFF)),
    Accent("Green", Color(0xFF2E6B1E), Color(0xFF94D97C)),
    Accent("Orange", Color(0xFF9A4600), Color(0xFFFFB68A)),
    Accent("Pink", Color(0xFFB0236A), Color(0xFFFFB0CC)),
)

private fun lightFor(a: Accent): ColorScheme = lightColorScheme(
    primary = a.light, onPrimary = Color.White,
    primaryContainer = a.light.copy(alpha = 0.16f).compositeOver(Color.White), onPrimaryContainer = a.light.darken(),
    secondary = a.light.darken(), tertiary = Color(0xFF7D5260),
)

private fun darkFor(a: Accent): ColorScheme = darkColorScheme(
    primary = a.dark, onPrimary = Color(0xFF101418),
    primaryContainer = a.dark.copy(alpha = 0.24f).compositeOver(Color(0xFF1B1F24)), onPrimaryContainer = a.dark,
    secondary = a.dark, tertiary = Color(0xFFEFB8C8),
)

private fun Color.darken(f: Float = 0.7f) = Color(red * f, green * f, blue * f, alpha)
private fun Color.compositeOver(bg: Color): Color {
    val a = alpha
    return Color(red * a + bg.red * (1 - a), green * a + bg.green * (1 - a), blue * a + bg.blue * (1 - a), 1f)
}

@Composable
fun TodoTheme(mode: ThemeMode, accentIndex: Int, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val accent = Accents.getOrElse(accentIndex) { Accents[0] }
    val ctx = LocalContext.current
    val scheme = when {
        accentIndex == 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkFor(accent)
        else -> lightFor(accent)
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

/** Priority colors are always paired with a text/shape marker so color is never the only signal. */
@Composable
fun priorityColor(priority: Int): Color = when (priority) {
    3 -> Color(0xFFD32F2F)
    2 -> Color(0xFFF57C00)
    1 -> Color(0xFF1976D2)
    else -> MaterialTheme.colorScheme.outline
}
