package com.meldiq.android.ui.screens.workspace.inbox

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.meldiq.android.R
import com.meldiq.android.data.api.InboxConversationDto
import com.meldiq.android.data.api.InboxListRes
import com.meldiq.android.ui.LocalContainer
import com.meldiq.android.ui.Routes
import com.meldiq.android.ui.TimeFmt
import com.meldiq.android.ui.components.BannerKind
import com.meldiq.android.ui.components.ChipRow
import com.meldiq.android.ui.components.CircleIconButton
import com.meldiq.android.ui.components.EmptyState
import com.meldiq.android.ui.components.GriipTextField
import com.meldiq.android.ui.components.GriipTopBar
import com.meldiq.android.ui.components.ListCard
import com.meldiq.android.ui.components.PillChip
import com.meldiq.android.ui.components.ShimmerList
import com.meldiq.android.ui.components.StatusBanner
import com.meldiq.android.ui.friendly
import com.meldiq.android.ui.theme.Griip
import com.meldiq.android.ui.theme.Space
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import java.net.URLEncoder

/**
 * The Unified Inbox (round 8, docs/UNIFIED_INBOX.md): customer conversations from the workspace's connected business
 * accounts. Views, a platform filter and search over customer names and tags; it refreshes when the server says
 * something changed, and every 30 seconds as a fallback.
 */
private const val REFRESH_MS = 30_000L

@Composable
fun InboxScreen(nav: NavHostController, orgId: String, canManage: Boolean) {
    val container = LocalContainer.current
    val c = Griip.colors
    var view by remember { mutableStateOf("all") }
    var provider by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var data by remember { mutableStateOf<InboxListRes?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    val me = container.session.session?.userId

    LaunchedEffect(view, provider, query, reload) {
        if (query.isNotEmpty()) delay(300)
        val q = buildString {
            append("view=").append(view)
            provider?.let { append("&provider=").append(it) }
            if (query.isNotBlank()) append("&q=").append(URLEncoder.encode(query.trim(), "UTF-8"))
        }
        runCatching { container.api.get<InboxListRes>("/v1/orgs/$orgId/inbox/conversations?$q") }
            .onSuccess { data = it; error = null }.onFailure { error = it.friendly() }
    }
    LaunchedEffect(orgId) { container.messages.inboxEvents.filter { it.orgId == orgId }.collect { reload++ } }
    LaunchedEffect(orgId) { while (true) { delay(REFRESH_MS); reload++ } }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding())) {
        item {
            GriipTopBar(stringResource(R.string.ib_title), onBack = { nav.popBackStack() }, actions = {
                if (canManage) CircleIconButton(Icons.Outlined.Settings, stringResource(R.string.ib_setup), { nav.navigate(Routes.inboxSetup(orgId)) }, tint = c.textSecondary)
            })
        }
        item { Text(stringResource(R.string.ib_not_e2ee), style = Griip.type.caption, color = c.textSecondary, modifier = Modifier.padding(horizontal = Space.gutter + 4.dp, vertical = 2.dp)) }
        item {
            GriipTextField(query, { query = it.take(100) }, stringResource(R.string.ib_search), Modifier.padding(horizontal = Space.gutter, vertical = 6.dp), leadingIcon = Icons.Outlined.Search)
        }
        item {
            val counts = data?.counts
            ChipRow {
                InboxRules.VIEWS.forEach { v ->
                    val n = when (v) { "unassigned" -> counts?.unassigned; "mine" -> counts?.mine; "ai" -> counts?.escalated; else -> null }
                    val label = viewLabel(v) + (n?.takeIf { it > 0 }?.let { " · $it" } ?: "")
                    PillChip(label, view == v, { view = v })
                }
            }
        }
        item {
            ChipRow {
                PillChip(stringResource(R.string.ib_all_platforms), provider == null, { provider = null })
                InboxRules.PROVIDERS.forEach { p -> PillChip(InboxRules.providerBadge(p), provider == p, { provider = p }) }
            }
        }
        error?.let { e -> item { StatusBanner(e, BannerKind.Error, Modifier.padding(horizontal = Space.gutter, vertical = 6.dp)) } }
        val list = data?.conversations
        if (list == null) {
            if (error == null) item { ShimmerList(4) }
        } else if (list.isEmpty()) {
            item { EmptyState(Icons.Outlined.Forum, stringResource(R.string.ib_empty), stringResource(R.string.ib_empty_body)) }
        }
        items(list.orEmpty(), key = { it.id }) { conv -> ConversationRow(conv, me) { nav.navigate(Routes.inboxConversation(conv.id)) } }
    }
}

