package com.meldiq.android.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/** Spacing on a 4 dp grid (docs/DESIGN_SYSTEM.md §3.4). 16 dp side gutter since the 2026-10-16 redesign. */
object Space {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 20.dp
    val xxl = 24.dp
    val xxxl = 32.dp
    val gutter = 16.dp
    /** Side margin of focused, single-task screens (welcome, sign-up, sign-in): more air than a list screen. */
    val screen = 24.dp
}

/** Consistent, modest radii: 16 dp for prominent surfaces, 12 dp for cards and fields, 8 dp for small parts. */
object Shapes {
    val hero = RoundedCornerShape(16.dp)
    val card = RoundedCornerShape(12.dp)
    val tile = RoundedCornerShape(12.dp)
    val iconTile = RoundedCornerShape(12.dp)
    val small = RoundedCornerShape(8.dp)
    /** Inputs on focused forms (sign-up, sign-in). */
    val field = RoundedCornerShape(16.dp)
    /** Large panels and choice cards in the Meldiq direction (2026-10-18). */
    val panel = RoundedCornerShape(24.dp)
    val pill = RoundedCornerShape(percent = 50)
    val sheet = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    /** Message bubbles: 18 dp, with a 6 dp corner toward the sender on the first bubble of a run. */
    val bubble = 18.dp
    val bubbleTail = 6.dp
}

/**
 * Motion tokens (docs/DESIGN_SYSTEM.md › Motion, Meldiq 2.0). Short and purposeful: nothing waits on an animation,
 * and with the system's animations off every state change snaps ([rememberReducedMotion]).
 *
 * - [INSTANT] 90 ms: what leaves first (a fading tab, a dismissed chip).
 * - [QUICK] 160 ms: small state changes (selection, toggles, badges).
 * - [STANDARD] 220 ms: panels, sheets, expanding sections.
 * - [PAGE] 260 ms: whole screens sliding in or out.
 * - [ENTRANCE] 420 ms: one-off entrances only (welcome logo, a sticker landing), never looping.
 *
 * Things arriving decelerate ([EmphasizedDecelerate]); things leaving accelerate ([EmphasizedAccelerate]), the
 * Material 3 emphasized curves.
 */
object Motion {
    const val INSTANT = 90
    const val QUICK = 160
    const val STANDARD = 220
    const val PAGE = 260
    const val ENTRANCE = 420
    /** A tab's content waits this long so the old one is gone first (fade-through). */
    const val FADE_THROUGH_DELAY = 60
    val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
    fun <T> standard() = tween<T>(STANDARD, easing = FastOutSlowInEasing)
    fun <T> quick() = tween<T>(QUICK, easing = FastOutSlowInEasing)
    fun <T> entrance() = tween<T>(ENTRANCE, easing = FastOutSlowInEasing)
    /** Something arriving: a screen, a sheet, a panel. */
    fun <T> enter(durationMillis: Int = PAGE, delayMillis: Int = 0) = tween<T>(durationMillis, delayMillis, EmphasizedDecelerate)
    /** Something leaving. */
    fun <T> exit(durationMillis: Int = PAGE) = tween<T>(durationMillis, easing = EmphasizedAccelerate)
}

/**
 * Whether the person turned animations off (Settings › Accessibility › Remove animations, or an animator scale of 0).
 * Decorative motion (entrances, stickers, loops) stays still then; state changes simply snap.
 */
@androidx.compose.runtime.Composable
fun rememberReducedMotion(): Boolean {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    return androidx.compose.runtime.remember {
        val scale = runCatching {
            android.provider.Settings.Global.getFloat(ctx.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        }.getOrDefault(1f)
        scale == 0f
    }
}
