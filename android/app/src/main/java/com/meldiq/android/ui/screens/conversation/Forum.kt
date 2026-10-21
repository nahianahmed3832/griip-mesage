package com.meldiq.android.ui.screens.conversation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.PostAdd
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.meldiq.android.R
import com.meldiq.android.data.db.MessageEntity
import com.meldiq.android.data.db.UserEntity
import com.meldiq.android.data.model.ForumRules
import com.meldiq.android.ui.TimeFmt
import com.meldiq.android.ui.components.Avatar
import com.meldiq.android.ui.components.EmptyState
import com.meldiq.android.ui.components.GlassCard
import com.meldiq.android.ui.components.GlassSheet
import com.meldiq.android.ui.components.GriipTextField
import com.meldiq.android.ui.components.PillButton
import com.meldiq.android.ui.components.PillChip
import com.meldiq.android.ui.components.TagPill
import com.meldiq.android.ui.theme.Griip
import com.meldiq.android.ui.theme.Space

/**
 * A forum channel's posts (communities, 2026-10-21): each top-level message is a post with a title; its replies are the
 * post's thread, opened by tapping it. Built from this phone's own (end-to-end encrypted) history.
 */
@Composable
fun ForumPostList(
    roots: List<MessageEntity>,
    replies: Map<String, ForumRules.Replies>,
    users: Map<String, UserEntity>,
    me: String?,
    title: (MessageEntity) -> String,
    body: (MessageEntity) -> String,
    onOpen: (MessageEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Griip.colors
    var sort by rememberSaveable { mutableStateOf(ForumRules.Sort.LATEST_ACTIVITY) }
    val posts = remember(roots, replies, sort) { ForumRules.posts(roots, replies, sort, id = { it.id }, time = { it.sortTs }, visible = { !it.deleted && it.kind == "text" }) }
    if (posts.isEmpty()) {
        EmptyState(Icons.Outlined.Forum, stringResource(R.string.ch_forum), stringResource(R.string.fm_empty), modifier)
        return
    }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 12.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillChip(stringResource(R.string.fm_sort_latest), sort == ForumRules.Sort.LATEST_ACTIVITY, { sort = ForumRules.Sort.LATEST_ACTIVITY })
                PillChip(stringResource(R.string.fm_sort_new), sort == ForumRules.Sort.NEWEST, { sort = ForumRules.Sort.NEWEST })
            }
        }
        items(posts, key = { it.root.id }) { post ->
            val m = post.root
            val author = if (m.senderUserId == me) "You" else users[m.senderUserId]?.displayName ?: "Someone"
            GlassCard(Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = 5.dp), onClick = { onOpen(m) }) {
                Text(title(m), style = Griip.type.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = c.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                body(m).takeIf { it.isNotBlank() }?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(it, style = Griip.type.bodySmall, color = c.textSecondary, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Avatar(author, users[m.senderUserId]?.avatarColor ?: m.senderUserId.hashCode(), size = 20.dp)
                    Spacer(Modifier.width(6.dp))
                    Text("$author · ${TimeFmt.listStamp(post.lastActivity)}", style = Griip.type.caption, color = c.textTertiary, modifier = Modifier.weight(1f))
                    TagPill(
                        when (post.replies) {
                            0 -> stringResource(R.string.fm_no_replies)
                            1 -> stringResource(R.string.fm_reply_one)
                            else -> stringResource(R.string.fm_replies, post.replies)
                        },
                        if (post.replies > 0) c.primaryDeep else c.textSecondary, if (post.replies > 0) c.primarySoft else c.surfaceVariant,
                    )
                }
            }
        }
    }
}

/** The forum's composer: one "New post" button instead of a message field (replies go in each post's thread). */
@Composable
fun ForumComposerBar(canPost: Boolean, onNewPost: () -> Unit) {
    val c = Griip.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = 10.dp)) {
        if (canPost) PillButton(stringResource(R.string.fm_new_post), onNewPost, Modifier.fillMaxWidth(), leadingIcon = Icons.Outlined.PostAdd, height = 48.dp)
        else Text(stringResource(R.string.fm_cant_post), style = Griip.type.bodySmall, color = c.textSecondary)
    }
}

@Composable
fun NewPostSheet(onDismiss: () -> Unit, onPost: (String, String) -> Unit) {
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    GlassSheet(onDismiss, title = stringResource(R.string.fm_new_post)) {
        GriipTextField(title, { title = it.take(ForumRules.MAX_TITLE) }, stringResource(R.string.fm_title))
        Spacer(Modifier.height(10.dp))
        GriipTextField(body, { body = it.take(4000) }, stringResource(R.string.fm_body), singleLine = false, minLines = 4)
        Spacer(Modifier.height(16.dp))
        PillButton(stringResource(R.string.fm_post), { onPost(title, body); onDismiss() }, Modifier.fillMaxWidth().padding(bottom = 8.dp), enabled = ForumRules.cleanTitle(title) != null, height = 50.dp)
    }
}
