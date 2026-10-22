package com.meldiq.android.ui.screens.workspace

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.meldiq.android.R
import com.meldiq.android.data.api.GuestPolicyReq
import com.meldiq.android.data.api.OrgDetailRes
import com.meldiq.android.data.api.UserDto
import com.meldiq.android.ui.LocalContainer
import com.meldiq.android.ui.TimeFmt
import com.meldiq.android.ui.components.ChipRow
import com.meldiq.android.ui.components.GlassSheet
import com.meldiq.android.ui.components.GriipSwitch
import com.meldiq.android.ui.components.MenuRow
import com.meldiq.android.ui.components.PillChip
import com.meldiq.android.ui.components.SectionHeader
import com.meldiq.android.ui.friendly
import com.meldiq.android.ui.theme.Griip
import kotlinx.coroutines.launch

// Round 7 slice C5 (docs/WORKSPACE_C5.md): a guest's end date, the workspace's guest policy, workspace templates.

private const val DAY_MS = 86_400_000L

/** In the member sheet, for a guest: when their membership ends (people who can change member access set it). */
@Composable
internal fun GuestExpirySection(d: OrgDetailRes, u: UserDto, onChanged: () -> Unit, onError: (String) -> Unit) {
    val container = LocalContainer.current
    val c = Griip.colors
    val scope = rememberCoroutineScope()
    var expiresAt by remember { mutableStateOf(u.expiresAt) }
    val canSet = "members.roles" in d.permissions.effective || d.permissions.canManageOrg
    SectionHeader(stringResource(R.string.guest_end_title), Modifier.padding(top = 14.dp, bottom = 6.dp))
    Text(
        expiresAt?.let { stringResource(R.string.guest_until, TimeFmt.dayHeader(it)) } ?: stringResource(R.string.guest_no_end),
        style = Griip.type.body, color = if (expiresAt != null) c.warning else c.textSecondary,
    )
    if (canSet) ChipRow(Modifier.padding(horizontal = 0.dp)) {
        listOf<Int?>(null, 7, 30, 90).forEach { days ->
            val label = days?.let { stringResource(R.string.guest_days_from_now, it) } ?: stringResource(R.string.guest_no_end)
            PillChip(label, false, {
                val target = days?.let { System.currentTimeMillis() + it * DAY_MS }
                scope.launch {
                    runCatching { container.orgs.setGuestExpiry(d.org.id, u.id, target) }
                        .onSuccess { expiresAt = target; onChanged() }.onFailure { onError(it.friendly()) }
                }
            })
        }
    }
}

/** Workspace settings: whether guests may be invited, and the longest a guest may stay. */
@Composable
internal fun GuestPolicyCard(orgId: String, allowed: Boolean, maxDays: Int?, onChanged: () -> Unit) {
    val container = LocalContainer.current
    val c = Griip.colors
    val scope = rememberCoroutineScope()
    var on by remember(allowed) { mutableStateOf(allowed) }
    var max by remember(maxDays) { mutableStateOf(maxDays) }
    var error by remember { mutableStateOf<String?>(null) }
    fun save(newOn: Boolean, newMax: Int?) = scope.launch {
        runCatching { container.api.put<GuestPolicyReq, kotlinx.serialization.json.JsonObject>("/v1/orgs/$orgId/guest-policy", GuestPolicyReq(newOn, newMax)) }
            .onSuccess { on = newOn; max = newMax; error = null; onChanged() }.onFailure { error = it.friendly() }
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.guest_policy_allow), style = Griip.type.body, color = c.textPrimary)
                Text(stringResource(R.string.guest_policy_allow_sub), style = Griip.type.caption, color = c.textSecondary)
            }
            GriipSwitch(on, { save(it, max) })
        }
        if (on) {
            Text(stringResource(R.string.guest_policy_max), style = Griip.type.label, color = c.textSecondary, modifier = Modifier.padding(top = 8.dp))
            ChipRow(Modifier.padding(horizontal = 0.dp)) {
                listOf<Int?>(null, 30, 90, 180).forEach { days ->
                    PillChip(days?.let { stringResource(R.string.guest_days, it) } ?: stringResource(R.string.guest_no_limit), max == days, { save(on, days) })
                }
            }
        }
        error?.let { Text(it, style = Griip.type.caption, color = c.danger) }
    }
}