@Composable
internal fun viewLabel(v: String): String = stringResource(
    when (v) {
        "unassigned" -> R.string.ib_view_unassigned
        "mine" -> R.string.ib_view_mine
        "waiting_agent" -> R.string.ib_view_waiting_agent
        "waiting_customer" -> R.string.ib_view_waiting_customer
        "ai" -> R.string.ib_view_ai
        "resolved" -> R.string.ib_view_resolved
        else -> R.string.ib_view_all
    },
)

@Composable
internal fun handlingLabel(h: String): String = stringResource(
    when (h) {
        "ai_draft" -> R.string.ib_handling_ai_draft
        "ai_auto" -> R.string.ib_handling_ai_auto
        "awaiting_review" -> R.string.ib_handling_awaiting_review
        "escalated" -> R.string.ib_handling_escalated
        else -> R.string.ib_handling_human
    },
)

@Composable
internal fun priorityLabel(p: String): String = stringResource(
    when (p) {
        "low" -> R.string.ib_priority_low
        "high" -> R.string.ib_priority_high
        "urgent" -> R.string.ib_priority_urgent
        else -> R.string.ib_priority_normal
    },
)

@Composable
internal fun providerColor(p: String): Color {
    val c = Griip.colors
    return when (p) {
        "whatsapp" -> c.success
        "instagram" -> c.peach
        "messenger" -> c.primary
        else -> c.textSecondary
    }
}

@Composable
private fun ConversationRow(conv: InboxConversationDto, me: String?, onClick: () -> Unit) {
    val c = Griip.colors
    val who = InboxRules.customerLabel(conv, stringResource(R.string.ib_customer))
    val assignee = when (conv.assigneeId) {
        null -> stringResource(R.string.ib_unassigned)
        me -> stringResource(R.string.ib_assigned_you)
        else -> null
    }
    val meta = listOfNotNull(
        conv.account.name,
        assignee,
        if (conv.handling != "human") handlingLabel(conv.handling) else null,
        if (conv.priority != "normal") priorityLabel(conv.priority) else null,
        conv.tags.takeIf { it.isNotEmpty() }?.joinToString(" ") { "#$it" },
    ).joinToString(" · ")
    ListCard(
        who, Modifier.padding(horizontal = 8.dp, vertical = 1.dp),
        subtitleContent = {
            Column {
                Text(conv.preview.ifBlank { "…" }, style = Griip.type.bodySmall, color = if (conv.unread > 0) c.textPrimary else c.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(meta, style = Griip.type.caption, color = c.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        leading = { ProviderBadge(conv.provider) },
        trailing = {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(TimeFmt.listStamp(conv.lastMessageAt), style = Griip.type.caption, color = c.textTertiary)
                if (conv.overdue) Text(stringResource(R.string.ib_overdue), style = Griip.type.caption, color = c.danger)
                else if (conv.unread > 0) Text("${conv.unread}", style = Griip.type.label, color = c.primary)
            }
        },
        onClick = onClick,
    )
}

/** The source platform, always shown: a coloured tile and the platform's name. */
@Composable
internal fun ProviderBadge(provider: String) {
    val color = providerColor(provider)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        com.meldiq.android.ui.components.IconTile(Icons.Outlined.Forum, color, Griip.colors.surfaceVariant, size = 36.dp)
        Text(InboxRules.providerBadge(provider), style = Griip.type.caption, color = color, maxLines = 1)
    }
}
