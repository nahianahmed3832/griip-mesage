package com.griipbuddy.orbit.ui.screens.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOff
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.griipbuddy.orbit.R
import com.griipbuddy.orbit.data.api.NavPrefsDto
import com.griipbuddy.orbit.data.db.ConversationEntity
import com.griipbuddy.orbit.data.model.NavOp
import com.griipbuddy.orbit.data.model.NavPlace
import com.griipbuddy.orbit.data.model.NavPrefsRules
import com.griipbuddy.orbit.data.repo.NavPreferences
import com.griipbuddy.orbit.ui.LocalContainer
import com.griipbuddy.orbit.ui.components.ConfirmDialog
import com.griipbuddy.orbit.ui.components.GlassCard
import com.griipbuddy.orbit.ui.components.GlassSheet
import com.griipbuddy.orbit.ui.components.GriipTextField
import com.griipbuddy.orbit.ui.components.MenuRow
import com.griipbuddy.orbit.ui.components.PillButton
import com.griipbuddy.orbit.ui.screens.chats.listTitle
import com.griipbuddy.orbit.ui.theme.Griip

/**
 * The person's custom sections on Home, below Starred (docs/WORKSPACE_ADMINISTRATION.md › Personal sections). Each one
 * opens and closes like a channel group, and "Edit" renames or deletes it. Kept for every device of the person.
 */
internal fun LazyListScope.customSectionsList(
    sections: List<CustomSection>,
    me: String?,
    onToggle: (CustomSection) -> Unit,
    onEdit: (CustomSection) -> Unit,
    onOpen: (ConversationEntity) -> Unit,
    onLongPress: (ConversationEntity) -> Unit,
) {
    sections.forEach { section ->
        item(key = "section-${section.workspaceId}-${section.id}") {
            HomeSectionHeader(
                section.name, action = stringResource(R.string.sec_edit), onAction = { onEdit(section) },
                expanded = !section.collapsed, onToggle = { onToggle(section) },
            )
        }
        if (section.collapsed) return@forEach
        if (section.items.isEmpty()) item(key = "section-empty-${section.id}") { QuietLine(stringResource(R.string.sec_empty)) }
        items(section.items, key = { "section-${section.id}-${it.id}" }) { conv ->
            val unread = conv.unreadCount > 0
            HomeListRow(
                listTitle(conv, me).removePrefix("#"),
                leading = { ChannelOrAvatar(conv, me) },
                onClick = { onOpen(conv) }, onLongClick = { onLongPress(conv) },
                subtitle = conv.scopeName?.takeIf { conv.type == "CHANNEL" }, bold = unread && !conv.muted, dim = conv.muted,
                indent = 8.dp,
                trailing = {
                    if (conv.mentionCount > 0) HomeCount(conv.mentionCount, accent = true, description = stringResource(R.string.home_mention_count, conv.mentionCount), prefix = "@")
                    else HomeCount(conv.unreadCount, accent = false, description = stringResource(R.string.home_unread_messages, conv.unreadCount))
                },
            )
        }
    }
}

/**
 * Where [conv] sits on Home: Starred, one of the person's sections in its workspace, or its default group; plus moving
 * it up or down within Starred or its section, and making a new section for it.
 */
