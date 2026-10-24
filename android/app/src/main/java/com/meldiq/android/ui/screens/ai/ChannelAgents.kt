package com.meldiq.android.ui.screens.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meldiq.android.R
import com.meldiq.android.data.ai.AiChannelAgentsRes
import com.meldiq.android.data.ai.AiPost
import com.meldiq.android.data.db.ConversationEntity
import com.meldiq.android.data.repo.ServerFeatures
import com.meldiq.android.ui.LocalContainer
import com.meldiq.android.ui.TimeFmt
import com.meldiq.android.ui.components.ButtonTone
import com.meldiq.android.ui.components.GlassSheet
import com.meldiq.android.ui.components.PillButton
import com.meldiq.android.ui.components.ShimmerList
import com.meldiq.android.ui.components.TagPill
import com.meldiq.android.ui.components.pressable
import com.meldiq.android.ui.theme.Griip
import com.meldiq.android.ui.theme.Shapes

private val NONE = AiChannelAgentsRes()

/**
 * The AI agents assigned to [conv] and what this person may do with them: none outside community channels, on a
 * server without AI Workforce, or when the fetch fails (agents are an extra; the channel works without them). Fetched
 * again on the channel's `ai.posts` frame.
 */
@Composable
fun rememberChannelAgents(conv: ConversationEntity?): AiChannelAgentsRes {
    val container = LocalContainer.current
    val posts by container.aiSignals.posts.collectAsStateWithLifecycle()
    val id = conv?.id
    val channel = conv?.type == "CHANNEL" && conv.orgId != null && !conv.voice
    val rev = id?.let { posts[it] } ?: 0
    val result by produceState(NONE, id, channel, rev) {
        value = if (id == null || !channel || container.features.has(ServerFeatures.AI_WORKFORCE) != true) NONE
        else runCatching { container.ai.channelAgents(id) }.getOrDefault(NONE)
    }
    return result
}

/**
 * A slim line above the composer when agents work in a channel: who, the latest thing one posted, a mark when an
 * approval waits, and Ask. Tapping it opens the agents' posts (kept by the server, not end-to-end encrypted).
 */
@Composable
fun ChannelAgentsBar(orgId: String, conversationId: String, agents: AiChannelAgentsRes, onAsk: () -> Unit) {
    if (agents.agents.isEmpty()) return
    val container = LocalContainer.current
    val postsRev by container.aiSignals.posts.collectAsStateWithLifecycle()
    val posts by produceState<List<AiPost>?>(null, conversationId, postsRev[conversationId]) {
        value = runCatching { container.ai.channelPosts(conversationId) }.getOrNull() ?: value
    }
    var open by remember { mutableStateOf(false) }
    val c = Griip.colors
    val list = posts.orEmpty()
    val latest = list.lastOrNull { it.kind != "request" }
    val waiting = list.count { it.kind == "approval" }
    val names = agents.agents.joinToString(", ") { it.displayName }
    Row(
        Modifier.fillMaxWidth().background(c.primarySoft).pressable({ open = true }).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Outlined.SmartToy, null, tint = c.primaryDeep, modifier = Modifier.size(18.dp))
        Text(
            stringResource(if (agents.agents.size == 1) R.string.aw_bar_one else R.string.aw_bar_many, names) +
                (latest?.let { " · ${it.agent.displayName}: ${it.body.lineSequence().firstOrNull().orEmpty()}" } ?: ""),
            style = Griip.type.caption, color = c.primaryDeep, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        if (waiting > 0) TagPill(stringResource(R.string.aw_bar_approval), c.warning, c.warningSoft)
        if (agents.permissions.canUse) PillButton(stringResource(R.string.aw_ask), onAsk, tone = ButtonTone.Glass, leadingIcon = Icons.Outlined.Add, height = 32.dp)
    }
    if (open) ChannelAgentsSheet(orgId, agents, posts, onAsk = { open = false; onAsk() }, onDismiss = { open = false })
}

@Composable
private fun ChannelAgentsSheet(orgId: String, agents: AiChannelAgentsRes, posts: List<AiPost>?, onAsk: () -> Unit, onDismiss: () -> Unit) {
    val container = LocalContainer.current
    val users by container.db.users().observeAll().collectAsStateWithLifecycle(emptyList())
    var job by remember { mutableStateOf<String?>(null) }
    val c = Griip.colors
    GlassSheet(onDismiss, title = stringResource(R.string.aw_channel_title)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            AiNotice(text = stringResource(R.string.aw_notice) + " " + stringResource(R.string.aw_channel_kept))
            agents.agents.forEach { a ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AgentAvatar(orgId, a.id, a.displayName, a.avatarColor, a.avatar != null, size = 28.dp)
                    Text(a.displayName, style = Griip.type.bodyMedium, color = c.textPrimary)
                    Text(
                        when (a.activation) {
                            "mention" -> "@${a.displayName}"
                            "command" -> "/" + com.meldiq.android.data.ai.AgentRules.commandName(a.displayName)
                            else -> stringResource(R.string.aw_activation_explicit)
                        },
                        style = Griip.type.caption, color = c.textSecondary,
                    )
                }
            }
            if (agents.permissions.canUse) PillButton(stringResource(R.string.aw_ask_title), onAsk, Modifier.fillMaxWidth(), leadingIcon = Icons.Outlined.Add, height = 44.dp)
            when {
                posts == null -> ShimmerList(2)
                posts.isEmpty() -> Text(stringResource(R.string.aw_channel_empty), style = Griip.type.body, color = c.textSecondary)
                else -> posts.forEach { p -> PostItem(orgId, p, p.authorId?.let { id -> users.firstOrNull { it.id == id }?.displayName }, agents.permissions.canApprove) { job = it } }
            }
        }
    }
    job?.let { JobSheet(orgId, it, agents.permissions, onDismiss = { job = null }) }
}

/** One post: a person's request ("Ana asked Writer …"), or what an agent said, with its files and a link to the request. */
@Composable
private fun PostItem(orgId: String, post: AiPost, author: String?, canApprove: Boolean, onOpen: (String) -> Unit) {
    val c = Griip.colors
    if (post.kind == "request") {
        Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
            Text(stringResource(R.string.aw_post_asked, author ?: stringResource(R.string.aw_someone), post.agent.displayName) + " · " + TimeFmt.relative(post.createdAt), style = Griip.type.caption, color = c.textSecondary)
            Text(post.body, style = Griip.type.body, color = c.textPrimary)
        }
        return
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        AgentAvatar(orgId, post.agent.installId, post.agent.displayName, post.agent.avatarColor, post.agent.hasAvatar, size = 34.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                listOfNotNull(post.agent.displayName, stringResource(R.string.aw_badge), post.agent.skill, TimeFmt.relative(post.createdAt)).joinToString(" · "),
                style = Griip.type.caption, color = c.textSecondary,
            )
            val bg = when (post.kind) {
                "error" -> c.dangerSoft
                "approval" -> c.warningSoft
                else -> c.surfaceVariant
            }
            Text(post.body, style = Griip.type.body, color = c.textPrimary, modifier = Modifier.fillMaxWidth().background(bg, Shapes.tile).padding(10.dp))
            post.files.forEach { AiFileRow(orgId, it) }
            post.jobId?.let { id ->
                Text(
                    stringResource(if (post.kind == "approval" && canApprove) R.string.aw_post_review else R.string.aw_post_open),
                    style = Griip.type.label, color = c.primary, modifier = Modifier.pressable({ onOpen(id) }).padding(vertical = 2.dp),
                )
            }
        }
    }
}

