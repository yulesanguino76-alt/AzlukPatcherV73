package com.azluk.patcher.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// ── Azluk V7 color palette ────────────────────────────────────────────────────
// Inspired by the new logo: deep navy + electric blue
val AzlukBlue        = Color(0xFF2B7FFF)
val AzlukBlueDim     = Color(0xFF1A5FCC)
val AzlukBlueGlow    = Color(0xFF5BA3FF)
val AzlukBg          = Color(0xFF0A0D14)
val AzlukSurface     = Color(0xFF111620)
val AzlukSurface2    = Color(0xFF18202E)
val AzlukSurfaceVar  = Color(0xFF1E2840)
val AzlukOnBg        = Color(0xFFE8EDF5)
val AzlukOnSurface   = Color(0xFFB8C5D8)
val AzlukError       = Color(0xFFFF4B6E)
val AzlukSuccess     = Color(0xFF00D68F)
val AzlukWarning     = Color(0xFFFFB347)

private val DarkColorScheme = darkColorScheme(
    primary          = AzlukBlue,
    onPrimary        = Color.White,
    primaryContainer = AzlukBlueDim,
    secondary        = AzlukBlueGlow,
    background       = AzlukBg,
    surface          = AzlukSurface,
    surfaceVariant   = AzlukSurfaceVar,
    onBackground     = AzlukOnBg,
    onSurface        = AzlukOnSurface,
    error            = AzlukError,
    onError          = Color.White,
)

@Composable
fun AzlukTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography  = Typography(),
        content     = content
    )
}
