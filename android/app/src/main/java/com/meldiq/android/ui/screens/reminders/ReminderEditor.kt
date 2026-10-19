package com.meldiq.android.ui.screens.reminders

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.meldiq.android.R
import com.meldiq.android.data.model.Recurrence
import com.meldiq.android.data.model.Reminder
import com.meldiq.android.data.model.ReminderContent
import com.meldiq.android.data.model.ReminderRef
import com.meldiq.android.data.model.ReminderRules
import com.meldiq.android.ui.TimeFmt
import com.meldiq.android.ui.components.GlassSheet
import com.meldiq.android.ui.components.GriipTextField
import com.meldiq.android.ui.components.MenuRow
import com.meldiq.android.ui.components.PillButton
import com.meldiq.android.ui.theme.Griip
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/** What the editor works on: a new reminder (no [id]) or an existing one; [source] and [ref] carry over unchanged. */
data class ReminderDraft(
    val id: String?,
    val title: String,
    val note: String?,
    val dueAt: Long,
    val recurrence: Recurrence?,
    val ref: ReminderRef?,
    val source: String,
) {
    fun content() = ReminderContent(title = title.trim(), note = note?.trim()?.takeIf { it.isNotEmpty() }, ref = ref, source = source)

    companion object {
        fun new(title: String = "", ref: ReminderRef? = null, now: Long = System.currentTimeMillis()) =
            ReminderDraft(null, title.take(ReminderRules.TITLE_MAX), null, ReminderRules.defaultTime(now, ZoneId.systemDefault()), null, ref, "personal")

        fun of(r: Reminder) = ReminderDraft(r.id, r.content?.title.orEmpty(), r.content?.note, r.dueAt, r.recurrence, r.content?.ref, r.content?.source ?: "personal")
    }
}

/** New or edited reminder: a title, an optional note, the date and time (this phone's time zone) and how it repeats. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReminderEditor(initial: ReminderDraft, onDismiss: () -> Unit, onSave: (ReminderDraft) -> Unit) {
    val c = Griip.colors
    val zone = ZoneId.systemDefault()
    var draft by remember { mutableStateOf(initial) }
    var pickDate by remember { mutableStateOf(false) }
    var pickTime by remember { mutableStateOf(false) }
    val now = System.currentTimeMillis()
    val inPast = draft.recurrence == null && draft.dueAt <= now
    GlassSheet(onDismiss, title = stringResource(if (initial.id == null) R.string.rem_new else R.string.rem_edit)) {
        GriipTextField(
            draft.title, { v -> draft = draft.copy(title = v.take(ReminderRules.TITLE_MAX)) }, stringResource(R.string.rem_field_title),
            placeholder = stringResource(R.string.rem_field_title_hint), singleLine = false,
        )
        Spacer(Modifier.height(12.dp))
        GriipTextField(
            draft.note.orEmpty(), { v -> draft = draft.copy(note = v.take(ReminderRules.NOTE_MAX)) }, stringResource(R.string.rem_field_note),
            singleLine = false, minLines = 2,
        )
        Spacer(Modifier.height(8.dp))
        MenuRow(Icons.Outlined.CalendarMonth, stringResource(R.string.rem_field_date), subtitle = TimeFmt.dayHeader(draft.dueAt), onClick = { pickDate = true }, trailing = null)
        MenuRow(Icons.Outlined.Schedule, stringResource(R.string.rem_field_time), subtitle = TimeFmt.clock(draft.dueAt) + " · " + zone.id, onClick = { pickTime = true }, trailing = null)
        Text(stringResource(R.string.rem_field_repeat), style = Griip.type.label, color = c.textSecondary, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (listOf<Recurrence?>(null) + Recurrence.entries).forEach { r ->
                FilterChip(
                    selected = draft.recurrence == r, onClick = { draft = draft.copy(recurrence = r) },
                    label = { Text(if (r == null) stringResource(R.string.rem_repeat_none) else recurrenceLabel(r)) },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = c.primarySoft, selectedLabelColor = c.textPrimary, labelColor = c.textSecondary),
                )
            }
        }
        if (inPast) Text(stringResource(R.string.rem_in_past), style = Griip.type.caption, color = c.warning, modifier = Modifier.padding(top = 8.dp))
        Spacer(Modifier.height(16.dp))
        PillButton(
            stringResource(R.string.rem_save), { onSave(draft) }, Modifier.fillMaxWidth(),
            enabled = ReminderRules.validTitle(draft.title) && !inPast, height = 52.dp,
        )
    }
    if (pickDate) {
        val local = Instant.ofEpochMilli(draft.dueAt).atZone(zone)
        val state = rememberDatePickerState(initialSelectedDateMillis = local.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { ms ->
                        val day = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()
                        draft = draft.copy(dueAt = ReminderRules.at(day, local.toLocalTime().withSecond(0).withNano(0), zone))
                    }
                    pickDate = false
                }) { Text(stringResource(R.string.rem_ok)) }
            },
            dismissButton = { TextButton(onClick = { pickDate = false }) { Text(stringResource(R.string.common_cancel)) } },
        ) { DatePicker(state) }
    }
    if (pickTime) {
        val local = Instant.ofEpochMilli(draft.dueAt).atZone(zone)
        val state = rememberTimePickerState(initialHour = local.hour, initialMinute = local.minute, is24Hour = android.text.format.DateFormat.is24HourFormat(androidx.compose.ui.platform.LocalContext.current))
        AlertDialog(
            onDismissRequest = { pickTime = false },
            title = { Text(stringResource(R.string.rem_field_time)) },
            text = { TimePicker(state) },
            confirmButton = {
                TextButton(onClick = {
                    draft = draft.copy(dueAt = ReminderRules.at(local.toLocalDate(), LocalTime.of(state.hour, state.minute), zone))
                    pickTime = false
                }) { Text(stringResource(R.string.rem_ok)) }
            },
            dismissButton = { TextButton(onClick = { pickTime = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}
