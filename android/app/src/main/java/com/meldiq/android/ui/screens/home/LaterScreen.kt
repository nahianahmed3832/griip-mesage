package com.meldiq.android.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.AlarmOff
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.BookmarkRemove
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.meldiq.android.R
import com.meldiq.android.data.api.SavedItemDto
import com.meldiq.android.data.db.ConversationEntity
import com.meldiq.android.data.db.MessageEntity
import com.meldiq.android.data.model.ReminderTimes
import com.meldiq.android.data.repo.ConversationRepository
import com.meldiq.android.ui.LocalContainer
import com.meldiq.android.ui.Routes
import com.meldiq.android.ui.TimeFmt
import com.meldiq.android.ui.components.BannerKind
import com.meldiq.android.ui.components.CircleIconButton
import com.meldiq.android.ui.components.EmptyState
import com.meldiq.android.ui.components.GlassSheet
import com.meldiq.android.ui.components.GriipTopBar
import com.meldiq.android.ui.components.MenuRow
import com.meldiq.android.ui.components.SegmentedPills
import com.meldiq.android.ui.components.StatusBanner
import com.meldiq.android.ui.components.pressable
import com.meldiq.android.ui.theme.Griip
import com.meldiq.android.ui.theme.Shapes
import com.meldiq.android.ui.theme.Space

/**
 * Home › Later (2026-10-19): messages saved for later, with optional reminders, on every device of the person (the ids
 * are kept in their personal preferences on the server; the messages themselves are read from this phone). In progress
 * and Done; open, remind, finish or remove each one.
 */
@Composable
fun LaterScreen(nav: NavHostController) {
    val container = LocalContainer.current
    val c = Griip.colors
    val saved by container.navPrefs.saved.collectAsStateWithLifecycle()
    val supported by produceState<Boolean?>(null) { value = container.navPrefs.savingSupported() }
    var tab by rememberSaveable { mutableStateOf(0) }
    var remindFor by remember { mutableStateOf<SavedItemDto?>(null) }
    val open = remember(saved) { saved.filterNot { it.done } }
    val done = remember(saved) { saved.filter { it.done } }
    val shown = if (tab == 0) open else done
    // The messages and conversations, read from this phone's encrypted database.
    val loaded by produceState(Triple(emptyMap<String, MessageEntity>(), emptyMap<String, ConversationEntity>(), emptyMap<String, String>()), saved) {
        val msgs = saved.mapNotNull { container.db.messages().get(it.messageId) }.associateBy { it.id }
        val convs = saved.map { it.conversationId }.distinct().mapNotNull { container.db.conversations().get(it) }.associateBy { it.id }
        val names = msgs.values.map { it.senderUserId }.distinct().mapNotNull { id -> container.db.users().get(id)?.let { id to it.displayName } }.toMap()
        value = Triple(msgs, convs, names)
    }
    val me = container.session.session?.userId
    val now = System.currentTimeMillis()

    LazyColumn(Modifier.fillMaxSize().background(c.background), contentPadding = PaddingValues(bottom = 32.dp)) {
        item { GriipTopBar(stringResource(R.string.later_title), onBack = { nav.popBackStack() }) }
        if (supported == false) item {
            StatusBanner(stringResource(R.string.later_unsupported), BannerKind.Warning, Modifier.padding(horizontal = Space.gutter, vertical = 6.dp))
        }
        item {
            SegmentedPills(
                listOf("${stringResource(R.string.later_tab_open)} · ${open.size}", "${stringResource(R.string.later_tab_done)} · ${done.size}"),
                tab, { tab = it }, Modifier.padding(horizontal = Space.gutter, vertical = 8.dp),
            )
        }
        if (shown.isEmpty()) item {
            EmptyState(Icons.Outlined.BookmarkBorder, stringResource(R.string.later_empty_title), stringResource(R.string.later_empty_body), Modifier.padding(top = 24.dp))
        }
        items(shown, key = { it.messageId }) { item ->
            val m = loaded.first[item.messageId]
            val conv = loaded.second[item.conversationId]
            SavedRow(
                item, m, conv, me, now, senderName = m?.let { loaded.third[it.senderUserId] },
                onOpen = { nav.navigate(Routes.conversation(item.conversationId)) },
                onRemind = { remindFor = item },
                onDone = { container.navPrefs.setSavedDone(item.messageId, !item.done) },
                onRemove = { container.navPrefs.removeSaved(item.messageId) },
                modifier = Modifier.animateItem(),
            )
        }
    }
    remindFor?.let { item ->
        RemindSheet(item.remindAt, onPick = { at -> container.navPrefs.remind(item.messageId, at); remindFor = null }, onDismiss = { remindFor = null })
    }
}

