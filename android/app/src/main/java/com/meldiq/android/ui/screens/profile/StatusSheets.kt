package com.meldiq.android.ui.screens.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.meldiq.android.R
import com.meldiq.android.data.api.UserDto
import com.meldiq.android.ui.LocalContainer
import com.meldiq.android.ui.TimeFmt
import com.meldiq.android.ui.components.BannerKind
import com.meldiq.android.ui.components.ButtonTone
import com.meldiq.android.ui.components.GlassSheet
import com.meldiq.android.ui.components.GriipTextField
import com.meldiq.android.ui.components.MenuRow
import com.meldiq.android.ui.components.PillButton
import com.meldiq.android.ui.components.PillChip
import com.meldiq.android.ui.components.SectionHeader
import com.meldiq.android.ui.components.StatusBanner
import com.meldiq.android.ui.friendly
import com.meldiq.android.ui.theme.Griip
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * Statuses and pausing notifications (round 3, the reference's "Set a status" and "Notifications paused"). Pure rules,
 * unit-tested in `StatusRulesTest`; the server keeps the status, its end time and the pause for every device.
 */
object StatusRules {
    enum class ClearAfter { NEVER, MINUTES_30, HOUR_1, HOURS_4, TODAY, THIS_WEEK }
    enum class Pause { MINUTES_30, HOUR_1, HOURS_2, TOMORROW_9 }

    /** A suggestion from the reference: its emoji, its wording and how long it lasts. */
    data class Preset(val emoji: String, val textRes: Int, val clearAfter: ClearAfter)

    val PRESETS = listOf(
        Preset("📅", R.string.r3_preset_meeting, ClearAfter.HOUR_1),
        Preset("🚌", R.string.r3_preset_commuting, ClearAfter.MINUTES_30),
        Preset("🤒", R.string.r3_preset_sick, ClearAfter.TODAY),
        Preset("🌴", R.string.r3_preset_vacation, ClearAfter.NEVER),
        Preset("🏡", R.string.r3_preset_remote, ClearAfter.TODAY),
    )

    private const val MINUTE = 60_000L
    private const val HOUR = 60 * MINUTE
    /** The server's limits, so a choice it would refuse is never offered. */
    const val STATUS_MAX_LENGTH = 140
    const val EMOJI_MAX_LENGTH = 16

    /** When a status chosen at [now] ends: null for "Don't clear"; "Today" is midnight, "This week" Sunday midnight. */
    fun expiresAt(choice: ClearAfter, now: Long, zone: ZoneId): Long? = when (choice) {
        ClearAfter.NEVER -> null
        ClearAfter.MINUTES_30 -> now + 30 * MINUTE
        ClearAfter.HOUR_1 -> now + HOUR
        ClearAfter.HOURS_4 -> now + 4 * HOUR
        ClearAfter.TODAY -> Instant.ofEpochMilli(now).atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        ClearAfter.THIS_WEEK -> Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
            .with(TemporalAdjusters.next(DayOfWeek.MONDAY)).atStartOfDay(zone).toInstant().toEpochMilli()
    }

    /** When notifications paused at [now] come back: "Until tomorrow, 9:00" is the next day at 09:00 local time. */
    fun pauseUntil(choice: Pause, now: Long, zone: ZoneId): Long = when (choice) {
        Pause.MINUTES_30 -> now + 30 * MINUTE
        Pause.HOUR_1 -> now + HOUR
        Pause.HOURS_2 -> now + 2 * HOUR
        Pause.TOMORROW_9 -> Instant.ofEpochMilli(now).atZone(zone).toLocalDate().plusDays(1).atTime(LocalTime.of(9, 0))
            .atZone(zone).toInstant().toEpochMilli()
    }

    /** Whether a status with this end time still shows at [now] (the server applies the same rule). */
    fun active(expiresAt: Long?, now: Long): Boolean = expiresAt == null || expiresAt > now

    /** Whether notifications are paused at [now]. */
    fun paused(until: Long?, now: Long): Boolean = until != null && until > now

    /** The one line under your name: emoji and text, or nothing when the status is empty or has ended. */
    fun line(u: UserDto?, now: Long): String? {
        if (u == null || !active(u.statusExpiresAt, now)) return null
        return listOfNotNull(u.statusEmoji, u.statusText).joinToString(" ").ifBlank { null }
    }
}

@Composable
private fun clearAfterLabel(c: StatusRules.ClearAfter): String = stringResource(
    when (c) {
        StatusRules.ClearAfter.NEVER -> R.string.r3_status_never
        StatusRules.ClearAfter.MINUTES_30 -> R.string.r3_status_30m
        StatusRules.ClearAfter.HOUR_1 -> R.string.r3_status_1h
        StatusRules.ClearAfter.HOURS_4 -> R.string.r3_status_4h
        StatusRules.ClearAfter.TODAY -> R.string.r3_status_today
        StatusRules.ClearAfter.THIS_WEEK -> R.string.r3_status_week
    },
)

/** "Set a status": suggestions, your own text and emoji, and when it clears. */
@Composable
fun StatusSheet(current: UserDto?, onDismiss: () -> Unit) {
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    val now = System.currentTimeMillis()
    val live = StatusRules.active(current?.statusExpiresAt, now)
    var text by rememberSaveable { mutableStateOf(if (live) current?.statusText.orEmpty() else "") }
    var emoji by rememberSaveable { mutableStateOf(if (live) current?.statusEmoji.orEmpty() else "") }
    var clearAfter by rememberSaveable { mutableStateOf(StatusRules.ClearAfter.NEVER) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val presetTexts = StatusRules.PRESETS.associateWith { stringResource(it.textRes) }

    fun save(t: String?, e: String?, expires: Long?) {
        busy = true
        error = null
        scope.launch {
            runCatching { container.profiles.setStatus(t, e, expires) }
                .onSuccess { onDismiss() }
                .onFailure { error = it.friendly() }
            busy = false
        }
    }

    GlassSheet({ if (!busy) onDismiss() }, title = stringResource(R.string.r3_status_title)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GriipTextField(emoji, { emoji = it.take(StatusRules.EMOJI_MAX_LENGTH) }, stringResource(R.string.r3_status_emoji), Modifier.width(96.dp), enabled = !busy)
            GriipTextField(text, { text = it.take(StatusRules.STATUS_MAX_LENGTH) }, stringResource(R.string.r3_status_hint), Modifier.weight(1f), enabled = !busy)
        }
        SectionHeader(stringResource(R.string.r3_status_clear_after))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusRules.ClearAfter.entries.forEach { c -> PillChip(clearAfterLabel(c), clearAfter == c, { clearAfter = c }) }
        }
        Spacer(Modifier.height(12.dp))
        SectionHeader(stringResource(R.string.r3_status_suggestions))
        StatusRules.PRESETS.forEach { p ->
            MenuRow(
                Icons.Outlined.Schedule, "${p.emoji}  ${presetTexts.getValue(p)}", subtitle = clearAfterLabel(p.clearAfter),
                onClick = { if (!busy) save(presetTexts.getValue(p), p.emoji, StatusRules.expiresAt(p.clearAfter, System.currentTimeMillis(), ZoneId.systemDefault())) },
                trailing = null,
            )
        }
        error?.let {
            Spacer(Modifier.height(8.dp))
            StatusBanner(it, BannerKind.Error)
        }
        Spacer(Modifier.height(12.dp))
        PillButton(
            stringResource(R.string.r3_status_save),
            { save(text.trim().ifEmpty { null }, emoji.trim().ifEmpty { null }, StatusRules.expiresAt(clearAfter, System.currentTimeMillis(), ZoneId.systemDefault())) },
            Modifier.fillMaxWidth(), enabled = !busy && (text.isNotBlank() || emoji.isNotBlank()), loading = busy,
        )
        if (live && (current?.statusText != null || current?.statusEmoji != null)) {
            Spacer(Modifier.height(8.dp))
            PillButton(stringResource(R.string.r3_status_clear), { save(null, null, null) }, Modifier.fillMaxWidth(), tone = ButtonTone.Glass, enabled = !busy)
        }
        Spacer(Modifier.height(16.dp))
    }
}

