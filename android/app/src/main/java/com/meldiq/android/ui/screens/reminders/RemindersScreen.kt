package com.meldiq.android.ui.screens.reminders

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Snooze
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.meldiq.android.data.model.Recurrence
import com.meldiq.android.data.model.Reminder
import com.meldiq.android.data.model.ReminderRules
import com.meldiq.android.data.model.ReminderState
import com.meldiq.android.ui.LocalContainer
import com.meldiq.android.ui.Routes
import com.meldiq.android.ui.TimeFmt
import com.meldiq.android.ui.components.BannerKind
import com.meldiq.android.ui.components.EmptyState
import com.meldiq.android.ui.components.GlassSheet
import com.meldiq.android.ui.components.GriipTopBar
import com.meldiq.android.ui.components.MenuRow
import com.meldiq.android.ui.components.PillButton
import com.meldiq.android.ui.components.SegmentedPills
import com.meldiq.android.ui.components.StatusBanner
import com.meldiq.android.ui.components.pressable
import com.meldiq.android.ui.friendly
import com.meldiq.android.ui.theme.Griip
import com.meldiq.android.ui.theme.Shapes
import com.meldiq.android.ui.theme.Space
import kotlinx.coroutines.launch
import java.time.ZoneId

/**
 * Reminders (round 6, docs/REMINDERS.md): Upcoming, Overdue (went off, not dealt with) and Done. The server keeps and
 * fires them, so they arrive on every device even after a restart; what they say is sealed on the phone.
 */
@Composable
fun RemindersScreen(nav: NavHostController) {
    val container = LocalContainer.current
    val c = Griip.colors
    val scope = rememberCoroutineScope()
    val all by container.reminders.reminders.collectAsStateWithLifecycle()
    val available by container.reminders.available.collectAsStateWithLifecycle()
    var loaded by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var tab by rememberSaveable { mutableStateOf(0) }
    var editing by remember { mutableStateOf<ReminderDraft?>(null) }
    var acting by remember { mutableStateOf<Reminder?>(null) }
    LaunchedEffect(Unit) {
        runCatching { container.reminders.refresh() }.onFailure { error = it.friendly() }
        loaded = true
    }
    val groups = remember(all) { ReminderRules.group(all) }
    val shown = when (tab) {
        0 -> groups.upcoming
        1 -> groups.overdue
        else -> groups.done
    }
    val act: (suspend () -> Unit) -> Unit = { block -> scope.launch { runCatching { block() }.onSuccess { error = null }.onFailure { error = it.friendly() } } }

    Box(Modifier.fillMaxSize().background(c.background)) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
            item { GriipTopBar(stringResource(R.string.rem_title), onBack = { nav.popBackStack() }) }
            if (loaded && !available) item {
                StatusBanner(stringResource(R.string.rem_unsupported), BannerKind.Warning, Modifier.padding(horizontal = Space.gutter, vertical = 6.dp))
            }
            error?.let { item { StatusBanner(it, BannerKind.Error, Modifier.padding(horizontal = Space.gutter, vertical = 6.dp)) } }
            item {
                SegmentedPills(
                    listOf(
                        "${stringResource(R.string.rem_tab_upcoming)} · ${groups.upcoming.size}",
                        "${stringResource(R.string.rem_tab_overdue)} · ${groups.overdue.size}",
                        "${stringResource(R.string.rem_tab_done)} · ${groups.done.size}",
                    ),
                    tab, { tab = it }, Modifier.padding(horizontal = Space.gutter, vertical = 8.dp),
                )
            }
            if (loaded && shown.isEmpty()) item {
                EmptyState(
                    Icons.Outlined.NotificationsActive,
                    stringResource(if (tab == 1) R.string.rem_empty_overdue else R.string.rem_empty_title),
                    stringResource(R.string.rem_empty_body), Modifier.padding(top = 24.dp),
                )
            }
            items(shown, key = { it.id }) { r -> ReminderRow(r, onClick = { acting = r }, modifier = Modifier.animateItem()) }
        }
        if (available) {
            PillButton(
                stringResource(R.string.rem_new), { editing = ReminderDraft.new() },
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(Space.gutter).fillMaxWidth(), leadingIcon = Icons.Rounded.Add, height = 52.dp,
            )
        }
    }
    acting?.let { r ->
        ReminderActions(
            r,
            onDismiss = { acting = null },
            onDone = { acting = null; act { container.reminders.complete(r.id) } },
            onStop = { acting = null; act { container.reminders.dismiss(r.id) } },
            onSnooze = { at -> acting = null; act { container.reminders.reschedule(r.id, at) } },
            onEdit = { acting = null; editing = ReminderDraft.of(r) },
            onDelete = { acting = null; act { container.reminders.delete(r.id) } },
            onOpen = r.content?.ref?.conversationId?.let { conv -> { acting = null; nav.navigate(Routes.conversation(conv)) } },
        )
    }
    editing?.let { draft ->
        ReminderEditor(
            draft, onDismiss = { editing = null },
            onSave = { d ->
                editing = null
                act {
                    val existing = d.id
                    if (existing == null) container.reminders.create(d.title, d.note, d.dueAt, d.recurrence, d.ref)
                    else container.reminders.edit(existing, d.content(), d.dueAt, d.recurrence)
                }
            },
        )
    }
}

