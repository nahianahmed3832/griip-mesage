package com.meldiq.android.ui.screens.moderation

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.meldiq.android.R
import com.meldiq.android.data.api.ReportContentDto
import com.meldiq.android.data.api.ReportReq
import com.meldiq.android.data.model.ReportRules
import com.meldiq.android.ui.LocalContainer
import com.meldiq.android.ui.components.BannerKind
import com.meldiq.android.ui.components.ButtonTone
import com.meldiq.android.ui.components.ConfirmDialog
import com.meldiq.android.ui.components.GlassSheet
import com.meldiq.android.ui.components.GriipTextField
import com.meldiq.android.ui.components.MenuRow
import com.meldiq.android.ui.components.PillButton
import com.meldiq.android.ui.components.StatusBanner
import com.meldiq.android.ui.friendly
import com.meldiq.android.ui.theme.Griip
import com.meldiq.android.ui.theme.Shapes
import kotlinx.coroutines.launch

/**
 * Who or what is being reported. A message report carries the conversation and message, and the [content] the phone
 * shows on the review step; a person report from a community's member list carries [orgId].
 */
data class ReportTarget(
    val userId: String,
    val name: String,
    val conversationId: String? = null,
    val conversationType: String? = null,
    val messageId: String? = null,
    val orgId: String? = null,
    /** The community whose moderators would read it, for the review step. */
    val communityName: String? = null,
    val content: ReportContentDto? = null,
)

@Composable
fun reasonLabel(reason: String): String = stringResource(
    when (reason) {
        "spam" -> R.string.rp_reason_spam
        "harassment" -> R.string.rp_reason_harassment
        "harmful" -> R.string.rp_reason_harmful
        "private_info" -> R.string.rp_reason_private_info
        "illegal" -> R.string.rp_reason_illegal
        else -> R.string.rp_reason_other
    },
)

/**
 * Report a message or a person (batch B4, Discord reference CHAT-MSG-003 §6.1, adapted): pick a reason, then a review
 * step that says who will read it and exactly what is shared, an optional note, and Send report. Nothing leaves the
 * phone before Send. "Just don't want to see them?" offers Block instead of a report.
 */
