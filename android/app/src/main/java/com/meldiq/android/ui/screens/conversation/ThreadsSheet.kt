package com.meldiq.android.ui.screens.conversation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.meldiq.android.ui.components.GlassSheet
import com.meldiq.android.ui.components.GriipTextField
import com.meldiq.android.ui.components.pressable
import com.meldiq.android.ui.theme.Griip
import com.meldiq.android.ui.theme.Shapes

/** The channel's threads (batch B5): search by name; tapping one opens it. [senderName] names a root's sender. */
@Composable
fun ThreadsSheet(rows: List<ThreadListRules.Row>, senderName: (ThreadListRules.Row) -> String, onOpen: (String) -> Unit, onDismiss: () -> Unit) {
    val c = Griip.colors
    var query by remember { mutableStateOf("") }
    GlassSheet(onDismiss, title = stringResource(R.string.b5_threads)) {
        if (rows.isEmpty()) {
            Text(stringResource(R.string.b5_threads_empty), style = Griip.type.body, color = c.textPrimary, modifier = Modifier.padding(top = 4.dp))
            Text(stringResource(R.string.b5_threads_empty_hint), style = Griip.type.bodySmall, color = c.textSecondary, modifier = Modifier.padding(top = 4.dp, bottom = 16.dp))
            return@GlassSheet
        }
        GriipTextField(query, { query = it.take(100) }, stringResource(R.string.b5_threads_search))
        Spacer(Modifier.height(8.dp))
        val shown = remember(rows, query) { ThreadListRules.filter(rows, query) }
        if (shown.isEmpty()) Text(stringResource(R.string.b5_threads_none_match), style = Griip.type.bodySmall, color = c.textSecondary, modifier = Modifier.padding(vertical = 12.dp))
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
            items(shown, key = { it.rootId }) { r -> ThreadRow(r, senderName(r)) { onOpen(r.rootId) } }
        }
        Spacer(Modifier.height(8.dp))
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
