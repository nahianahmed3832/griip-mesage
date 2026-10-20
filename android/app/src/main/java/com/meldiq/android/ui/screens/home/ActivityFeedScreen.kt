package com.meldiq.android.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.CallMissed
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.meldiq.android.R
import com.meldiq.android.ui.LocalContainer
import com.meldiq.android.ui.LocalTabBarPadding
import com.meldiq.android.ui.Routes
import com.meldiq.android.ui.TimeFmt
import com.meldiq.android.ui.components.ArrowKnob
import com.meldiq.android.ui.components.CircleIconButton
import com.meldiq.android.ui.components.EmptyState
import com.meldiq.android.ui.components.LargeHeader
import com.meldiq.android.ui.components.ShimmerList
import com.meldiq.android.ui.containerViewModel
import com.meldiq.android.ui.screens.chats.ConversationAvatar
import com.meldiq.android.ui.screens.chats.listTitle
import com.meldiq.android.ui.theme.Griip
import com.meldiq.android.ui.theme.Space

/**
 * The Activity tab (UI-002): what needs you, from this phone's own data. Unread @mentions in every workspace (opening
 * the chat clears them), incoming calls you missed in the last week, and, if the server lets you see it, the selected
 * workspace's change log, and replies in threads you started or replied in (unseen first; opening the thread marks them
 * seen). No reactions: nothing on the phone tracks those yet.
 */
@Composable
fun ActivityFeedScreen(nav: NavHostController) = ActivityFeed(nav)

