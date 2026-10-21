package com.meldiq.android.ui.screens.workspace

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.PostAdd
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Tag
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
import com.meldiq.android.data.api.CategoryDto
import com.meldiq.android.data.api.ConversationDto
import com.meldiq.android.ui.LocalContainer
import com.meldiq.android.ui.components.BannerKind
import com.meldiq.android.ui.components.ConfirmDialog
import com.meldiq.android.ui.components.GlassCard
import com.meldiq.android.ui.components.GlassSheet
import com.meldiq.android.ui.components.GriipTextField
import com.meldiq.android.ui.components.MenuRow
import com.meldiq.android.ui.components.PillButton
import com.meldiq.android.ui.components.StatusBanner
import com.meldiq.android.ui.friendly
import com.meldiq.android.ui.screens.chats.channelIcon
import com.meldiq.android.ui.theme.Griip
import kotlinx.coroutines.launch

/** A new category ([category] null) or a renamed one. */
@Composable
fun CategoryNameSheet(orgId: String, category: CategoryDto?, onDismiss: () -> Unit, onDone: () -> Unit) {
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(category?.name.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    GlassSheet(onDismiss, title = stringResource(if (category == null) R.string.cm_new_category else R.string.cm_rename)) {
        GriipTextField(name, { name = it.take(60) }, stringResource(R.string.cm_category_name), placeholder = "Talk")
        error?.let { Spacer(Modifier.height(10.dp)); StatusBanner(it, BannerKind.Error) }
        Spacer(Modifier.height(16.dp))
        PillButton(stringResource(if (category == null) R.string.cm_create else R.string.cm_save), {
            busy = true
            scope.launch {
                runCatching { if (category == null) container.orgs.createCategory(orgId, name) else container.orgs.renameCategory(category.id, name) }
                    .onSuccess { onDismiss(); onDone() }.onFailure { error = it.friendly() }
                busy = false
            }
        }, Modifier.fillMaxWidth().padding(bottom = 8.dp), enabled = name.isNotBlank(), loading = busy, height = 50.dp)
    }
}

/** What a manager can do with a category: add a channel, rename, permissions, reorder, delete. */
@Composable
fun CategoryActionsSheet(
    category: CategoryDto,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    canReorder: Boolean,
    onDismiss: () -> Unit,
    onNewChannel: (() -> Unit)?,
    onRename: () -> Unit,
    onPermissions: () -> Unit,
    onMove: (Int) -> Unit,
    onDelete: (() -> Unit)?,
) {
    val c = Griip.colors
    var confirm by remember { mutableStateOf(false) }
    GlassSheet(onDismiss, title = category.name) {
        GlassCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(6.dp)) {
            onNewChannel?.let { MenuRow(Icons.Outlined.PostAdd, stringResource(R.string.cm_new_channel), onClick = { onDismiss(); it() }) }
            MenuRow(Icons.Outlined.Edit, stringResource(R.string.cm_rename), onClick = { onDismiss(); onRename() })
            MenuRow(Icons.Outlined.Security, stringResource(R.string.cm_permissions), tint = c.lavender, tintBg = c.lavenderSoft, onClick = { onDismiss(); onPermissions() })
            if (canReorder && canMoveUp) MenuRow(Icons.Outlined.ArrowUpward, stringResource(R.string.cm_move_up), tint = c.textSecondary, tintBg = c.surfaceVariant, onClick = { onMove(-1); onDismiss() })
            if (canReorder && canMoveDown) MenuRow(Icons.Outlined.ArrowDownward, stringResource(R.string.cm_move_down), tint = c.textSecondary, tintBg = c.surfaceVariant, onClick = { onMove(1); onDismiss() })
            onDelete?.let { MenuRow(Icons.Outlined.DeleteOutline, stringResource(R.string.cm_delete), tint = c.danger, tintBg = c.dangerSoft, onClick = { confirm = true }) }
        }
        Spacer(Modifier.height(8.dp))
    }
    if (confirm) ConfirmDialog(
        stringResource(R.string.cm_delete_category_title, category.name), stringResource(R.string.cm_delete_category_body),
        stringResource(R.string.cm_delete), onConfirm = { confirm = false; onDismiss(); onDelete?.invoke() }, onDismiss = { confirm = false }, destructive = true,
    )
}

/** Arranging one channel: its order in the category, its category, its permissions. */
@Composable
fun ChannelArrangeSheet(
    channel: ConversationDto,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    canArrange: Boolean,
    onDismiss: () -> Unit,
    onMove: (Int) -> Unit,
    onMoveTo: () -> Unit,
    onPermissions: () -> Unit,
) {
    val c = Griip.colors
    GlassSheet(onDismiss, title = channel.name ?: "") {
        GlassCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(6.dp)) {
            if (canArrange && canMoveUp) MenuRow(Icons.Outlined.ArrowUpward, stringResource(R.string.cm_move_up), tint = c.textSecondary, tintBg = c.surfaceVariant, onClick = { onMove(-1); onDismiss() })
            if (canArrange && canMoveDown) MenuRow(Icons.Outlined.ArrowDownward, stringResource(R.string.cm_move_down), tint = c.textSecondary, tintBg = c.surfaceVariant, onClick = { onMove(1); onDismiss() })
            if (canArrange) MenuRow(Icons.Outlined.DriveFileMove, stringResource(R.string.cm_move_to), onClick = { onDismiss(); onMoveTo() })
            MenuRow(Icons.Outlined.Security, stringResource(R.string.cm_permissions), tint = c.lavender, tintBg = c.lavenderSoft, onClick = { onDismiss(); onPermissions() })
        }
        Spacer(Modifier.height(8.dp))
    }
}

/** Picks the category a channel moves to (it takes that category's permissions). */
@Composable
fun MoveToCategorySheet(channel: ConversationDto, categories: List<CategoryDto>, onDismiss: () -> Unit, onPick: (String?) -> Unit) =
    MoveToCategorySheet(channel.categoryId, categories, onDismiss, onPick)

/** Picks the category a channel in [currentCategoryId] moves to (it takes that category's permissions). */
@Composable
fun MoveToCategorySheet(currentCategoryId: String?, categories: List<CategoryDto>, onDismiss: () -> Unit, onPick: (String?) -> Unit) {
    val c = Griip.colors
    GlassSheet(onDismiss, title = stringResource(R.string.cm_move_to)) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            GlassCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(6.dp)) {
                if (currentCategoryId != null) MenuRow(Icons.Outlined.Tag, stringResource(R.string.cm_no_category), tint = c.textSecondary, tintBg = c.surfaceVariant, onClick = { onDismiss(); onPick(null) })
                categories.filter { it.id != currentCategoryId }.forEach { cat ->
                    MenuRow(Icons.Outlined.FolderOpen, cat.name, tint = c.peach, tintBg = c.peachSoft, onClick = { onDismiss(); onPick(cat.id) })
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** The icon of a channel row on the community screen. */
fun channelRowIcon(ch: ConversationDto) = channelIcon(ch.kind, ch.voice, ch.format)