@Composable
fun ReportSheet(target: ReportTarget, onDismiss: () -> Unit) {
    val container = LocalContainer.current
    val c = Griip.colors
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var reason by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmBlock by remember { mutableStateOf(false) }
    // The community whose moderators would read it, by name (from this phone's list of communities).
    var community by remember { mutableStateOf(target.communityName) }
    // A person reported from their profile: the reporter picks who reads it (their communities, or Meldiq's team); the
    // server sends it to Meldiq's team when the person isn't in the chosen community.
    var chosenOrg by remember { mutableStateOf(target.orgId) }
    var myOrgs by remember { mutableStateOf<List<com.meldiq.android.data.api.OrgDto>>(emptyList()) }
    androidx.compose.runtime.LaunchedEffect(target.orgId) {
        val orgs = container.orgs.cachedOrgs().orEmpty()
        myOrgs = orgs
        if (community == null && target.orgId != null) community = orgs.firstOrNull { it.id == target.orgId }?.name
    }
    val choosing = target.messageId == null && target.orgId == null
    val title = if (target.messageId != null) stringResource(R.string.rp_title_message) else stringResource(R.string.rp_title_person, target.name)
    GlassSheet(onDismiss, title = title) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            val picked = reason
            if (picked == null) {
                Text(stringResource(R.string.rp_pick), style = Griip.type.label, color = c.textSecondary, modifier = Modifier.padding(bottom = 6.dp).semantics { heading() })
                ReportRules.REASONS.forEach { r ->
                    MenuRow(
                        Icons.Outlined.Flag, reasonLabel(r), subtitle = if (r == "illegal") stringResource(R.string.rp_illegal_sub) else null,
                        onClick = { reason = r }, trailing = { androidx.compose.material3.Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = c.textTertiary) },
                    )
                }
                Spacer(Modifier.height(6.dp))
                MenuRow(Icons.Outlined.Block, stringResource(R.string.rp_block_instead, target.name), onClick = { confirmBlock = true }, trailing = null)
            } else {
                Text(stringResource(R.string.rp_review), style = Griip.type.title, color = c.textPrimary, modifier = Modifier.semantics { heading() })
                Spacer(Modifier.height(8.dp))
                Text(reasonLabel(picked), style = Griip.type.bodyMedium, color = c.textPrimary)
                if (choosing && picked != "illegal" && myOrgs.isNotEmpty()) {
                    Text(stringResource(R.string.rp_send_to), style = Griip.type.label, color = c.textSecondary, modifier = Modifier.padding(top = 6.dp))
                    com.meldiq.android.ui.components.ChipRow(Modifier.padding(horizontal = 0.dp)) {
                        com.meldiq.android.ui.components.PillChip(stringResource(R.string.rp_meldiq_team), chosenOrg == null, { chosenOrg = null; community = null })
                        myOrgs.forEach { o -> com.meldiq.android.ui.components.PillChip(o.name, chosenOrg == o.id, { chosenOrg = o.id; community = o.name }) }
                    }
                }
                val toCommunity = ReportRules.goesToCommunity(picked, target.conversationType, chosenOrg != null) && community != null
                Text(
                    if (toCommunity) stringResource(R.string.rp_to_community, community.orEmpty()) else stringResource(R.string.rp_to_meldiq),
                    style = Griip.type.bodySmall, color = c.textSecondary,
                )
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.rp_shared), style = Griip.type.label, color = c.textSecondary)
                target.content?.text?.let { quoted ->
                    Text(
                        quoted, style = Griip.type.bodySmall, color = c.textPrimary, maxLines = 6,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(Shapes.small).background(c.surfaceVariant).padding(10.dp),
                    )
                }
                Text(
                    if (target.messageId != null) stringResource(R.string.rp_shared_message, ReportRules.CONTEXT) else stringResource(R.string.rp_shared_person),
                    style = Griip.type.caption, color = c.textSecondary,
                )
                Spacer(Modifier.height(10.dp))
                GriipTextField(note, { note = it.take(ReportRules.NOTE_MAX) }, stringResource(R.string.rp_note), singleLine = false, minLines = 2)
                error?.let { Spacer(Modifier.height(8.dp)); StatusBanner(it, BannerKind.Error) }
                Spacer(Modifier.height(12.dp))
                PillButton(stringResource(R.string.rp_send), {
                    busy = true
                    error = null
                    scope.launch {
                        runCatching {
                            container.reports.send(
                                ReportReq(
                                    targetUserId = target.userId, reason = picked, conversationId = target.conversationId, messageId = target.messageId,
                                    orgId = chosenOrg, note = note.trim().ifEmpty { null }, content = target.content,
                                ),
                            )
                        }.onSuccess { sent ->
                            val msg = when {
                                sent.repeated -> R.string.rp_already
                                sent.to == "community" -> R.string.rp_sent_community
                                else -> R.string.rp_sent_meldiq
                            }
                            Toast.makeText(ctx, ctx.getString(msg), Toast.LENGTH_SHORT).show()
                            onDismiss()
                        }.onFailure { error = it.friendly() }
                        busy = false
                    }
                }, Modifier.fillMaxWidth(), tone = ButtonTone.Danger, loading = busy, height = 50.dp)
                PillButton(stringResource(R.string.rp_back), { reason = null }, Modifier.fillMaxWidth().padding(top = 8.dp), tone = ButtonTone.Glass, showKnob = false, height = 46.dp)
            }
            Spacer(Modifier.height(8.dp))
        }
    }
    if (confirmBlock) ConfirmDialog(
        stringResource(R.string.rp_block_title, target.name), stringResource(R.string.rp_block_body), stringResource(R.string.rp_block),
        onConfirm = {
            confirmBlock = false
            scope.launch { runCatching { container.federation.block(target.userId) }.onSuccess { onDismiss() }.onFailure { error = it.friendly() } }
        },
        onDismiss = { confirmBlock = false }, destructive = true,
    )
}
