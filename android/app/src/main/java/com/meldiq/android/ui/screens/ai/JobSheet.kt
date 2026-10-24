package com.meldiq.android.ui.screens.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.StopCircle
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meldiq.android.R
import com.meldiq.android.data.ai.AgentRules
import com.meldiq.android.data.ai.AiJobDetail
import com.meldiq.android.data.ai.AiJobStates
import com.meldiq.android.data.ai.AiPermissions
import com.meldiq.android.ui.LocalContainer
import com.meldiq.android.ui.TimeFmt
import com.meldiq.android.ui.components.BannerKind
import com.meldiq.android.ui.components.ButtonTone
import com.meldiq.android.ui.components.GlassSheet
import com.meldiq.android.ui.components.GriipTextField
import com.meldiq.android.ui.components.PillButton
import com.meldiq.android.ui.components.ShimmerList
import com.meldiq.android.ui.components.SlimProgress
import com.meldiq.android.ui.components.StatusBanner
import com.meldiq.android.ui.components.pressable
import com.meldiq.android.ui.friendly
import com.meldiq.android.ui.theme.Griip
import kotlinx.coroutines.launch

/** One request to an agent, in a sheet (opened from the AI agents page, a channel post or a notification). */
@Composable
fun JobSheet(orgId: String, jobId: String, permissions: AiPermissions, onDismiss: () -> Unit) {
    GlassSheet(onDismiss, title = stringResource(R.string.aw_job_title)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) { JobView(orgId, jobId, permissions) }
    }
}

/**
 * Where a request is (as Meldiq Web's JobView): its real state, the progress the job reports while it works, its
 * delegated steps, its files and what it waits for. Who asked (and people who manage agents) can cancel, retry, answer
 * and ask for changes; approvals go to approvers, and to who asked for internal actions of their own request (the
 * server decides). It updates on the `ai.job` frame; closing it never cancels anything.
 */
