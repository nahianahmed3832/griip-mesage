package com.meldiq.android.ui.screens.conversation

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.meldiq.android.R
import com.meldiq.android.data.db.MessageEntity
import com.meldiq.android.data.db.UserEntity
import com.meldiq.android.ui.TimeFmt
import com.meldiq.android.ui.components.CircleIconButton
import com.meldiq.android.ui.components.UserAvatar
import com.meldiq.android.ui.components.pressable
import com.meldiq.android.ui.theme.Griip
import com.meldiq.android.ui.theme.Shapes

/** The header while searching this conversation: back, the query (focused), and clear. */
@Composable
fun ConversationSearchBar(query: String, onQuery: (String) -> Unit, onClose: () -> Unit) {
    val c = Griip.colors
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val hint = stringResource(R.string.conv_search_hint)
    Row(
        Modifier.fillMaxWidth().background(c.surface).statusBarsPadding().height(64.dp).padding(start = 4.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircleIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onClose)
        Row(
            Modifier.weight(1f).height(44.dp).clip(Shapes.pill).background(c.surfaceVariant).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Search, null, tint = c.textSecondary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (query.isEmpty()) Text(hint, style = Griip.type.body, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                BasicTextField(
                    query, onQuery, singleLine = true, textStyle = Griip.type.body.copy(color = c.textPrimary), cursorBrush = SolidColor(c.primary),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus).semantics { contentDescription = hint },
                )
            }
            if (query.isNotEmpty()) CircleIconButton(Icons.Rounded.Close, "Clear", { onQuery("") }, size = 32.dp, tint = c.textSecondary)
        }
    }
}

/** Matching messages, newest first, with the query highlighted; tapping one jumps to it in the conversation. */
@Composable
fun ConversationSearchResults(query: String, results: List<MessageEntity>, users: Map<String, UserEntity>, me: String?, onOpen: (MessageEntity) -> Unit, modifier: Modifier = Modifier) {
    val c = Griip.colors
    LazyColumn(modifier, contentPadding = PaddingValues(vertical = 8.dp)) {
        item {
            Text(
                if (results.isEmpty()) stringResource(R.string.conv_search_none) else stringResource(R.string.conv_search_count, results.size),
                style = Griip.type.label.copy(fontWeight = FontWeight.SemiBold), color = c.textSecondary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        items(results, key = { it.id }) { m ->
            val sender = users[m.senderUserId]
            val name = if (m.outgoing) stringResource(R.string.conv_you) else sender?.displayName ?: "Someone"
            val avatarName = sender?.displayName?.takeIf { it.isNotBlank() } ?: name
            Row(
                Modifier.fillMaxWidth().pressable({ onOpen(m) }).padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.Top,
            ) {
                UserAvatar(m.senderUserId, avatarName, sender?.avatarColor ?: 0, sender?.photoRev, size = 32.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(name, style = Griip.type.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = c.textPrimary, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                        Spacer(Modifier.width(8.dp))
                        Text(TimeFmt.dayHeader(m.sortTs) + " · " + TimeFmt.clock(m.sortTs), style = Griip.type.caption, color = c.textSecondary, maxLines = 1)
                    }
                    Text(highlight(m.text, query, c.primarySoft, c.textPrimary), style = Griip.type.bodySmall, color = c.textPrimary, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

private fun highlight(text: String, query: String, bg: androidx.compose.ui.graphics.Color, fg: androidx.compose.ui.graphics.Color) = buildAnnotatedString {
    val q = query.trim()
    if (q.isEmpty()) { append(text); return@buildAnnotatedString }
    var from = 0
    while (true) {
        val i = text.indexOf(q, from, ignoreCase = true)
        if (i < 0) { append(text.substring(from)); break }
        append(text.substring(from, i))
        withStyle(SpanStyle(background = bg, color = fg, fontWeight = FontWeight.Bold)) { append(text.substring(i, i + q.length)) }
        from = i + q.length
    }
}

