package com.meldiq.android.ui.screens.profile

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.GroupAdd
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.meldiq.android.R
import com.meldiq.android.data.api.OrgDto
import com.meldiq.android.ui.LocalContainer
import com.meldiq.android.ui.components.BannerKind
import com.meldiq.android.ui.components.MenuRow
import com.meldiq.android.ui.components.ShimmerList
import com.meldiq.android.ui.components.StatusBanner
import com.meldiq.android.ui.components.GlassSheet
import com.meldiq.android.ui.friendly
import com.meldiq.android.ui.scan.InviteCodes
import com.meldiq.android.ui.theme.Griip
import kotlinx.coroutines.launch

/**
 * Batch B4b (Discord reference INV-CTRL "Invite to Server" on a profile): pick one of your communities; Meldiq makes a
 * one-use invitation valid for [ProfileInvite.HOURS] hours and sends its link to the person in your DM with them. The
 * server checks that your role may invite; communities where you're a guest aren't offered.
 */
object ProfileInvite {
    const val USES = 1
    const val HOURS = 7 * 24

    /** The communities offered: not ones where this account is only a guest. */
    fun offered(orgs: List<OrgDto>): List<OrgDto> = orgs.filter { it.myRole != null && it.myRole != "GUEST" }.sortedBy { it.name.lowercase() }

    fun message(communityName: String, link: String) = "$communityName: $link"
}

@Composable
fun InviteToCommunitySheet(userId: String, name: String, onDismiss: () -> Unit, onSent: (conversationId: String) -> Unit) {
    val container = LocalContainer.current
    val c = Griip.colors
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var orgs by remember { mutableStateOf<List<OrgDto>?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        orgs = container.orgs.cachedOrgs()?.let(ProfileInvite::offered)
        runCatching { container.orgs.orgs() }.onSuccess { orgs = ProfileInvite.offered(it) }.onFailure { if (orgs == null) { error = it.friendly(); orgs = emptyList() } }
    }
    fun send(o: OrgDto) {
        if (busy != null) return
        busy = o.id
        scope.launch {
            runCatching {
                val invite = container.orgs.createInvite(o.id, "MEMBER", ProfileInvite.USES, ProfileInvite.HOURS)
                val code = invite.code ?: error("no code")
                val dm = container.conversations.openDm(userId)
                container.messages.sendText(dm.id, ProfileInvite.message(o.name, InviteCodes.link(code)))
                dm.id
            }.onSuccess { cid ->
                android.widget.Toast.makeText(ctx, ctx.getString(R.string.pi_sent, name), android.widget.Toast.LENGTH_SHORT).show()
                onSent(cid)
            }.onFailure { error = it.friendly() }
            busy = null
        }
    }
    GlassSheet(onDismiss, title = stringResource(R.string.pi_pick, name)) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(stringResource(R.string.pi_note), style = Griip.type.caption, color = c.textSecondary)
            Spacer(Modifier.height(8.dp))
            error?.let { StatusBanner(it, BannerKind.Error); Spacer(Modifier.height(8.dp)) }
            val list = orgs
            when {
                list == null -> ShimmerList(3)
                list.isEmpty() -> Text(stringResource(R.string.pi_none), style = Griip.type.body, color = c.textSecondary)
                else -> list.forEach { o ->
                    MenuRow(
                        Icons.Outlined.GroupAdd, o.name, subtitle = if (busy == o.id) stringResource(R.string.pi_sending) else null,
                        tint = c.peach, tintBg = c.peachSoft, onClick = { send(o) },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}