@Composable
private fun ReminderRow(r: Reminder, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = Griip.colors
    val title = r.content?.title ?: stringResource(R.string.rem_unreadable)
    val overdue = r.state == ReminderState.FIRED
    Row(
        modifier.fillMaxWidth().pressable(onClick).padding(horizontal = Space.gutter, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).clip(Shapes.small).background(if (overdue) c.warningSoft else c.surfaceVariant), contentAlignment = Alignment.Center) {
            Icon(
                when (r.state) {
                    ReminderState.DONE -> Icons.Outlined.CheckCircle
                    ReminderState.DISMISSED -> Icons.Outlined.NotificationsOff
                    else -> Icons.Outlined.Alarm
                },
                null, tint = if (overdue) c.warning else c.primary, modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = Griip.type.body.copy(fontWeight = FontWeight.SemiBold), color = c.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val whenText = TimeFmt.dayHeader(r.dueAt) + " · " + TimeFmt.clock(r.dueAt)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when {
                        overdue -> stringResource(R.string.rem_went_off, TimeFmt.dayHeader(r.firedAt ?: r.dueAt) + " · " + TimeFmt.clock(r.firedAt ?: r.dueAt))
                        r.state == ReminderState.DONE -> stringResource(R.string.rem_done_label)
                        r.state == ReminderState.DISMISSED -> stringResource(R.string.rem_stopped_label)
                        else -> whenText
                    },
                    style = Griip.type.caption, color = if (overdue) c.warning else c.textSecondary,
                )
                r.recurrence?.let {
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.Outlined.Repeat, null, tint = c.textSecondary, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(2.dp))
                    Text(recurrenceLabel(it), style = Griip.type.caption, color = c.textSecondary)
                }
            }
            if (r.deliveryFailed) Text(stringResource(R.string.rem_delivery_failed), style = Griip.type.caption, color = c.danger)
            r.content?.note?.let { Text(it, style = Griip.type.caption, color = c.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
    }
}

@Composable
internal fun recurrenceLabel(r: Recurrence): String = stringResource(
    when (r) {
        Recurrence.DAILY -> R.string.rem_repeat_daily
        Recurrence.WEEKDAYS -> R.string.rem_repeat_weekdays
        Recurrence.WEEKLY -> R.string.rem_repeat_weekly
        Recurrence.MONTHLY -> R.string.rem_repeat_monthly
    },
)

/** Done, snooze, edit, stop (without completing anything) and delete. */
@Composable
private fun ReminderActions(
    r: Reminder,
    onDismiss: () -> Unit,
    onDone: () -> Unit,
    onStop: () -> Unit,
    onSnooze: (Long) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onOpen: (() -> Unit)?,
) {
    val c = Griip.colors
    val now = System.currentTimeMillis()
    val zone = ZoneId.systemDefault()
    val finished = r.state == ReminderState.DONE || r.state == ReminderState.DISMISSED
    GlassSheet(onDismiss, title = r.content?.title ?: stringResource(R.string.rem_unreadable)) {
        if (onOpen != null) MenuRow(Icons.AutoMirrored.Outlined.OpenInNew, stringResource(R.string.rem_open_message), onClick = onOpen, trailing = null)
        if (!finished) MenuRow(Icons.Outlined.CheckCircle, stringResource(R.string.rem_mark_done), tint = c.success, tintBg = c.successSoft, onClick = onDone, trailing = null)
        listOf(
            ReminderRules.Snooze.MINUTES_10 to R.string.rem_snooze_10m,
            ReminderRules.Snooze.HOUR_1 to R.string.rem_snooze_1h,
            ReminderRules.Snooze.EVENING to R.string.rem_snooze_evening,
            ReminderRules.Snooze.TOMORROW to R.string.rem_snooze_tomorrow,
        ).forEach { (choice, label) ->
            val at = ReminderRules.snoozeUntil(choice, now, zone)
            MenuRow(Icons.Outlined.Snooze, stringResource(label), subtitle = TimeFmt.dayHeader(at) + " · " + TimeFmt.clock(at), onClick = { onSnooze(at) }, trailing = null)
        }
        if (r.content != null) MenuRow(Icons.Outlined.Edit, stringResource(R.string.rem_edit), onClick = onEdit, trailing = null)
        if (!finished) MenuRow(Icons.Outlined.NotificationsOff, stringResource(R.string.rem_stop), subtitle = stringResource(R.string.rem_stop_sub), tint = c.textSecondary, tintBg = c.surfaceVariant, onClick = onStop, trailing = null)
        MenuRow(Icons.Outlined.DeleteOutline, stringResource(R.string.rem_delete), tint = c.danger, tintBg = c.dangerSoft, onClick = onDelete, trailing = null)
    }
}
