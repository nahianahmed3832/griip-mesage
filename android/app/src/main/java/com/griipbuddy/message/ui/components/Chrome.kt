package com.griipbuddy.message.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.griipbuddy.message.ui.theme.Griip
import com.griipbuddy.message.ui.theme.Motion
import com.griipbuddy.message.ui.theme.Shapes
import com.griipbuddy.message.ui.theme.Space

/** Centred-title header: frosted back circle, title (+subtitle), contextual actions (reference). */
@Composable
fun GriipTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val c = Griip.colors
    Box(modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 6.dp).height(56.dp)) {
        Row(Modifier.align(Alignment.CenterStart), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) CircleIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onBack)
        }
        Column(Modifier.align(Alignment.Center).padding(horizontal = 104.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = Griip.type.title, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            if (subtitle != null) Text(subtitle, style = Griip.type.caption, color = c.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(Modifier.align(Alignment.CenterEnd), verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

/** Large left-aligned header for tab roots (Chats, Workspace, Profile). */
@Composable
fun LargeHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val c = Griip.colors
    Row(
        modifier.fillMaxWidth().statusBarsPadding().padding(start = Space.gutter, end = 12.dp, top = 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            if (subtitle != null) Text(subtitle, style = Griip.type.bodySmall, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(title, style = Griip.type.headline.copy(fontWeight = FontWeight.ExtraBold), color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(verticalAlignment = Alignment.CenterVertically, content = trailing)
    }
}

data class NavItem(val route: String, val label: String, val icon: ImageVector, val selectedIcon: ImageVector, val badge: Int = 0)

/** Floating glass bottom navigation with four destinations (reference). */
@Composable
fun GlassBottomBar(items: List<NavItem>, selectedRoute: String?, onSelect: (NavItem) -> Unit, modifier: Modifier = Modifier) {
    val c = Griip.colors
    Box(modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(
            Modifier.fillMaxWidth().height(68.dp)
                .shadow(18.dp, Shapes.hero, ambientColor = c.shadow, spotColor = c.shadow)
                .clip(Shapes.hero)
                .background(c.glassStrong)
                .border(1.dp, c.glassBorder, Shapes.hero)
                .padding(horizontal = 6.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEach { item ->
                val sel = item.route == selectedRoute
                val fg by animateColorAsState(if (sel) c.primary else c.textTertiary, Motion.quick(), label = "nav")
                Column(
                    Modifier.weight(1f).clip(Shapes.tile).pressable({ onSelect(item) }, role = Role.Tab).semantics { selected = sel; contentDescription = item.label }.padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box {
                        Icon(if (sel) item.selectedIcon else item.icon, null, tint = fg, modifier = Modifier.size(24.dp))
                        if (item.badge > 0) {
                            CountBadge(item.badge, Modifier.align(Alignment.TopEnd).padding(start = 18.dp).height(16.dp))
                        }
                    }
                    Spacer(Modifier.height(3.dp))
                    Text(item.label, style = Griip.type.caption.copy(fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Medium), color = fg, maxLines = 1)
                }
            }
        }
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
) {
    val c = Griip.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.glassStrong.copy(alpha = 1f),
        shape = Shapes.hero,
        title = { Text(title, style = Griip.type.title, color = c.textPrimary) },
        text = { Text(text, style = Griip.type.bodySmall, color = c.textSecondary) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(confirmLabel, style = Griip.type.button, color = if (destructive) c.danger else c.primaryDeep) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", style = Griip.type.button, color = c.textSecondary) } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlassSheet(
    onDismiss: () -> Unit,
    title: String? = null,
    state: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = Griip.colors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        shape = Shapes.sheet,
        containerColor = if (c.isDark) Color(0xFF16272A) else Color(0xFFFAFDFC),
        scrimColor = c.scrim,
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
        dragHandle = {
            Box(Modifier.padding(top = 10.dp, bottom = 6.dp).width(40.dp).height(5.dp).clip(Shapes.pill).background(c.textTertiary.copy(alpha = 0.4f)))
        },
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = Space.gutter).padding(bottom = 16.dp)) {
            if (title != null) {
                Text(title, style = Griip.type.title, color = c.textPrimary, modifier = Modifier.padding(bottom = 12.dp))
            }
            content()
        }
    }
}
