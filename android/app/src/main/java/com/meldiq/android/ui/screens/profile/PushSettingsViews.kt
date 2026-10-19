package com.meldiq.android.ui.screens.profile

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meldiq.android.R
import com.meldiq.android.data.repo.PushState
import com.meldiq.android.data.repo.Settings
import com.meldiq.android.service.UnifiedPushDelivery
import com.meldiq.android.ui.LocalContainer
import com.meldiq.android.ui.components.GriipSwitch
import com.meldiq.android.ui.components.MenuRow
import com.meldiq.android.ui.theme.Griip

/**
 * Settings › Notifications & Sounds › Background delivery: wake-ups through a UnifiedPush distributor
 * (docs/BACKGROUND_DELIVERY.md), with where they stand. Turning them on may show Android's distributor chooser.
 */
@Composable
fun PushWakeupRow(s: Settings, update: ((Settings) -> Settings) -> Unit) {
    val ctx = LocalContext.current
    val container = LocalContainer.current
    val state by container.push.state.collectAsStateWithLifecycle()
    MenuRow(Icons.Outlined.Bolt, stringResource(R.string.push_wakeups), subtitle = pushStatus(s.pushWakeups, state), trailing = {
        GriipSwitch(s.pushWakeups, { on ->
            update { it.copy(pushWakeups = on) }
            when {
                !on -> UnifiedPushDelivery.stop(ctx)
                else -> ctx.activity()?.let { UnifiedPushDelivery.enable(it) } ?: UnifiedPushDelivery.start(ctx)
            }
        })
    })
}

/** What wake-ups are and what to set up in the distributor app first, naming this server's push server when known. */
@Composable
fun PushExplainer(modifier: Modifier = Modifier) {
    val container = LocalContainer.current
    var hosts by remember(container) { mutableStateOf<List<String>?>(null) }
    LaunchedEffect(container) { hosts = container.features.pushHosts() }
    val server = hosts?.firstOrNull()?.let { "https://$it" } ?: stringResource(R.string.push_host_unknown)
    Text(stringResource(R.string.push_explainer, server), style = Griip.type.caption, color = Griip.colors.textSecondary, modifier = modifier)
}

@Composable
private fun pushStatus(enabled: Boolean, state: PushState): String {
    if (!enabled) return stringResource(R.string.push_status_off)
    return when (state) {
        PushState.Off, PushState.Registering -> stringResource(R.string.push_status_registering)
        PushState.ServerUnsupported -> stringResource(R.string.push_status_unsupported)
        PushState.NoDistributor -> stringResource(R.string.push_status_no_distributor)
        is PushState.DistributorFailed -> stringResource(R.string.push_status_failed, state.reason.lowercase().replace('_', ' '))
        is PushState.ServerRefused ->
            state.allowedHosts.firstOrNull()?.let { stringResource(R.string.push_status_refused, it) } ?: stringResource(R.string.push_status_refused_other)
        is PushState.Pending -> stringResource(R.string.push_status_pending)
        is PushState.Active -> stringResource(R.string.push_status_active, state.host)
    }
}

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}