@Composable
fun JobView(orgId: String, jobId: String, permissions: AiPermissions) {
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    val rev by container.aiSignals.jobs.collectAsStateWithLifecycle()
    val me = container.session.current?.userId
    var job by remember { mutableStateOf<AiJobDetail?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }
    var answer by remember { mutableStateOf("") }
    suspend fun load() = runCatching { container.ai.job(orgId, jobId) }.onSuccess { job = it; error = null }.onFailure { error = it.friendly() }
    LaunchedEffect(orgId, jobId, rev) { load() }
    fun act(block: suspend () -> Unit) {
        busy = true
        error = null
        scope.launch {
            runCatching { block() }.onFailure { error = it.friendly() }
            load()
            busy = false
        }
    }
    val c = Griip.colors
    val j = job
    if (j == null) {
        error?.let { StatusBanner(it, BannerKind.Error) } ?: ShimmerList(2)
        return
    }
    val steer = AgentRules.maySteer(j, me, permissions)
    val pending = AgentRules.pendingApproval(j)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(j.agent.displayName, style = Griip.type.titleSmall, color = c.textPrimary, modifier = Modifier.weight(1f))
            JobStatePill(j.state)
        }
        Text(
            listOfNotNull(
                stringResource(R.string.aw_job_asked, TimeFmt.relative(j.createdAt)),
                j.finishedAt?.let { f -> j.startedAt?.let { stringResource(R.string.aw_job_took, TimeFmt.duration((f - it) / 1000)) } },
                stringResource(R.string.aw_job_revision, j.revision).takeIf { j.revision > 0 },
            ).joinToString(" · "),
            style = Griip.type.caption, color = c.textSecondary,
        )
        if (AiJobStates.showsProgress(j.state)) SlimProgress(j.progress / 100f)
        j.note?.takeIf { it.isNotBlank() && j.state != AiJobStates.AWAITING_INPUT }?.let { Text(it, style = Griip.type.body, color = c.textSecondary) }
        if (j.sharedMessages > 0) Text(stringResource(R.string.aw_job_shared, j.sharedMessages), style = Griip.type.caption, color = c.textTertiary)
        j.error?.takeIf { j.state != AiJobStates.COMPLETED }?.let { StatusBanner(it.message.ifBlank { it.code }, BannerKind.Error) }
        error?.let { StatusBanner(it, BannerKind.Error) }
        if (j.steps.isNotEmpty()) {
            Text(stringResource(R.string.aw_job_steps), style = Griip.type.section, color = c.textSecondary)
            j.steps.forEach { s ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(s.agent.displayName, style = Griip.type.body, color = c.textPrimary, modifier = Modifier.weight(1f))
                    JobStatePill(s.state)
                }
            }
        }
        AgentRules.currentFiles(j.files).forEach { AiFileRow(orgId, it) }
        val older = AgentRules.replacedFiles(j.files)
        if (older.isNotEmpty()) {
            var showOlder by remember { mutableStateOf(false) }
            Text(
                stringResource(if (showOlder) R.string.aw_job_hide_older else R.string.aw_job_show_older, older.size), style = Griip.type.label, color = c.primary,
                modifier = Modifier.pressable({ showOlder = !showOlder }).padding(vertical = 2.dp),
            )
            if (showOlder) older.forEach { AiFileRow(orgId, it) }
        }
        if (j.state == AiJobStates.AWAITING_APPROVAL && pending != null) {
            StatusBanner(stringResource(R.string.aw_job_approval_needed, pending.summary), BannerKind.Warning)
            if (AgentRules.mayApprove(j, me, permissions)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillButton(stringResource(R.string.aw_job_approve), { act { container.ai.decide(orgId, pending.id, approve = true) } }, Modifier.weight(1f), enabled = !busy, leadingIcon = Icons.Outlined.Check, height = 44.dp)
                    PillButton(stringResource(R.string.aw_job_reject), { act { container.ai.decide(orgId, pending.id, approve = false) } }, Modifier.weight(1f), tone = ButtonTone.Glass, enabled = !busy, leadingIcon = Icons.Outlined.Close, height = 44.dp)
                }
                Text(stringResource(R.string.aw_job_approve_exact), style = Griip.type.caption, color = c.textTertiary)
            } else Text(stringResource(R.string.aw_job_approver_decides), style = Griip.type.caption, color = c.textTertiary)
            if (steer) {
                GriipTextField(note, { note = it.take(2000) }, stringResource(R.string.aw_job_changes), singleLine = false, minLines = 2)
                PillButton(stringResource(R.string.aw_job_request_changes), { act { container.ai.revise(orgId, j.id, note.trim()); note = "" } }, Modifier.fillMaxWidth(), tone = ButtonTone.Glass, enabled = !busy && note.isNotBlank(), height = 44.dp)
            }
        }
        if (j.state == AiJobStates.AWAITING_INPUT && steer) {
            StatusBanner(j.note?.ifBlank { null } ?: stringResource(R.string.aw_job_needs_answer), BannerKind.Info)
            GriipTextField(answer, { answer = it.take(4000) }, stringResource(R.string.aw_job_answer_label), singleLine = false, minLines = 2)
            PillButton(stringResource(R.string.aw_job_answer), { act { container.ai.answer(orgId, j.id, answer.trim()); answer = "" } }, Modifier.fillMaxWidth(), enabled = !busy && answer.isNotBlank(), height = 44.dp)
        }
        if (steer) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (AiJobStates.active(j.state)) PillButton(stringResource(R.string.aw_job_cancel), { act { container.ai.cancel(orgId, j.id) } }, tone = ButtonTone.Danger, enabled = !busy, leadingIcon = Icons.Outlined.StopCircle, height = 40.dp)
                if (AiJobStates.canRetry(j.state, j.parentJobId)) PillButton(stringResource(R.string.aw_job_retry), { act { container.ai.retry(orgId, j.id) } }, tone = ButtonTone.Glass, enabled = !busy, leadingIcon = Icons.Outlined.Refresh, height = 40.dp)
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}
