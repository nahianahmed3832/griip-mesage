package com.griipbuddy.orbit.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Colour tokens (docs/DESIGN_SYSTEM.md, docs/UI_REDESIGN.md).
 *
 * 2026-10-16 redesign: one restrained emerald accent on neutral surfaces, flat backgrounds and borders
 * instead of glass and shadows. Older token names are kept so every screen moves to the new language
 * together: `glass*` are now solid surfaces, `canvas*` the flat app background, `hero*`/`action*` solid
 * emerald. Lavender and peach are muted semantic tints (groups, announcements/pins), not decoration.
 * Text on `actionStart` (buttons, send) meets 4.5:1 in both themes.
 */
@Immutable
data class GriipColors(
    val isDark: Boolean,
    val primary: Color,
    val primaryBright: Color,
    val primaryDeep: Color,
    val primarySoft: Color,
    val onPrimary: Color,
    val lavender: Color,
    val lavenderSoft: Color,
    val peach: Color,
    val peachSoft: Color,
    val danger: Color,
    val dangerSoft: Color,
    val warning: Color,
    val warningSoft: Color,
    val success: Color,
    val successSoft: Color,
    val canvasTop: Color,
    val canvasMid: Color,
    val canvasBottom: Color,
    val glass: Color,
    val glassStrong: Color,
    val glassBorder: Color,
    val hairline: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val shadow: Color,
    val scrim: Color,
    val bubbleIncoming: Color,
    val callStage: Color,
    /** Emerald fills that carry [onAction] text/icons (buttons, send, selected states). */
    val actionStart: Color,
    val actionEnd: Color,
    val heroStart: Color,
    val heroMid: Color,
    val heroEnd: Color,
    /** App background behind lists and screens. */
    val background: Color,
    /** Cards, sheets, bars and incoming bubbles. */
    val surface: Color,
    /** Search fields, inputs, chips and pressed rows. */
    val surfaceVariant: Color,
    /** 1 dp borders and dividers. */
    val border: Color,
    val onAction: Color,
    val bubbleOutgoing: Color,
    val onBubbleOutgoing: Color,
    val bubbleOutgoingMeta: Color,
    /** Read-receipt ticks on outgoing bubbles. */
    val readReceipt: Color,
    /** Selected bottom-navigation pill. */
    val navIndicator: Color,
    /** Boundary of inputs and other controls that must stand out from their fill (3:1 where it carries meaning). */
    val outline: Color,
    /** The accent gradient (start → end): highlights, accent text and accent fills carrying [onAccent]. */
    val accentStart: Color,
    val accentEnd: Color,
    val onAccent: Color,
)

private val EmeraldLight = Color(0xFF087F56)
private val EmeraldDark = Color(0xFF3CC48F)

val LightColors = GriipColors(
    isDark = false,
    primary = EmeraldLight,
    primaryBright = Color(0xFF12A170),
    primaryDeep = Color(0xFF06633F),
    primarySoft = Color(0xFFE1F3EA),
    onPrimary = Color.White,
    lavender = Color(0xFF55629F),
    lavenderSoft = Color(0xFFE9EBF5),
    peach = Color(0xFFA9601C),
    peachSoft = Color(0xFFF7ECDF),
    danger = Color(0xFFC6372B),
    dangerSoft = Color(0xFFFBE7E4),
    warning = Color(0xFF9C6510),
    warningSoft = Color(0xFFFBF1DE),
    success = Color(0xFF0E8A5A),
    successSoft = Color(0xFFE0F3E9),
    canvasTop = Color(0xFFF6F7F6),
    canvasMid = Color(0xFFF6F7F6),
    canvasBottom = Color(0xFFF6F7F6),
    glass = Color.White,
    glassStrong = Color.White,
    glassBorder = Color(0xFFE1E6E3),
    hairline = Color(0xFF111715).copy(alpha = 0.07f),
    textPrimary = Color(0xFF111715),
    textSecondary = Color(0xFF4D5853),
    textTertiary = Color(0xFF6F7B76),
    shadow = Color.Black.copy(alpha = 0.06f),
    scrim = Color.Black.copy(alpha = 0.40f),
    bubbleIncoming = Color.White,
    callStage = Color(0xFF0F1513),
    actionStart = EmeraldLight,
    actionEnd = EmeraldLight,
    heroStart = EmeraldLight,
    heroMid = EmeraldLight,
    heroEnd = Color(0xFF06633F),
    background = Color(0xFFF6F7F6),
    surface = Color.White,
    surfaceVariant = Color(0xFFEEF1EF),
    border = Color(0xFFE1E6E3),
    onAction = Color.White,
    bubbleOutgoing = Color(0xFFDCF2E6),
    onBubbleOutgoing = Color(0xFF0E1A15),
    bubbleOutgoingMeta = Color(0xFF4A6B5C),
    readReceipt = Color(0xFF0B8FD9),
    navIndicator = Color(0xFFDCF0E5),
    outline = Color(0xFFE1E6E3),
    accentStart = EmeraldLight,
    accentEnd = Color(0xFF06633F),
    onAccent = Color.White,
)

