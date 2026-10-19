package com.meldiq.android.ui.screens.home

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.meldiq.android.data.api.ConnState
import com.meldiq.android.ui.components.BannerKind
import com.meldiq.android.ui.components.StatusBanner

/** Relay connection state for list and conversation screens. Nothing is shown while connected. */
@Composable
fun ConnectionBanner(state: ConnState, modifier: Modifier = Modifier, onRetry: () -> Unit) {
    when (state) {
        ConnState.Offline -> StatusBanner("You're offline. Messages will send when you reconnect.", BannerKind.Warning, modifier, icon = Icons.Outlined.CloudOff)
        ConnState.Connecting -> StatusBanner("Connecting…", BannerKind.Info, modifier, icon = Icons.Outlined.Sync, action = "Retry", onAction = onRetry)
        else -> {}
    }
}
