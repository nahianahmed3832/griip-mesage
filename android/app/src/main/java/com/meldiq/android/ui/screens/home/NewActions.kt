package com.meldiq.android.ui.screens.home

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.GroupAdd
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.PostAdd
import androidx.compose.material.icons.outlined.Workspaces
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.meldiq.android.R
import com.meldiq.android.data.api.OrgDetailRes
import com.meldiq.android.ui.components.GlassSheet
import com.meldiq.android.ui.components.MenuRow
import com.meldiq.android.ui.theme.Griip

/** What Home's New button can start, given the selected workspace's permissions (unit-tested in `NewActionsTest`). */
enum class NewAction { MESSAGE, GROUP, CHANNEL, INVITE, WORKSPACE }

object NewActionRules {
    /**
     * Direct messages and groups always; a channel and an invitation only where the server says you may in the selected
     * workspace ([detail] null: no workspace, or not loaded yet); joining or creating a workspace always.
     */
    fun available(detail: OrgDetailRes?): List<NewAction> = buildList {
        add(NewAction.MESSAGE)
        add(NewAction.GROUP)
        if (detail?.permissions?.canCreateChannels == true) add(NewAction.CHANNEL)
        if (detail?.permissions?.canInvite == true) add(NewAction.INVITE)
        add(NewAction.WORKSPACE)
    }
}

/**
 * Home's contextual New menu (Slack's "+", adapted): only actions that exist and that you may take in the selected
 * workspace. Each opens the existing flow.
 */
@Composable
fun NewActionsSheet(detail: OrgDetailRes?, onDismiss: () -> Unit, onPick: (NewAction) -> Unit) {
    val c = Griip.colors
    val orgName = detail?.org?.name.orEmpty()
    GlassSheet(onDismiss, title = stringResource(R.string.new_title)) {
        NewActionRules.available(detail).forEach { action ->
            when (action) {
                NewAction.MESSAGE -> MenuRow(
                    Icons.Outlined.ChatBubbleOutline, stringResource(R.string.new_message), subtitle = stringResource(R.string.new_message_sub),
                    onClick = { onPick(action) },
                )
                NewAction.GROUP -> MenuRow(
                    Icons.Outlined.Groups, stringResource(R.string.new_group), subtitle = stringResource(R.string.new_group_sub),
                    tint = c.lavender, tintBg = c.lavenderSoft, onClick = { onPick(action) },
                )
                NewAction.CHANNEL -> MenuRow(
                    Icons.Outlined.PostAdd, stringResource(R.string.new_channel), subtitle = stringResource(R.string.new_channel_sub, orgName),
                    onClick = { onPick(action) },
                )
                NewAction.INVITE -> MenuRow(
                    Icons.Outlined.GroupAdd, stringResource(R.string.new_invite), subtitle = stringResource(R.string.new_invite_sub, orgName),
                    tint = c.peach, tintBg = c.peachSoft, onClick = { onPick(action) },
                )
                NewAction.WORKSPACE -> MenuRow(
                    Icons.Outlined.Workspaces, stringResource(R.string.new_workspace), tint = c.textSecondary, tintBg = c.surfaceVariant,
                    onClick = { onPick(action) },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}
