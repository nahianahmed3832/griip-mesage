package com.griipbuddy.message.ui.theme

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/** Spacing on a 4 dp grid (docs/DESIGN_SYSTEM.md §3.4). */
object Space {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 20.dp
    val xxl = 24.dp
    val xxxl = 32.dp
    val gutter = 20.dp
}

object Shapes {
    val hero = RoundedCornerShape(24.dp)
    val card = RoundedCornerShape(20.dp)
    val tile = RoundedCornerShape(16.dp)
    val iconTile = RoundedCornerShape(14.dp)
    val small = RoundedCornerShape(10.dp)
    val pill = RoundedCornerShape(percent = 50)
    val sheet = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
}

object Motion {
    const val STANDARD = 220
    const val QUICK = 160
    fun <T> standard() = tween<T>(STANDARD, easing = FastOutSlowInEasing)
    fun <T> quick() = tween<T>(QUICK, easing = FastOutSlowInEasing)
}
