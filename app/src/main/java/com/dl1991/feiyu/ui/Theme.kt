package com.dl1991.feiyu.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * 大肥鱼直播 theme: clean industrial. Concrete-grey ground, white panels with a hairline
 * rule, graphite ink, one safety-orange accent, small mono labels. No blur, no gradients.
 */
val LocalFyDark = staticCompositionLocalOf { false }

object FyPalette {
    val Orange = Color(0xFFE8590C)
    val OrangeDark = Color(0xFFFF7A2E)
    val Ink = Color(0xFF17181A)
    val Graphite = Color(0xFF2A2C30)
    val Steel = Color(0xFF5D6066)
    val Concrete = Color(0xFFE9E9E6)
    val Panel = Color(0xFFFFFFFF)
    val Rule = Color(0xFFD3D3CE)
    val NightGround = Color(0xFF121315)
    val NightPanel = Color(0xFF1B1C1F)
    val NightRule = Color(0xFF2E3035)
    val Ok = Color(0xFF2F8F4E)
    val OkDark = Color(0xFF5BC27F)
    val Warn = Color(0xFFB7791F)
    val WarnDark = Color(0xFFE3A94A)
    val Err = Color(0xFFC0392B)
    val ErrDark = Color(0xFFF0776A)
    val Live = Color(0xFFE5322D)
}

private val Light = lightColorScheme(
    primary = FyPalette.Orange, onPrimary = Color.White,
    secondary = FyPalette.Graphite, onSecondary = Color.White,
    background = FyPalette.Concrete, onBackground = FyPalette.Ink,
    surface = FyPalette.Panel, onSurface = FyPalette.Ink,
    surfaceVariant = Color(0xFFF1F1EE), onSurfaceVariant = FyPalette.Steel,
    outline = FyPalette.Rule, error = FyPalette.Err,
)

private val Dark = darkColorScheme(
    primary = FyPalette.OrangeDark, onPrimary = Color(0xFF1A0E06),
    secondary = Color(0xFFC9CBD0), onSecondary = FyPalette.Ink,
    background = FyPalette.NightGround, onBackground = Color(0xFFE8E8E6),
    surface = FyPalette.NightPanel, onSurface = Color(0xFFE8E8E6),
    surfaceVariant = Color(0xFF24262A), onSurfaceVariant = Color(0xFF9EA2A9),
    outline = FyPalette.NightRule, error = FyPalette.ErrDark,
)

@Composable
fun FyTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalFyDark provides dark) {
        MaterialTheme(colorScheme = if (dark) Dark else Light, content = content)
    }
}