@Composable
private fun ActivityFeed(nav: NavHostController) {
    val container = LocalContainer.current
    val c = Griip.colors
    val me = container.session.session?.userId
    val vm = containerViewModel { ActivityViewModel(it) }
    val loaded by vm.conversations.collectAsStateWithLifecycle()
    val users by vm.users.collectAsStateWithLifecycle()
    val convs = loaded.orEmpty()
    val callLog by vm.callLog.collectAsStateWithLifecycle()
    val orgLog by vm.orgLog.collectAsStateWithLifecycle()
    val threads by vm.threads.collectAsStateWithLifecycle()
    val conn by container.realtime.state.collectAsStateWithLifecycle()
    val mentions = remember(convs) { WorkspaceHomeRules.allMentions(convs) }
    val missed = remember(callLog) {
        WorkspaceHomeRules.missedCalls(callLog, System.currentTimeMillis(), WorkspaceHomeRules.MISSED_CALLS_ACTIVITY_WINDOW_MS).take(WorkspaceHomeRules.MISSED_CALLS_SHOWN)
    }
    val byId = remember(convs) { convs.associateBy { it.id } }
    // Round 3 (the reference's Activity chips): All, Mentions, Threads, Calls.
    var filter by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(ActivityRules.Filter.ALL) }
    val show = { s: ActivityRules.Filter -> ActivityRules.shows(filter, s) }

    Box(Modifier.fillMaxSize().background(c.background)) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp + LocalTabBarPadding.current)) {
            item(key = "header") {
                LargeHeader(stringResource(R.string.nav_activity)) {
                    CircleIconButton(Icons.Rounded.Search, stringResource(R.string.chats_search), { nav.navigate(Routes.SEARCH) }, background = c.surface)
                }
            }
            item(key = "connection") { ConnectionBanner(conn, Modifier.padding(horizontal = Space.gutter, vertical = 4.dp)) { container.realtime.reconnectNow() } }
            if (loaded == null) {
                item(key = "loading") { ShimmerList(5, Modifier.padding(top = 8.dp)) }
                return@LazyColumn
            }
            item(key = "filters") {
                com.meldiq.android.ui.components.ChipRow(Modifier.padding(vertical = 4.dp)) {
                    listOf(
                        ActivityRules.Filter.ALL to R.string.r3_activity_all, ActivityRules.Filter.MENTIONS to R.string.r3_activity_mentions,
                        ActivityRules.Filter.THREADS to R.string.r3_activity_threads, ActivityRules.Filter.CALLS to R.string.r3_activity_calls,
                    ).forEach { (f, label) -> com.meldiq.android.ui.components.PillChip(stringResource(label), filter == f, { filter = f }) }
                }
            }
            if (ActivityRules.empty(filter, mentions.size, threads.size, missed.size)) {
                item(key = "empty") {
                    EmptyState(Icons.Outlined.NotificationsNone, stringResource(R.string.activity_empty_title), stringResource(R.string.activity_empty_body))
                }
            }
            if (show(ActivityRules.Filter.MENTIONS) && mentions.isNotEmpty()) {
                item(key = "mentions-header") { HomeSectionHeader(stringResource(R.string.home_mentions)) }
                items(mentions, key = { "mention-" + it.id }) { conv ->
                    HomeListRow(
                        listTitle(conv, me),
                        leading = { ChannelOrAvatar(conv, me) },
                        onClick = { nav.navigate(Routes.conversation(conv.id)) },
                        subtitle = conv.scopeName?.takeIf { conv.type == "CHANNEL" }, bold = true,
                        trailing = { HomeCount(conv.mentionCount, accent = true, description = stringResource(R.string.home_mention_count, conv.mentionCount), prefix = "@") },
                    )
                }
            }
            if (show(ActivityRules.Filter.THREADS) && threads.isNotEmpty()) {
                item(key = "threads-header") { HomeSectionHeader(stringResource(R.string.activity_threads)) }
                items(threads, key = { "thread-" + it.rootId }) { t ->
                    val conv = byId[t.conversationId]
                    val sender = users.firstOrNull { it.id == t.latest.senderUserId }?.displayName ?: stringResource(R.string.activity_someone)
                    HomeListRow(
                        conv?.let { listTitle(it, me) } ?: stringResource(R.string.activity_thread),
                        leading = { if (conv != null) ChannelOrAvatar(conv, me) else Icon(Icons.Outlined.Forum, null, tint = c.textSecondary, modifier = Modifier.size(20.dp)) },
                        onClick = { nav.navigate(Routes.conversation(t.conversationId, t.rootId)) },
                        subtitle = "$sender: ${t.latest.text.ifBlank { stringResource(R.string.activity_thread_attachment) }}".take(120),
                        bold = t.unseen > 0,
                        trailing = {
                            if (t.unseen > 0) HomeCount(t.unseen, accent = true, description = if (t.unseen == 1) stringResource(R.string.activity_thread_new_one) else stringResource(R.string.activity_thread_new, t.unseen))
                            else Text(TimeFmt.listStamp(t.latest.sortTs), style = Griip.type.caption, color = c.textTertiary)
                        },
                    )
                }
            }
            if (show(ActivityRules.Filter.CALLS) && missed.isNotEmpty()) {
                item(key = "calls-header") {
                    HomeSectionHeader(
                        stringResource(R.string.activity_missed_calls),
                        action = stringResource(R.string.home_call_history), onAction = { nav.navigate(Routes.CALLS) },
                    )
                }
                items(missed, key = { "call-" + it.callId }) { entry ->
                    val conv = byId[entry.conversationId]
                    val kind = stringResource(if (entry.video) R.string.activity_missed_video else R.string.activity_missed_voice)
                    // The chat's name, else the caller's name the call log kept, else just the kind of call.
                    val who = conv?.let { listTitle(it, me) } ?: entry.peerName?.takeIf { it.isNotBlank() }
                    HomeListRow(
                        who ?: kind,
                        leading = {
                            if (conv != null) ConversationAvatar(conv, me, size = 32.dp)
                            else Icon(Icons.AutoMirrored.Rounded.CallMissed, null, tint = c.danger, modifier = Modifier.size(20.dp))
                        },
                        onClick = { if (conv != null) nav.navigate(Routes.conversation(conv.id)) else nav.navigate(Routes.CALLS) },
                        subtitle = listOfNotNull(kind.takeIf { who != null }, TimeFmt.listStamp(entry.startedAt)).joinToString(" · "),
                        trailing = { Icon(Icons.AutoMirrored.Rounded.CallMissed, null, tint = c.danger, modifier = Modifier.size(18.dp)) },
                    )
                }
            }
            if (filter == ActivityRules.Filter.ALL) orgLog?.let { log ->
                item(key = "org-log") {
                    HomeListRow(
                        stringResource(R.string.activity_workspace_log),
                        leading = { Icon(Icons.Outlined.History, null, tint = c.textSecondary, modifier = Modifier.size(20.dp)) },
                        onClick = { nav.navigate(Routes.activity(log.orgId)) },
                        subtitle = stringResource(R.string.activity_workspace_log_sub, log.name),
                        modifier = Modifier.padding(top = 12.dp),
                        trailing = { ArrowKnob() },
                    )
                }
            }
        }
    }
}

/** Round 3: which Activity sections a filter chip shows (unit-tested in `StatusRulesTest`). */
object ActivityRules {
    enum class Filter { ALL, MENTIONS, THREADS, CALLS }

    fun shows(filter: Filter, section: Filter): Boolean = filter == Filter.ALL || filter == section

    /** Whether the chosen filter has nothing to show (then the empty state appears). */
    fun empty(filter: Filter, mentions: Int, threads: Int, calls: Int): Boolean = when (filter) {
        Filter.ALL -> mentions + threads + calls == 0
        Filter.MENTIONS -> mentions == 0
        Filter.THREADS -> threads == 0
        Filter.CALLS -> calls == 0
    }
}
