package com.meldiq.android.ui.screens.conversation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.meldiq.android.R
import com.meldiq.android.data.model.ThreadListRules
import com.meldiq.android.ui.TimeFmt
import com.meldiq.android.ui.components.EmptyState
import com.meldiq.android.ui.components.GriipTextField
import com.meldiq.android.ui.components.pressable
import com.meldiq.android.ui.theme.Griip
import com.meldiq.android.ui.theme.Shapes
import com.meldiq.android.ui.theme.Space

/**
 * The channel's threads (batch B5), as the Threads tab of its details (batch B14b): search by name; tapping one opens
 * it. [senderName] names a root's sender.
 */
internal fun LazyListScope.threadRows(
    rows: List<ThreadListRules.Row>, query: String, onQuery: (String) -> Unit, senderName: (ThreadListRules.Row) -> String, onOpen: (String) -> Unit,
) {
    if (rows.isEmpty()) {
        item(key = "threads-none") {
            EmptyState(Icons.Outlined.Forum, stringResource(R.string.b14b_none_threads), stringResource(R.string.b5_threads_empty_hint))
        }
        return
    }
    item(key = "threads-search") {
        GriipTextField(query, { onQuery(it.take(100)) }, stringResource(R.string.b5_threads_search), modifier = Modifier.padding(horizontal = Space.gutter, vertical = 4.dp))
    }
    val shown = ThreadListRules.filter(rows, query)
    if (shown.isEmpty()) item(key = "threads-no-match") {
        Text(
            stringResource(R.string.b5_threads_none_match), style = Griip.type.bodySmall, color = Griip.colors.textSecondary,
            modifier = Modifier.padding(horizontal = Space.gutter, vertical = 12.dp),
        )
    }
    items(shown, key = { "thread-" + it.rootId }) { r ->
        Box(Modifier.padding(horizontal = 12.dp)) { ThreadRow(r, senderName(r)) { onOpen(r.rootId) } }
    }
}

@Composable
private fun ThreadRow(r: ThreadListRules.Row, sender: String, onOpen: () -> Unit) {
    val c = Griip.colors
    Row(
        Modifier.fillMaxWidth().clip(Shapes.small).pressable(onOpen, onClickLabel = stringResource(R.string.b5_thread_open)).padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(32.dp).clip(Shapes.small).background(c.surfaceVariant), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Forum, null, tint = c.primary, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(r.title, style = Griip.type.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOf(sender, if (r.replies == 1) stringResource(R.string.b5_replies_one) else stringResource(R.string.b5_replies, r.replies), TimeFmt.listStamp(r.lastReplyTs)).joinToString(" · "),
                style = Griip.type.caption, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