val DarkColors = GriipColors(
    isDark = true,
    primary = EmeraldDark,
    primaryBright = Color(0xFF4FD6A0),
    primaryDeep = Color(0xFF72DDB2),
    primarySoft = Color(0xFF133B2B),
    onPrimary = Color(0xFF03241A),
    lavender = Color(0xFF9BA6E3),
    lavenderSoft = Color(0xFF232741),
    peach = Color(0xFFE0A160),
    peachSoft = Color(0xFF3A2A1B),
    danger = Color(0xFFF0796D),
    dangerSoft = Color(0xFF3E1F1C),
    warning = Color(0xFFE6B45A),
    warningSoft = Color(0xFF392D17),
    success = Color(0xFF4ED39D),
    successSoft = Color(0xFF13372A),
    canvasTop = Color(0xFF0D1110),
    canvasMid = Color(0xFF0D1110),
    canvasBottom = Color(0xFF0D1110),
    glass = Color(0xFF151A18),
    glassStrong = Color(0xFF1B211F),
    glassBorder = Color(0xFF2A322F),
    hairline = Color.White.copy(alpha = 0.08f),
    textPrimary = Color(0xFFE7ECEA),
    textSecondary = Color(0xFFA9B4B0),
    textTertiary = Color(0xFF85908C),
    shadow = Color.Black.copy(alpha = 0.30f),
    scrim = Color.Black.copy(alpha = 0.55f),
    bubbleIncoming = Color(0xFF1C2220),
    callStage = Color(0xFF090C0B),
    actionStart = Color(0xFF17865D),
    actionEnd = Color(0xFF17865D),
    heroStart = Color(0xFF17865D),
    heroMid = Color(0xFF17865D),
    heroEnd = Color(0xFF0F6646),
    background = Color(0xFF0D1110),
    surface = Color(0xFF151A18),
    surfaceVariant = Color(0xFF1F2623),
    border = Color(0xFF2A322F),
    onAction = Color.White,
    bubbleOutgoing = Color(0xFF15513A),
    onBubbleOutgoing = Color(0xFFEAF6F0),
    bubbleOutgoingMeta = Color(0xFFA7CDBB),
    readReceipt = Color(0xFF6CC7FF),
    navIndicator = Color(0xFF173E2E),
    outline = Color(0xFF2A322F),
    accentStart = EmeraldDark,
    accentEnd = Color(0xFF72DDB2),
    onAccent = Color(0xFF03241A),
)

val LocalGriipColors = staticCompositionLocalOf { LightColors }

/**
 * Avatar colours: muted solid tones (pairs kept for older call sites; both entries are the same colour).
 * White initials meet 4.5:1 on every entry.
 */
private val AvatarTones = listOf(
    Color(0xFF2B7A6B), // teal
    Color(0xFF5363AE), // indigo
    Color(0xFFA35F1E), // amber
    Color(0xFF3A7F4E), // green
    Color(0xFF2E70A1), // blue
    Color(0xFFAA4A6C), // rose
    Color(0xFF7652A8), // violet
    Color(0xFF52666F), // slate
)
val AvatarGradients = AvatarTones.map { it to it }
