package com.griipbuddy.message.ui.theme

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
}

/** Consistent, modest radii: 16 dp for prominent surfaces, 12 dp for cards and fields, 8 dp for small parts. */
object Shapes {
    val hero = RoundedCornerShape(16.dp)
    val card = RoundedCornerShape(12.dp)
    val tile = RoundedCornerShape(12.dp)
    val iconTile = RoundedCornerShape(12.dp)
    val small = RoundedCornerShape(8.dp)
    val pill = RoundedCornerShape(percent = 50)
    val sheet = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    /** Message bubbles: 18 dp, with a 6 dp corner toward the sender on the first bubble of a run. */
    val bubble = 18.dp
    val bubbleTail = 6.dp
}

object Motion {
    const val STANDARD = 220
    const val QUICK = 160
    fun <T> standard() = tween<T>(STANDARD, easing = FastOutSlowInEasing)
    fun <T> quick() = tween<T>(QUICK, easing = FastOutSlowInEasing)
}
