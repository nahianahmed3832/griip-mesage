package com.griipbuddy.orbit.ui.theme

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
fun GriipTheme(mode: ThemeMode = ThemeMode.SYSTEM, accent: String = Accents.DEFAULT, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    CompositionLocalProvider(LocalGriipType provides GriipTypography) {
        ProvideGriipPalette(Accents.apply(if (dark) DarkColors else LightColors, accent, dark), content)
    }
}

/**
 * Draws [content] in one fixed palette, whatever the app's theme setting: [IvoryColors] for sign-in and sign-up,
 * [CocoaColors] for the premium dark surfaces (docs/DESIGN_SYSTEM.md). Every Griip component and the Material widgets
 * underneath follow it.
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
 * Accent colours (Settings › Chats). Orbit green is the default; the others change the action colour, links and your
 * own message bubbles, keeping contrast in light and dark mode. Unit-tested in `AccentsTest`.
 */
object Accents {
    const val DEFAULT = "orbit"

    data class Accent(val id: String, val label: String, val light: Long, val lightDeep: Long, val lightSoft: Long, val lightBubble: Long,
                      val dark: Long, val darkDeep: Long, val darkSoft: Long, val darkAction: Long, val darkBubble: Long)

    val ALL = listOf(
        Accent("orbit", "Orbit green", 0xFF087F56, 0xFF06633F, 0xFFE1F3EA, 0xFFDCF2E6, 0xFF3CC48F, 0xFF72DDB2, 0xFF133B2B, 0xFF17865D, 0xFF15513A),
        Accent("teal", "Teal", 0xFF0F8F8C, 0xFF075E5C, 0xFFDDF2F1, 0xFFD6EFEE, 0xFF35C2BE, 0xFF7ADCD8, 0xFF113A39, 0xFF16807D, 0xFF124A49),
        Accent("blue", "Blue", 0xFF2563C9, 0xFF163E82, 0xFFE2EBFA, 0xFFDDE7F9, 0xFF6B9BEA, 0xFFA8C4F4, 0xFF16264A, 0xFF2F63B8, 0xFF1C3566),
        Accent("violet", "Violet", 0xFF7044C8, 0xFF452780, 0xFFEDE5FA, 0xFFE7DEF8, 0xFFA584EC, 0xFFC9B6F5, 0xFF2A1C49, 0xFF6A47B8, 0xFF352262),
        Accent("amber", "Amber", 0xFFB86E00, 0xFF6E4200, 0xFFFBEFDA, 0xFFF8E8CC, 0xFFF0A93B, 0xFFF7CB85, 0xFF3D2A0E, 0xFFA36612, 0xFF4A3514),
    )

    fun of(id: String?): Accent = ALL.firstOrNull { it.id == id } ?: ALL.first()

    fun apply(base: GriipColors, id: String?, dark: Boolean): GriipColors {
        if (id == null || id == DEFAULT) return base
        val a = of(id)
        return if (dark) base.copy(
            primary = androidx.compose.ui.graphics.Color(a.dark), primaryDeep = androidx.compose.ui.graphics.Color(a.darkDeep),
            primarySoft = androidx.compose.ui.graphics.Color(a.darkSoft), actionStart = androidx.compose.ui.graphics.Color(a.darkAction),
            actionEnd = androidx.compose.ui.graphics.Color(a.darkAction), bubbleOutgoing = androidx.compose.ui.graphics.Color(a.darkBubble),
            accentStart = androidx.compose.ui.graphics.Color(a.dark), accentEnd = androidx.compose.ui.graphics.Color(a.darkDeep),
        ) else base.copy(
            primary = androidx.compose.ui.graphics.Color(a.light), primaryDeep = androidx.compose.ui.graphics.Color(a.lightDeep),
            primarySoft = androidx.compose.ui.graphics.Color(a.lightSoft), actionStart = androidx.compose.ui.graphics.Color(a.light),
            actionEnd = androidx.compose.ui.graphics.Color(a.light), bubbleOutgoing = androidx.compose.ui.graphics.Color(a.lightBubble),
            accentStart = androidx.compose.ui.graphics.Color(a.light), accentEnd = androidx.compose.ui.graphics.Color(a.lightDeep),
        )
    }
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
