package com.meldiq.android.ui.screens.profile

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.meldiq.android.R
import com.meldiq.android.data.api.MessagingSettingsDto
import com.meldiq.android.data.model.RequestRules
import com.meldiq.android.data.repo.ServerFeatures
import com.meldiq.android.ui.LocalContainer
import com.meldiq.android.ui.components.BannerKind
import com.meldiq.android.ui.components.GlassCard
import com.meldiq.android.ui.components.GlassSheet
import com.meldiq.android.ui.components.MenuRow
import com.meldiq.android.ui.components.StatusBanner
import com.meldiq.android.ui.friendly
import com.meldiq.android.ui.theme.Griip
import com.meldiq.android.ui.theme.Space
import kotlinx.coroutines.launch

/**
 * Settings › Privacy › Messages (batch B6, servers with `message-requests`): who can message you directly, and who can
 * send you connection and message requests. Someone outside the first who passes the second lands under Requests.
 */
@Composable
fun MessagingPrivacyCard() {
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    var available by remember { mutableStateOf(false) }
    var current by remember { mutableStateOf<MessagingSettingsDto?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var choosing by remember { mutableStateOf<Boolean?>(null) } // true: direct messages, false: requests
    LaunchedEffect(Unit) {
        available = container.features.has(ServerFeatures.MESSAGE_REQUESTS) == true
        if (available) runCatching { container.connections.messaging() }.onSuccess { current = it }.onFailure { error = it.friendly() }
    }
    if (!available) return
    val s = current
    GlassCard(Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = 8.dp), contentPadding = PaddingValues(4.dp)) {
        MenuRow(Icons.Outlined.Forum, stringResource(R.string.b6_dm_policy), subtitle = s?.let { dmLabel(it.dmPolicy) }, onClick = { choosing = true })
        MenuRow(Icons.Outlined.PersonAdd, stringResource(R.string.b6_request_policy), subtitle = s?.let { requestLabel(it.requestPolicy) }, onClick = { choosing = false })
    }
    error?.let { StatusBanner(it, BannerKind.Warning, Modifier.padding(horizontal = Space.gutter, vertical = 4.dp)) }
    choosing?.let { direct ->
        val options = if (direct) RequestRules.DM_POLICIES else RequestRules.REQUEST_POLICIES
        val chosen = if (direct) s?.dmPolicy else s?.requestPolicy
        GlassSheet({ choosing = null }, title = stringResource(if (direct) R.string.b6_dm_policy else R.string.b6_request_policy)) {
            Text(
                stringResource(if (direct) R.string.b6_dm_policy_help else R.string.b6_request_policy_help),
                style = Griip.type.bodySmall, color = Griip.colors.textSecondary, modifier = Modifier.padding(bottom = 8.dp),
            )
            options.forEach { v ->
                MenuRow(
                    if (direct) Icons.Outlined.Forum else Icons.Outlined.PersonAdd, if (direct) dmLabel(v) else requestLabel(v),
                    trailing = { if (v == chosen) Text("✓", style = Griip.type.titleSmall, color = Griip.colors.primary) },
                    onClick = {
                        choosing = null
                        scope.launch {
                            runCatching { if (direct) container.connections.setMessaging(dmPolicy = v) else container.connections.setMessaging(requestPolicy = v) }
                                .onSuccess { current = it; error = null }
                                .onFailure { error = it.friendly() }
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun dmLabel(v: String) = stringResource(
    when (v) {
        "everyone" -> R.string.b6_policy_everyone
        "connections" -> R.string.b6_policy_connections
        else -> R.string.b6_policy_communities
    },
)

@Composable
private fun requestLabel(v: String) = stringResource(
    when (v) {
        "nobody" -> R.string.b6_policy_nobody
        "communities" -> R.string.b6_policy_communities
        else -> R.string.b6_policy_everyone
    },
)
