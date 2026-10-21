package com.meldiq.android.ui.screens.moderation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.meldiq.android.R
import com.meldiq.android.data.model.SecurityRules
import com.meldiq.android.ui.LocalContainer
import com.meldiq.android.ui.components.BannerKind
import com.meldiq.android.ui.components.ButtonTone
import com.meldiq.android.ui.components.ChipRow
import com.meldiq.android.ui.components.GlassSheet
import com.meldiq.android.ui.components.PillButton
import com.meldiq.android.ui.components.PillChip
import com.meldiq.android.ui.components.StatusBanner
import com.meldiq.android.ui.friendly
import com.meldiq.android.ui.screens.workspace.TimeoutRules
import com.meldiq.android.ui.theme.Griip
import kotlinx.coroutines.launch

private val PAUSE_LABELS = listOf(R.string.sa_1h, R.string.sa_6h, R.string.sa_24h, R.string.sa_7d)

/**
 * Security actions (batch B4, Discord reference ADMIN-CTRL-007): pause joining, and new DMs between members, for 1 hour
 * to 7 days, or resume. A running pause says until when. The server checks who may.
 */
@Composable
fun SecuritySheet(orgId: String, invitesUntil: Long?, dmsUntil: Long?, onDismiss: () -> Unit, onChanged: () -> Unit) {
    val container = LocalContainer.current
    val c = Griip.colors
    val scope = rememberCoroutineScope()
    var invites by remember { mutableStateOf(invitesUntil) }
    var dms by remember { mutableStateOf(dmsUntil) }
    var error by remember { mutableStateOf<String?>(null) }
    fun apply(setInvites: Boolean, until: Long?) = scope.launch {
        runCatching { container.reports.security(orgId, if (setInvites) until else null, if (setInvites) null else until, setInvites, !setInvites) }
            .onSuccess { invites = it.invitesPausedUntil; dms = it.dmsPausedUntil; error = null; onChanged() }
            .onFailure { error = it.friendly() }
    }
    GlassSheet(onDismiss, title = stringResource(R.string.sa_title)) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            PauseSection(stringResource(R.string.sa_invites), stringResource(R.string.sa_invites_sub), invites, onPause = { apply(true, it) }, onResume = { apply(true, null) })
            Spacer(Modifier.height(16.dp))
            PauseSection(stringResource(R.string.sa_dms), stringResource(R.string.sa_dms_sub), dms, onPause = { apply(false, it) }, onResume = { apply(false, null) })
            error?.let { Spacer(Modifier.height(8.dp)); StatusBanner(it, BannerKind.Error) }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun PauseSection(title: String, subtitle: String, until: Long?, onPause: (Long) -> Unit, onResume: () -> Unit) {
    val c = Griip.colors
    Text(title, style = Griip.type.bodyMedium, color = c.textPrimary)
    Text(subtitle, style = Griip.type.caption, color = c.textSecondary)
    if (SecurityRules.running(until)) {
        Spacer(Modifier.height(8.dp))
        StatusBanner(stringResource(R.string.sa_paused_until, TimeoutRules.untilText(until ?: 0)), BannerKind.Warning)
        PillButton(stringResource(R.string.sa_resume), onResume, Modifier.padding(top = 8.dp), tone = ButtonTone.Glass, height = 40.dp, showKnob = false)
    } else {
        ChipRow(Modifier.padding(horizontal = 0.dp)) {
            SecurityRules.DURATIONS.zip(PAUSE_LABELS).forEach { (ms, label) -> PillChip(stringResource(label), false, { onPause(System.currentTimeMillis() + ms) }) }
        }
    }
}
