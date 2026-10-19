package com.meldiq.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.foundation.background
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.unit.dp

enum class ThemeMode { SYSTEM, LIGHT, DARK }

object Griip {
    val colors: GriipColors
        @Composable @ReadOnlyComposable get() = LocalGriipColors.current
    val type: GriipType
        @Composable @ReadOnlyComposable get() = LocalGriipType.current
}

@Composable
fun GriipTheme(mode: ThemeMode = ThemeMode.SYSTEM, palette: String = MeldiqPalettes.DEFAULT, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    CompositionLocalProvider(LocalGriipType provides GriipTypography) {
        ProvideGriipPalette(MeldiqPalettes.colors(palette, dark), content)
    }
}

/**
 * Draws [content] in one palette (the theme's, or a fixed one such as the call screen's). Every Griip component and the
 * Material widgets underneath follow it.
 */
@Composable
fun ProvideGriipPalette(colors: GriipColors, content: @Composable () -> Unit) {
    // Type and Material shapes/typography carry over from the enclosing theme; only the colours change.
    CompositionLocalProvider(LocalGriipColors provides colors) {
        MaterialTheme(colorScheme = griipColorScheme(colors), content = content)
    }
}

/**
 * Material 3 supplies low-level widgets (text fields, dialogs, menus, switches, sheets); its scheme is derived from the
 * same tokens so they match the Griip components.
 */
fun griipColorScheme(c: GriipColors): ColorScheme = if (c.isDark) {
    darkColorScheme(
        primary = c.primary, onPrimary = c.onPrimary, primaryContainer = c.primarySoft, onPrimaryContainer = c.primaryDeep,
        secondary = c.lavender, tertiary = c.peach, error = c.danger,
        background = c.background, onBackground = c.textPrimary, surface = c.surface, onSurface = c.textPrimary,
        surfaceVariant = c.surfaceVariant, onSurfaceVariant = c.textSecondary, outline = c.outline, outlineVariant = c.border,
        surfaceContainerLowest = c.background, surfaceContainerLow = c.surface, surfaceContainer = c.surface,
        surfaceContainerHigh = c.glassStrong, surfaceContainerHighest = c.surfaceVariant,
    )
} else {
    lightColorScheme(
        primary = c.primary, onPrimary = c.onPrimary, primaryContainer = c.primarySoft, onPrimaryContainer = c.primaryDeep,
        secondary = c.lavender, tertiary = c.peach, error = c.danger,
        background = c.background, onBackground = c.textPrimary, surface = c.surface, onSurface = c.textPrimary,
        surfaceVariant = c.surfaceVariant, onSurfaceVariant = c.textSecondary, outline = c.outline, outlineVariant = c.border,
        surfaceContainerLowest = c.surface, surfaceContainerLow = c.surface, surfaceContainer = c.surface,
        surfaceContainerHigh = c.surface, surfaceContainerHighest = c.surfaceVariant,
    )
}

/**
 * Chat wallpapers (Settings › Chats): drawn by the app (no images to download), quiet enough that bubbles stay
 * readable in light and dark mode.
 */
object Wallpapers {
    data class Wallpaper(val id: String, val label: String)

    val ALL = listOf(Wallpaper("none", "None"), Wallpaper("mint", "Mint"), Wallpaper("dusk", "Dusk"), Wallpaper("dots", "Dots"), Wallpaper("waves", "Waves"))

    fun modifier(id: String, c: GriipColors): androidx.compose.ui.Modifier = when (id) {
        "mint" -> androidx.compose.ui.Modifier.background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(c.primarySoft, c.background)))
        "dusk" -> androidx.compose.ui.Modifier.background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(c.lavenderSoft, c.background, c.primarySoft)))
        "dots" -> androidx.compose.ui.Modifier.background(c.background).drawBehind {
            val step = 22.dp.toPx()
            val r = 1.6.dp.toPx()
            var y = step / 2
            while (y < size.height) {
                var x = step / 2
                while (x < size.width) {
                    drawCircle(c.primary.copy(alpha = 0.12f), r, androidx.compose.ui.geometry.Offset(x, y))
                    x += step
                }
                y += step
            }
        }
        "waves" -> androidx.compose.ui.Modifier.background(c.background).drawBehind {
            val gap = 34.dp.toPx()
            var y = gap / 2
            while (y < size.height) {
                val path = androidx.compose.ui.graphics.Path()
                path.moveTo(0f, y)
                var x = 0f
                while (x <= size.width) {
                    path.lineTo(x, y + kotlin.math.sin(x / 40.dp.toPx() * Math.PI.toFloat()) * 5.dp.toPx())
                    x += 6f
                }
                drawPath(path, c.primary.copy(alpha = 0.10f), style = androidx.compose.ui.graphics.drawscope.Stroke(1.2.dp.toPx()))
                y += gap
            }
        }
        else -> androidx.compose.ui.Modifier
    }
}