@Composable
private fun SavedRow(
    item: SavedItemDto,
    m: MessageEntity?,
    conv: ConversationEntity?,
    me: String?,
    now: Long,
    senderName: String?,
    onOpen: () -> Unit,
    onRemind: () -> Unit,
    onDone: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Griip.colors
    var menu by remember { mutableStateOf(false) }
    val title = conv?.let { ConversationRepository.title(it, me).let { t -> if (it.type == "CHANNEL") "#$t" else t } } ?: "—"
    val sender = if (m?.outgoing == true) stringResource(R.string.conv_you) else senderName
    Column(
        modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = 5.dp).clip(Shapes.card).background(c.surface)
            .border(1.dp, c.border, Shapes.card).pressable(onOpen, onClickLabel = stringResource(R.string.conv_view_thread)).padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = Griip.type.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    stringResource(R.string.later_saved_in, TimeFmt.dayHeader(item.savedAt), TimeFmt.clock(item.savedAt)) + (sender?.let { " · $it" } ?: ""),
                    style = Griip.type.caption, color = c.textSecondary, maxLines = 1,
                )
            }
            Box {
                CircleIconButton(Icons.Rounded.MoreVert, "More actions", { menu = true }, tint = c.textSecondary)
                DropdownMenu(menu, { menu = false }, containerColor = c.glassStrong, shape = Shapes.card) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.later_remind), color = c.textPrimary) },
                        leadingIcon = { Icon(Icons.Outlined.Alarm, null, tint = c.textSecondary) }, onClick = { menu = false; onRemind() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(if (item.done) R.string.later_mark_open else R.string.later_mark_done), color = c.textPrimary) },
                        leadingIcon = { Icon(if (item.done) Icons.Outlined.Replay else Icons.Outlined.CheckCircle, null, tint = c.textSecondary) }, onClick = { menu = false; onDone() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.later_remove), color = c.danger) },
                        leadingIcon = { Icon(Icons.Outlined.BookmarkRemove, null, tint = c.danger) }, onClick = { menu = false; onRemove() },
                    )
                }
            }
        }
        Text(
            m?.text?.takeIf { it.isNotBlank() && !m.deleted } ?: stringResource(R.string.later_missing),
            style = Griip.type.bodySmall, color = if (m == null || m.deleted) c.textSecondary else c.textPrimary,
            maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp, end = 10.dp),
        )
        item.remindAt?.takeIf { !item.done }?.let { at ->
            val overdue = ReminderTimes.overdue(at, item.done, now)
            val label = TimeFmt.dayHeader(at) + " " + TimeFmt.clock(at)
            Row(
                Modifier.padding(top = 8.dp).clip(Shapes.pill).background(if (overdue) c.dangerSoft else c.primarySoft).padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Alarm, null, tint = if (overdue) c.danger else c.primaryDeep, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(5.dp))
                Text(
                    if (overdue) stringResource(R.string.later_overdue_label, label) else stringResource(R.string.later_due, label),
                    style = Griip.type.caption.copy(fontWeight = FontWeight.SemiBold), color = if (overdue) c.danger else c.primaryDeep,
                )
            }
        }
    }
}

/** When to be reminded about a saved message: soon, tomorrow morning, next week, or not at all. */
@Composable
fun RemindSheet(current: Long?, onPick: (Long?) -> Unit, onDismiss: () -> Unit) {
    val c = Griip.colors
    val now = System.currentTimeMillis()
    GlassSheet(onDismiss, title = stringResource(R.string.later_remind)) {
        listOf(
            ReminderTimes.Choice.IN_1_HOUR to R.string.later_remind_1h,
            ReminderTimes.Choice.IN_3_HOURS to R.string.later_remind_3h,
            ReminderTimes.Choice.TOMORROW_MORNING to R.string.later_remind_tomorrow,
            ReminderTimes.Choice.NEXT_WEEK to R.string.later_remind_next_week,
        ).forEach { (choice, label) ->
            val at = ReminderTimes.at(choice, now)
            MenuRow(Icons.Outlined.Alarm, stringResource(label), subtitle = TimeFmt.dayHeader(at) + " " + TimeFmt.clock(at), onClick = { onPick(at) }, trailing = null)
        }
        if (current != null) MenuRow(Icons.Outlined.AlarmOff, stringResource(R.string.later_remind_clear), tint = c.textSecondary, tintBg = c.surfaceVariant, onClick = { onPick(null) }, trailing = null)
    }
}