@Composable
fun SectionPickerSheet(conv: ConversationEntity, onDismiss: () -> Unit) {
    val container = LocalContainer.current
    val c = Griip.colors
    val workspaceId = NavPreferences.workspaceOf(conv)
    val all by container.navPrefs.prefs.collectAsStateWithLifecycle()
    val prefs = all[workspaceId] ?: NavPrefsDto()
    val place = NavPrefsRules.placeOf(prefs, conv.id)
    var naming by rememberSaveable { mutableStateOf(false) }
    fun change(op: NavOp) = container.navPrefs.change(workspaceId, op)

    GlassSheet(onDismiss, title = stringResource(R.string.sec_move_title)) {
        GlassCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(6.dp)) {
            prefs.sections.forEach { s ->
                val here = place == NavPlace.Section(s.id)
                MenuRow(
                    Icons.Outlined.Folder, s.name, tint = c.peach, tintBg = c.peachSoft,
                    onClick = { change(NavOp.Place(conv.id, s.id)); onDismiss() },
                    trailing = if (here) ({ Icon(Icons.Rounded.Check, stringResource(R.string.sec_current), tint = c.primary) }) else null,
                )
            }
            MenuRow(
                Icons.Outlined.CreateNewFolder, stringResource(R.string.sec_new), subtitle = stringResource(R.string.sec_new_sub),
                onClick = { naming = true }, trailing = null,
            )
            if (place != NavPlace.Default) {
                MenuRow(
                    Icons.Outlined.FolderOff, stringResource(R.string.sec_remove), tint = c.textSecondary, tintBg = c.surfaceVariant,
                    onClick = { change(NavOp.Place(conv.id, null)); onDismiss() }, trailing = null,
                )
                MenuRow(Icons.Rounded.KeyboardArrowUp, stringResource(R.string.sec_move_up), onClick = { change(NavOp.Move(conv.id, -1)) }, trailing = null)
                MenuRow(Icons.Rounded.KeyboardArrowDown, stringResource(R.string.sec_move_down), onClick = { change(NavOp.Move(conv.id, 1)) }, trailing = null)
            }
        }
        Spacer(Modifier.height(8.dp))
    }
    if (naming) {
        SectionNameDialog(stringResource(R.string.sec_new), initial = "", onDismiss = { naming = false }) { name ->
            val id = NavPrefsRules.newSectionId()
            change(NavOp.AddSection(id, name))
            change(NavOp.Place(conv.id, id))
            naming = false
            onDismiss()
        }
    }
}

/** Rename or delete a custom section; deleting puts its conversations back in their usual groups. */
@Composable
fun SectionEditSheet(section: CustomSection, onDismiss: () -> Unit) {
    val container = LocalContainer.current
    val c = Griip.colors
    var renaming by rememberSaveable { mutableStateOf(false) }
    var deleting by rememberSaveable { mutableStateOf(false) }
    GlassSheet(onDismiss, title = section.name) {
        GlassCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(6.dp)) {
            MenuRow(Icons.Outlined.Edit, stringResource(R.string.sec_rename), onClick = { renaming = true }, trailing = null)
            MenuRow(
                Icons.Outlined.Delete, stringResource(R.string.sec_delete), subtitle = stringResource(R.string.sec_delete_sub),
                tint = c.danger, tintBg = c.dangerSoft, onClick = { deleting = true }, trailing = null,
            )
        }
        Spacer(Modifier.height(8.dp))
    }
    if (renaming) {
        SectionNameDialog(stringResource(R.string.sec_rename), initial = section.name, onDismiss = { renaming = false }) { name ->
            container.navPrefs.change(section.workspaceId, NavOp.RenameSection(section.id, name))
            renaming = false
            onDismiss()
        }
    }
    if (deleting) {
        ConfirmDialog(
            stringResource(R.string.sec_delete_title, section.name), stringResource(R.string.sec_delete_sub), stringResource(R.string.sec_delete),
            onConfirm = {
                container.navPrefs.change(section.workspaceId, NavOp.DeleteSection(section.id))
                deleting = false
                onDismiss()
            },
            onDismiss = { deleting = false }, destructive = true,
        )
    }
}

/** A section name, 1–60 characters. */
@Composable
private fun SectionNameDialog(title: String, initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf(initial) }
    GlassSheet(onDismiss, title = title) {
        Column(Modifier.fillMaxWidth()) {
            GriipTextField(name, { name = it.take(NavPrefsRules.SECTION_NAME_MAX) }, stringResource(R.string.sec_name))
            Spacer(Modifier.height(12.dp))
            PillButton(
                stringResource(R.string.sec_save), { onSave(name.trim()) }, Modifier.fillMaxWidth(),
                enabled = name.isNotBlank(), height = 50.dp,
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}
