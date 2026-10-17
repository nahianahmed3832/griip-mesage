package com.griipbuddy.orbit.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** The 2026-10-18 palettes (UI-001): readable text and visible input outlines (WCAG 2.2 contrast). */
class OrbitPalettesTest {
    private fun channel(v: Float): Double = if (v <= 0.03928f) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)

    private fun luminance(c: Color): Double = 0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)

    private fun contrast(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    private fun assertContrast(name: String, fg: Color, bg: Color, minimum: Double) {
        val ratio = contrast(fg, bg)
        assertTrue("$name is %.2f:1, needs %.1f:1".format(ratio, minimum), ratio >= minimum)
    }

    private fun assertReadable(palette: String, c: GriipColors) {
        val text = 4.5
        assertContrast("$palette primary text", c.textPrimary, c.background, 7.0)
        assertContrast("$palette secondary text", c.textSecondary, c.background, text)
        assertContrast("$palette secondary text on cards", c.textSecondary, c.surface, text)
        assertContrast("$palette secondary text on inputs", c.textSecondary, c.surfaceVariant, text)
        assertContrast("$palette tertiary text", c.textTertiary, c.background, text)
        assertContrast("$palette tertiary text on cards", c.textTertiary, c.surface, text)
        assertContrast("$palette links and focus", c.primary, c.background, text)
        assertContrast("$palette text on primary", c.onPrimary, c.primary, text)
        assertContrast("$palette button label (start)", c.onAction, c.actionStart, text)
        assertContrast("$palette button label (end)", c.onAction, c.actionEnd, text)
        assertContrast("$palette accent label (start)", c.onAccent, c.accentStart, text)
        assertContrast("$palette accent label (end)", c.onAccent, c.accentEnd, text)
        assertContrast("$palette accent text (start)", c.accentStart, c.background, text)
        assertContrast("$palette accent text (end)", c.accentEnd, c.background, text)
        assertContrast("$palette error text", c.danger, c.background, text)
        assertContrast("$palette error banner", c.danger, c.dangerSoft, text)
        assertContrast("$palette warning banner", c.warning, c.warningSoft, text)
        assertContrast("$palette success text", c.success, c.background, text)
        assertContrast("$palette success banner", c.success, c.successSoft, text)
        // Input boundaries are user-interface components: 3:1 against what surrounds them and what fills them.
        assertContrast("$palette input outline on the page", c.outline, c.background, 3.0)
        assertContrast("$palette input outline on its fill", c.outline, c.surface, 3.0)
    }

    @Test
    fun ivoryKeepsSignInReadable() = assertReadable("Ivory", IvoryColors)

    @Test
    fun cocoaKeepsTheDarkAppReadable() = assertReadable("Cocoa", CocoaColors)

    @Test
    fun palettesMatchTheDesignDirection() {
        assertTrue(!IvoryColors.isDark)
        assertTrue(CocoaColors.isDark)
        assertEquals(Color(0xFF171313), CocoaColors.background)
        assertEquals(Color(0xFF292120), CocoaColors.surface)
        assertEquals(Color(0xFFFFF8F3), CocoaColors.textPrimary)
        assertEquals(Color(0xFFB8AAA5), CocoaColors.textSecondary)
        // Sign-in buttons are charcoal on ivory.
        assertTrue(luminance(IvoryColors.actionStart) < 0.05 && luminance(IvoryColors.background) > 0.85)
    }

    @Test
    fun chosenAccentAlsoColoursTheAccentGradient() {
        val blue = Accents.of("blue")
        assertEquals(Color(blue.light), Accents.apply(LightColors, "blue", dark = false).accentStart)
        assertEquals(Color(blue.dark), Accents.apply(DarkColors, "blue", dark = true).accentStart)
        assertEquals(LightColors.accentStart, Accents.apply(LightColors, Accents.DEFAULT, dark = false).accentStart)
    }
}
