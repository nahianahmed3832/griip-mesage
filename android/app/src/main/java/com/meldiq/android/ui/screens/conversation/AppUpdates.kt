package com.meldiq.android.ui.screens.conversation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.meldiq.android.R
import com.meldiq.android.data.api.ChannelAppEventDto
import com.meldiq.android.data.repo.ServerFeatures
import com.meldiq.android.ui.LinkOpener
import com.meldiq.android.ui.LocalContainer
import com.meldiq.android.ui.TimeFmt
import com.meldiq.android.ui.components.BannerKind
import com.meldiq.android.ui.components.ButtonTone
import com.meldiq.android.ui.components.GlassSheet
import com.meldiq.android.ui.components.ListCard
import com.meldiq.android.ui.components.PillButton
import com.meldiq.android.ui.components.StatusBanner
import com.meldiq.android.ui.components.pressable
import com.meldiq.android.ui.screens.workspace.apps.AppTile
import com.meldiq.android.ui.theme.Griip

/**
 * App updates in a channel (docs/INTEGRATIONS.md › Notifications): what connected apps posted here (a pull request
 * merged, a deployment failed). They come from Meldiq's server, which received them from the provider, so they aren't
 * end-to-end encrypted, and this says so. Only the channel's members can read them (the server checks).
 */
@Composable
fun ChannelAppUpdatesBar(conversationId: String) {
    val container = LocalContainer.current
    val c = Griip.colors
    var events by remember(conversationId) { mutableStateOf<List<ChannelAppEventDto>>(emptyList()) }
    var open by remember { mutableStateOf(false) }
    val load: suspend () -> Unit = {
        if (container.features.has(ServerFeatures.INTEGRATIONS) == true) {
            runCatching { container.integrations.channelEvents(conversationId) }.onSuccess { events = it.sortedByDescending { e -> e.createdAt } }
        }
    }
    LaunchedEffect(conversationId) {
        load()
        container.messages.appEvents.collect { if (it == conversationId) load() }
    }
    if (events.isEmpty()) return
    Row(
        Modifier.fillMaxWidth().background(c.surface).pressable({ open = true }, onClickLabel = stringResource(R.string.app_events_title)).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Apps, null, tint = c.peach, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            stringResource(R.string.app_events_count, events.size) + " · " + events.first().title,
            style = Griip.type.label, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
    HorizontalDivider(thickness = 1.dp, color = c.border)
    if (open) AppUpdatesSheet(events) { open = false }
}

@Composable
private fun AppUpdatesSheet(events: List<ChannelAppEventDto>, onDismiss: () -> Unit) {
    val c = Griip.colors
    val ctx = LocalContext.current
    GlassSheet(onDismiss, title = stringResource(R.string.app_events_title)) {
        StatusBanner(stringResource(R.string.app_events_note), BannerKind.Info, icon = Icons.Outlined.Info)
        Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()).padding(top = 8.dp)) {
            events.forEach { e ->
                ListCard(
                    e.title,
                    subtitle = listOfNotNull(e.appName, e.summary, e.actor, TimeFmt.listStamp(e.occurredAt.takeIf { it > 0 } ?: e.createdAt)).joinToString(" · "),
                    leading = { AppTile(e.icon, "installed", size = 38.dp) },
                    trailing = e.url?.let { url -> { PillButton(stringResource(R.string.share_app_open), { LinkOpener.open(ctx, url, inApp = true) }, tone = ButtonTone.Glass, height = 36.dp) } },
                )
            }
        }
    }
}

/**
 * Under a shared link to a connected app's item: what the viewer's own connection says about it now (a status, an
 * owner). The server asks the provider as the viewer, so someone without access, or without a connection, sees
 * nothing extra. Only recognised provider links are ever looked up.
 */
@Composable
fun AppLinkDetails(orgId: String?, url: String) {
    if (orgId == null || !com.meldiq.android.ui.screens.workspace.apps.AppRules.mightUnfurl(url)) return
    val container = LocalContainer.current
    val c = Griip.colors
    var line by remember(url) { mutableStateOf<String?>(null) }
    LaunchedEffect(orgId, url) {
        if (container.features.has(ServerFeatures.INTEGRATIONS) != true) return@LaunchedEffect
        val r = container.integrations.unfurlCached(orgId, url)?.resource ?: return@LaunchedEffect
        line = listOfNotNull(r.status, r.detail, r.owner, r.container).distinct().take(3).joinToString(" · ").ifBlank { null }
    }
    line?.let { Text(it, style = Griip.type.caption, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp)) }
}
