package com.griipbuddy.orbit.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * The Orbit palettes from the 2026-10-18 design direction (docs/DESIGN_SYSTEM.md, UI-001):
 *
 * - [IvoryColors]: warm ivory, dark type and charcoal buttons. Sign-up and sign-in use it in light and dark mode alike.
 * - [CocoaColors]: premium dark cocoa and charcoal with a soft peach → pink accent, for the main app once the home,
 *   chat and profile screens move to it (UI-002, UI-003). Nothing uses it yet; the signed-in app keeps [LightColors]
 *   and [DarkColors] until then.
 *
 * Both are complete [GriipColors] sets, so every existing component draws correctly in them. Contrast is checked in
 * `OrbitPalettesTest`: body and secondary text 4.5:1 or better, input outlines 3:1.
 */
private val Charcoal = Color(0xFF221B1A)
private val Ink = Color(0xFF1C1716)
private val Ivory = Color(0xFFF7F2ED)
private val IvoryRaised = Color(0xFFFFFDFB)
private val Cream = Color(0xFFFFF8F3)

val IvoryColors = GriipColors(
    isDark = false,
    primary = Color(0xFF2B2220),
    primaryBright = Color(0xFF463935),
    primaryDeep = Ink,
    primarySoft = Color(0xFFEDE3DC),
    onPrimary = Cream,
    lavender = Color(0xFF5A5596),
    lavenderSoft = Color(0xFFECEAF6),
    peach = Color(0xFF9A4E25),
    peachSoft = Color(0xFFF8E6DA),
    danger = Color(0xFFB3261E),
    dangerSoft = Color(0xFFFAE4E0),
    warning = Color(0xFF85590A),
    warningSoft = Color(0xFFF8EBD3),
    success = Color(0xFF276E45),
    successSoft = Color(0xFFE2F1E7),
    canvasTop = Ivory,
    canvasMid = Ivory,
    canvasBottom = Ivory,
    glass = IvoryRaised,
    glassStrong = Color.White,
    glassBorder = Color(0xFFE6DCD5),
    hairline = Ink.copy(alpha = 0.08f),
    textPrimary = Ink,
    textSecondary = Color(0xFF5F5450),
    textTertiary = Color(0xFF766963),
    shadow = Color(0xFF3A2A24).copy(alpha = 0.08f),
    scrim = Color.Black.copy(alpha = 0.40f),
    bubbleIncoming = IvoryRaised,
    callStage = Color(0xFF171313),
    actionStart = Charcoal,
    actionEnd = Charcoal,
    heroStart = Charcoal,
    heroMid = Charcoal,
    heroEnd = Ink,
    background = Ivory,
    surface = IvoryRaised,
    surfaceVariant = Color(0xFFEFE8E2),
    border = Color(0xFFE6DCD5),
    onAction = Cream,
    bubbleOutgoing = Color(0xFFEFE3DA),
    onBubbleOutgoing = Ink,
    bubbleOutgoingMeta = Color(0xFF6B5D57),
    readReceipt = Color(0xFF2F6FB0),
    navIndicator = Color(0xFFEDE3DC),
    outline = Color(0xFF8F817B),
    // Deep terracotta → rose: the accent hue, dark enough to read as text on ivory.
    accentStart = Color(0xFFB4502F),
    accentEnd = Color(0xFFA93A62),
    onAccent = Color.White,
)

private val Cocoa = Color(0xFF171313)
private val CocoaRaised = Color(0xFF292120)
private val Peach = Color(0xFFF6A585)
private val Pink = Color(0xFFEE8AA6)

val CocoaColors = GriipColors(
    isDark = true,
    primary = Peach,
    primaryBright = Color(0xFFF8B89F),
    primaryDeep = Color(0xFFFFD3C0),
    primarySoft = Color(0xFF3A2A26),
    onPrimary = Color(0xFF2A1914),
    lavender = Color(0xFFB9B4F0),
    lavenderSoft = Color(0xFF2C2838),
    peach = Color(0xFFF5B58A),
    peachSoft = Color(0xFF3A2B22),
    danger = Color(0xFFFF8A80),
    dangerSoft = Color(0xFF3D1F1D),
    warning = Color(0xFFF2C26B),
    warningSoft = Color(0xFF3A2F1A),
    success = Color(0xFF7FD8A6),
    successSoft = Color(0xFF1B3327),
    // A warm cocoa glow at the top that settles into near-black (the reference's backdrop).
    canvasTop = Color(0xFF2E2320),
    canvasMid = Color(0xFF1D1716),
    canvasBottom = Cocoa,
    glass = CocoaRaised,
    glassStrong = Color(0xFF322826),
    glassBorder = Color(0xFF3D3230),
    hairline = Color.White.copy(alpha = 0.06f),
    textPrimary = Cream,
    textSecondary = Color(0xFFB8AAA5),
    textTertiary = Color(0xFF968883),
    shadow = Color.Black.copy(alpha = 0.35f),
    scrim = Color.Black.copy(alpha = 0.60f),
    bubbleIncoming = Color(0xFF2C2423),
    callStage = Color(0xFF0E0B0B),
    actionStart = Peach,
    actionEnd = Pink,
    heroStart = Peach,
    heroMid = Color(0xFFF297A0),
    heroEnd = Pink,
    background = Cocoa,
    surface = CocoaRaised,
    surfaceVariant = Color(0xFF342A28),
    border = Color(0xFF3D3230),
    onAction = Color(0xFF2A1914),
    bubbleOutgoing = Color(0xFF3A2C29),
    onBubbleOutgoing = Cream,
    bubbleOutgoingMeta = Color(0xFFC9B8B1),
    readReceipt = Color(0xFF8FC8FF),
    navIndicator = Color(0xFF3A302D),
    outline = Color(0xFF7D6E69),
    accentStart = Peach,
    accentEnd = Pink,
    onAccent = Color(0xFF2A1914),
)

/** Left-to-right accent gradient of a palette (highlights and accent text). */
fun GriipColors.accentBrush(): Brush = Brush.horizontalGradient(listOf(accentStart, accentEnd))
