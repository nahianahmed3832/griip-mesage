package com.meldiq.android.ui.screens.conversation

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.outlined.Search
import com.meldiq.android.data.api.ForumTagDto
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
import com.meldiq.android.ui.components.pressable
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
    /** The forum's tags (servers with `forum-tags`), and a post's tag ids (from inside its encrypted message). */
    forumTags: List<ForumTagDto> = emptyList(),
    postTags: (MessageEntity) -> List<String> = { emptyList() },
    /** Batch B8: the forum's post guidelines, and a post's most-used reaction. */
    guidelines: String? = null,
    topReaction: (MessageEntity) -> Pair<String, Int>? = { null },
) {
    val c = Griip.colors
    var sort by rememberSaveable { mutableStateOf(ForumRules.Sort.LATEST_ACTIVITY) }
    var query by rememberSaveable { mutableStateOf("") }
    var selected by rememberSaveable { mutableStateOf(emptySet<String>()) }
    var matchAll by rememberSaveable { mutableStateOf(false) }
    val posts = remember(roots, replies, sort) { ForumRules.posts(roots, replies, sort, id = { it.id }, time = { it.sortTs }, visible = { !it.deleted && it.kind == "text" }) }
    val shown = remember(posts, query, selected, matchAll) { ForumRules.filter(posts, query, selected, title, body, postTags, matchAll) }
    val tagNames = remember(forumTags) { forumTags.associate { it.id to it.name } }
    if (posts.isEmpty()) {
        Column(modifier) {
            guidelines?.let { ForumGuidelinesCard(it, Modifier.padding(horizontal = Space.gutter, vertical = 8.dp)) }
            EmptyState(Icons.Outlined.Forum, stringResource(R.string.ch_forum), stringResource(R.string.fm_empty))
        }
        return
    }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 12.dp)) {
        guidelines?.let { g -> item(key = "guidelines") { ForumGuidelinesCard(g, Modifier.padding(horizontal = Space.gutter, vertical = 6.dp)) } }
        item(key = "search") {
            GriipTextField(
                query, { query = it.take(80) }, stringResource(R.string.nv_search_posts), Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = 8.dp),
                leadingIcon = Icons.Outlined.Search,
            )
        }
        item(key = "filters") {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = Space.gutter, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillChip(stringResource(R.string.fm_sort_latest), sort == ForumRules.Sort.LATEST_ACTIVITY, { sort = ForumRules.Sort.LATEST_ACTIVITY })
                PillChip(stringResource(R.string.fm_sort_new), sort == ForumRules.Sort.NEWEST, { sort = ForumRules.Sort.NEWEST })
                if (forumTags.isNotEmpty()) {
                    PillChip(stringResource(R.string.nv_all_tags), selected.isEmpty(), { selected = emptySet() })
                    forumTags.forEach { t -> PillChip(t.name, t.id in selected, { selected = if (t.id in selected) selected - t.id else selected + t.id }) }
                    // Batch B8: with two or more tags chosen, posts with any of them or with all of them.
                    if (selected.size >= 2) PillChip(stringResource(if (matchAll) R.string.b8_match_all else R.string.b8_match_any), matchAll, { matchAll = !matchAll })
                }
            }
        }
        if (shown.isEmpty()) item(key = "no-match") {
            EmptyState(
                Icons.Outlined.Search, stringResource(R.string.nv_no_matching_posts), stringResource(R.string.ux_no_match_sub),
                action = stringResource(R.string.ux_clear_filters), onAction = { selected = emptySet(); query = "" },
            )
        }
        items(shown, key = { it.root.id }) { post ->
            val m = post.root
            val author = if (m.senderUserId == me) "You" else users[m.senderUserId]?.displayName ?: "Someone"
            GlassCard(Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = 5.dp), onClick = { onOpen(m) }) {
                Text(title(m), style = Griip.type.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = c.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val labels = postTags(m).mapNotNull { tagNames[it] }
                if (labels.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { labels.forEach { TagPill(it, c.primaryDeep, c.primarySoft) } }
                }
                body(m).takeIf { it.isNotBlank() }?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(it, style = Griip.type.bodySmall, color = c.textSecondary, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Avatar(users[m.senderUserId]?.displayName ?: author, users[m.senderUserId]?.avatarColor ?: m.senderUserId.hashCode(), size = 20.dp)
                    Spacer(Modifier.width(6.dp))
                    Text("$author · ${TimeFmt.listStamp(post.lastActivity)}", style = Griip.type.caption, color = c.textTertiary, modifier = Modifier.weight(1f))
                    topReaction(m)?.let { (emoji, n) ->
                        TagPill("$emoji $n", c.textSecondary, c.surfaceVariant)
                        Spacer(Modifier.width(6.dp))
                    }
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
fun NewPostSheet(onDismiss: () -> Unit, forumTags: List<ForumTagDto> = emptyList(), guidelines: String? = null, onPost: (String, String, List<String>) -> Unit) {
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var tags by remember { mutableStateOf(emptyList<String>()) }
    GlassSheet(onDismiss, title = stringResource(R.string.fm_new_post)) {
        guidelines?.let { ForumGuidelinesCard(it, Modifier.padding(bottom = 10.dp)) }
        GriipTextField(title, { title = it.take(ForumRules.MAX_TITLE) }, stringResource(R.string.fm_title))
        Spacer(Modifier.height(10.dp))
        GriipTextField(body, { body = it.take(4000) }, stringResource(R.string.fm_body), singleLine = false, minLines = 4)
        if (forumTags.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text(stringResource(R.string.nv_tags), style = Griip.type.label, color = Griip.colors.textSecondary)
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                forumTags.forEach { t ->
                    PillChip(t.name, t.id in tags, {
                        tags = when {
                            t.id in tags -> tags - t.id
                            tags.size < ForumRules.MAX_TAGS_PER_POST -> tags + t.id
                            else -> tags
                        }
                    })
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        PillButton(stringResource(R.string.fm_post), { onPost(title, body, tags); onDismiss() }, Modifier.fillMaxWidth().padding(bottom = 8.dp), enabled = ForumRules.cleanTitle(title) != null, height = 50.dp)
    }
}


/** Batch B8 (FORUM-CTRL-006 "Post Guidelines"): the forum's guidelines, the first lines with Show all. */
@Composable
fun ForumGuidelinesCard(text: String, modifier: Modifier = Modifier) {
    val c = Griip.colors
    var expanded by rememberSaveable { mutableStateOf(false) }
    val (preview, more) = remember(text) { ForumRules.guidelinesPreview(text) }
    GlassCard(modifier.fillMaxWidth()) {
        Text(stringResource(R.string.b8_guidelines), style = Griip.type.label, color = c.textSecondary)
        Spacer(Modifier.height(4.dp))
        Text(if (expanded) text.trim() else preview, style = Griip.type.bodySmall, color = c.textPrimary)
        if (more) {
            Text(
                stringResource(if (expanded) R.string.b8_show_less else R.string.b8_show_all), style = Griip.type.caption, color = c.primary,
                modifier = Modifier.padding(top = 6.dp).pressable({ expanded = !expanded }),
            )
        }
    }
}