/** "Pause notifications": for a while or until tomorrow morning, on every device; or resume. */
@Composable
fun PauseSheet(pausedUntil: Long?, onDismiss: () -> Unit) {
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val paused = StatusRules.paused(pausedUntil, System.currentTimeMillis())

    fun set(until: Long?) {
        busy = true
        error = null
        scope.launch {
            runCatching { container.profiles.setNotificationsPaused(until) }
                .onSuccess { onDismiss() }
                .onFailure { error = it.friendly() }
            busy = false
        }
    }

    GlassSheet({ if (!busy) onDismiss() }, title = stringResource(R.string.r3_pause)) {
        Text(stringResource(R.string.r3_pause_sub), style = Griip.type.bodySmall, color = Griip.colors.textSecondary)
        Spacer(Modifier.height(8.dp))
        if (paused && pausedUntil != null) {
            StatusBanner(stringResource(R.string.r3_paused_until, TimeFmt.listStamp(pausedUntil) + " " + TimeFmt.clock(pausedUntil)), BannerKind.Info, icon = Icons.Outlined.NotificationsOff)
            Spacer(Modifier.height(8.dp))
            PillButton(stringResource(R.string.r3_resume), { set(null) }, Modifier.fillMaxWidth(), enabled = !busy, loading = busy)
            Spacer(Modifier.height(8.dp))
        }
        listOf(
            StatusRules.Pause.MINUTES_30 to R.string.r3_status_30m,
            StatusRules.Pause.HOUR_1 to R.string.r3_status_1h,
            StatusRules.Pause.HOURS_2 to R.string.r3_pause_2h,
            StatusRules.Pause.TOMORROW_9 to R.string.r3_pause_tomorrow,
        ).forEach { (choice, label) ->
            MenuRow(
                Icons.Outlined.NotificationsOff, stringResource(label), trailing = null,
                onClick = { if (!busy) set(StatusRules.pauseUntil(choice, System.currentTimeMillis(), ZoneId.systemDefault())) },
            )
        }
        error?.let {
            Spacer(Modifier.height(8.dp))
            StatusBanner(it, BannerKind.Error)
        }
        Spacer(Modifier.height(16.dp))
    }
}
