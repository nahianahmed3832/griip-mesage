package com.griipbuddy.message.ui.screens.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.griipbuddy.message.data.repo.Settings
import com.griipbuddy.message.notify.NotificationHealth
import com.griipbuddy.message.ui.components.BannerKind
import com.griipbuddy.message.ui.components.StatusBanner

/** The current notification problems, re-checked whenever the screen resumes (e.g. back from Android settings). */
@Composable
fun rememberNotificationProblems(settings: Settings): List<NotificationHealth.Problem> {
    val ctx = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var tick by remember { mutableStateOf(0) }
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    return remember(tick, settings.notifications, settings.backgroundConnection) {
        runCatching { NotificationHealth.problems(NotificationHealth.read(ctx, settings.notifications, settings.backgroundConnection)) }.getOrDefault(emptyList())
    }
}

/** Each problem with a button that opens the Android settings page that fixes it (Orbit never changes them itself). */
@Composable
fun NotificationProblems(problems: List<NotificationHealth.Problem>, modifier: Modifier = Modifier, onlySevere: Boolean = false, max: Int = Int.MAX_VALUE) {
    val ctx = LocalContext.current
    Column(modifier) {
        problems.filter { !onlySevere || it.severe }.take(max).forEach { p ->
            val intent = NotificationHealth.intentFor(ctx, p)
            StatusBanner(
                "${p.title}. ${p.detail}", if (p.severe) BannerKind.Warning else BannerKind.Info, Modifier.padding(vertical = 3.dp),
                icon = Icons.Rounded.NotificationsOff,
                action = if (intent != null) "Fix" else null,
                onAction = intent?.let { i -> { runCatching { ctx.startActivity(i) } } },
            )
        }
    }
}
