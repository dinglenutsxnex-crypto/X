package com.rootdroid.inspector.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily

val MonoFamily = FontFamily.Monospace

private val DarkColors = darkColorScheme(
    primary          = Accent,
    onPrimary        = Color(0xFF0D1117),
    primaryContainer = AccentMuted,
    secondary        = StatusGreen,
    onSecondary      = Color(0xFF0D1117),
    background       = Background,
    onBackground     = TextPrimary,
    surface          = Surface,
    onSurface        = TextPrimary,
    surfaceVariant   = SurfaceHigh,
    onSurfaceVariant = TextSecond,
    outline          = Border,
    outlineVariant   = BorderSub,
    error            = StatusRed,
    onError          = Color.White,
)

@Composable
fun RootDroidTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        content = content,
    )
}
