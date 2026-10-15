package com.griipbuddy.message.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable

enum class ThemeMode { SYSTEM, LIGHT, DARK }

object Griip {
    val colors: GriipColors
        @Composable @ReadOnlyComposable get() = LocalGriipColors.current
    val type: GriipType
        @Composable @ReadOnlyComposable get() = LocalGriipType.current
}

@Composable
fun GriipTheme(mode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val c = if (dark) DarkColors else LightColors
    // Material3 is used only for low-level widgets (text fields, dialogs, menus); every screen-level
    // surface comes from the Griip components so the reference's glass language stays consistent.
    val scheme = if (dark) {
        darkColorScheme(
            primary = c.primary, onPrimary = c.onPrimary, primaryContainer = c.primarySoft, onPrimaryContainer = c.primaryDeep,
            secondary = c.lavender, tertiary = c.peach, error = c.danger,
            background = c.canvasMid, onBackground = c.textPrimary, surface = c.glassStrong, onSurface = c.textPrimary,
            surfaceVariant = c.glass, onSurfaceVariant = c.textSecondary, outline = c.hairline, outlineVariant = c.hairline,
            surfaceContainer = c.canvasMid, surfaceContainerHigh = c.glassStrong, surfaceContainerHighest = c.glassStrong,
        )
    } else {
        lightColorScheme(
            primary = c.primary, onPrimary = c.onPrimary, primaryContainer = c.primarySoft, onPrimaryContainer = c.primaryDeep,
            secondary = c.lavender, tertiary = c.peach, error = c.danger,
            background = c.canvasMid, onBackground = c.textPrimary, surface = c.canvasMid, onSurface = c.textPrimary,
            surfaceVariant = c.primarySoft, onSurfaceVariant = c.textSecondary, outline = c.hairline, outlineVariant = c.hairline,
            surfaceContainer = c.canvasMid, surfaceContainerHigh = c.canvasMid, surfaceContainerHighest = c.canvasMid,
        )
    }
    CompositionLocalProvider(LocalGriipColors provides c, LocalGriipType provides GriipTypography) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
