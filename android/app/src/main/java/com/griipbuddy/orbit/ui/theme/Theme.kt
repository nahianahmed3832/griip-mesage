package com.griipbuddy.orbit.ui.theme

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
    // Material 3 supplies low-level widgets (text fields, dialogs, menus, switches, sheets); its scheme is
    // derived from the same tokens so they match the Griip components.
    val scheme = if (dark) {
        darkColorScheme(
            primary = c.primary, onPrimary = c.onPrimary, primaryContainer = c.primarySoft, onPrimaryContainer = c.primaryDeep,
            secondary = c.lavender, tertiary = c.peach, error = c.danger,
            background = c.background, onBackground = c.textPrimary, surface = c.surface, onSurface = c.textPrimary,
            surfaceVariant = c.surfaceVariant, onSurfaceVariant = c.textSecondary, outline = c.border, outlineVariant = c.border,
            surfaceContainerLowest = c.background, surfaceContainerLow = c.surface, surfaceContainer = c.surface,
            surfaceContainerHigh = c.glassStrong, surfaceContainerHighest = c.surfaceVariant,
        )
    } else {
        lightColorScheme(
            primary = c.primary, onPrimary = c.onPrimary, primaryContainer = c.primarySoft, onPrimaryContainer = c.primaryDeep,
            secondary = c.lavender, tertiary = c.peach, error = c.danger,
            background = c.background, onBackground = c.textPrimary, surface = c.surface, onSurface = c.textPrimary,
            surfaceVariant = c.surfaceVariant, onSurfaceVariant = c.textSecondary, outline = c.border, outlineVariant = c.border,
            surfaceContainerLowest = c.surface, surfaceContainerLow = c.surface, surfaceContainer = c.surface,
            surfaceContainerHigh = c.surface, surfaceContainerHighest = c.surfaceVariant,
        )
    }
    CompositionLocalProvider(LocalGriipColors provides c, LocalGriipType provides GriipTypography) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
