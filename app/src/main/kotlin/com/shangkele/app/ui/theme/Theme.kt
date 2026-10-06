package com.shangkele.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * 主题。
 *
 * 不用 dynamicColor：MagicOS 的动态取色在四曲屏上偶有色偏，
 * 且课程块配色需要与主题底色保持稳定对比度（见 docs/07 §七）。
 */
private val LightColors = lightColorScheme(
    primary = Color(0xFF3B6FD8),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD9E4FF),
    onPrimaryContainer = Color(0xFF0B2347),
    secondary = Color(0xFF5A5D72),
    secondaryContainer = Color(0xFFE6E7F2),
    onSecondaryContainer = Color(0xFF1B1B22),
    background = Color(0xFFFAFAFA),
    onBackground = Color(0xFF1A1C1E),
    surface = Color(0xFFFAFAFA),
    onSurface = Color(0xFF1A1C1E),
    surfaceVariant = Color(0xFFEFF0F4),
    onSurfaceVariant = Color(0xFF44474E),
    outlineVariant = Color(0xFFD8DAE0),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7FA6F0),
    onPrimary = Color(0xFF04203F),
    primaryContainer = Color(0xFF17293F),
    onPrimaryContainer = Color(0xFFD9E4FF),
    secondary = Color(0xFFC3C6DA),
    secondaryContainer = Color(0xFF262833),
    onSecondaryContainer = Color(0xFFE3E4EC),
    background = Color(0xFF121212),
    onBackground = Color(0xFFE3E3E3),
    surface = Color(0xFF121212),
    onSurface = Color(0xFFE3E3E3),
    surfaceVariant = Color(0xFF23252A),
    onSurfaceVariant = Color(0xFFC4C6CF),
    outlineVariant = Color(0xFF3A3D44),
)

@Composable
fun ShangKeLeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
