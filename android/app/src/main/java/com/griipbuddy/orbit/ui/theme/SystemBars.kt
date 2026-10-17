package com.griipbuddy.orbit.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Screens drawn on a dark palette whatever the app theme ([CocoaColors]) need light status- and navigation-bar icons
 * while they are shown. `MainActivity` owns the bars; such screens call [DarkSurfaceSystemBars] and it counts them.
 */
@Stable
class SystemBarRequests {
    /** How many dark-surface screens are composed right now (a screen leaving and one arriving can overlap). */
    var darkSurfaces by mutableIntStateOf(0)
        private set

    fun acquire() {
        darkSurfaces++
    }

    fun release() {
        darkSurfaces = (darkSurfaces - 1).coerceAtLeast(0)
    }
}

val LocalSystemBarRequests = staticCompositionLocalOf<SystemBarRequests?> { null }

/** Asks for light system-bar icons while the calling screen is on screen. */
@Composable
fun DarkSurfaceSystemBars() {
    val requests = LocalSystemBarRequests.current ?: return
    DisposableEffect(requests) {
        requests.acquire()
        onDispose { requests.release() }
    }
}
