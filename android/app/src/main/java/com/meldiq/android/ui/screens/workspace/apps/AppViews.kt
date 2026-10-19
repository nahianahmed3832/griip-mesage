package com.meldiq.android.ui.screens.workspace.apps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.DesignServices
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderShared
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Mail
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.SupportAgent
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.Webhook
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.meldiq.android.R
import com.meldiq.android.data.api.CapabilityDto
import com.meldiq.android.ui.components.IconTile
import com.meldiq.android.ui.components.TagPill
import com.meldiq.android.ui.theme.Griip

/**
 * Small pieces shared by the app screens. Icons are Meldiq's own glyphs chosen by the server's icon name, never a
 * provider's logo (no brand assets are bundled; docs/INTEGRATIONS.md › Branding).
 */
fun appIcon(name: String): ImageVector = when (name) {
    "cloud_folder" -> Icons.Outlined.FolderShared
    "cloud_files" -> Icons.Outlined.Cloud
    "calendar" -> Icons.Outlined.CalendarMonth
    "sites" -> Icons.Outlined.Language
    "code" -> Icons.Outlined.Code
    "tasks" -> Icons.Outlined.TaskAlt
    "notes" -> Icons.Outlined.EditNote
    "mail" -> Icons.Outlined.Mail
    "video" -> Icons.Outlined.Videocam
    "bug" -> Icons.Outlined.BugReport
    "people" -> Icons.Outlined.People
    "support" -> Icons.Outlined.SupportAgent
    "science" -> Icons.Outlined.Science
    "docs" -> Icons.Outlined.Description
    "design" -> Icons.Outlined.DesignServices
    "folder" -> Icons.Outlined.Folder
    "webhook" -> Icons.Outlined.Webhook
    else -> Icons.Outlined.Apps
}

/** The app's tile: Meldiq's glyph on a soft tint. Planned apps are muted. */
@Composable
fun AppTile(icon: String, state: String, size: androidx.compose.ui.unit.Dp = 48.dp) {
    val c = Griip.colors
    val (fg, bg) = when (state) {
        "connected" -> c.success to c.successSoft
        "coming_soon", "needs_configuration", "blocked" -> c.textSecondary to c.surfaceVariant
        else -> c.primary to c.primarySoft
    }
    IconTile(appIcon(icon), fg, bg, size = size)
}

@Composable
fun stateLabel(state: String): String = when (state) {
    "coming_soon" -> stringResource(R.string.app_state_coming_soon)
    "needs_configuration" -> stringResource(R.string.app_state_needs_configuration)
    "blocked" -> stringResource(R.string.app_state_blocked)
    "approval_required" -> stringResource(R.string.app_state_approval_required)
    "disabled" -> stringResource(R.string.app_state_disabled)
    "installed" -> stringResource(R.string.app_state_installed)
    "connected" -> stringResource(R.string.app_state_connected)
    "error" -> stringResource(R.string.app_state_error)
    else -> stringResource(R.string.app_state_available)
}

/** The state as a pill: colour only where it means something (connected, needs attention, waiting). */
@Composable
fun StatePill(state: String) {
    val c = Griip.colors
    val (fg, bg) = when (state) {
        "connected" -> c.success to c.successSoft
        "error" -> c.danger to c.dangerSoft
        "approval_required" -> c.warning to c.warningSoft
        "installed" -> c.primaryDeep to c.primarySoft
        else -> c.textSecondary to c.surfaceVariant
    }
    TagPill(stateLabel(state), fg, bg)
}

@Composable
fun categoryLabel(category: String?): String = when (category) {
    "files" -> stringResource(R.string.apps_cat_files)
    "docs" -> stringResource(R.string.apps_cat_docs)
    "calendar" -> stringResource(R.string.apps_cat_calendar)
    "communication" -> stringResource(R.string.apps_cat_communication)
    "projects" -> stringResource(R.string.apps_cat_projects)
    "engineering" -> stringResource(R.string.apps_cat_engineering)
    "crm" -> stringResource(R.string.apps_cat_crm)
    "automation" -> stringResource(R.string.apps_cat_automation)
    else -> stringResource(R.string.apps_all)
}

@Composable
fun actionLabel(action: String): String = when (action) {
    "install" -> stringResource(R.string.app_install)
    "request" -> stringResource(R.string.app_request)
    "approve" -> stringResource(R.string.app_approve)
    "decline" -> stringResource(R.string.app_decline)
    "connect" -> stringResource(R.string.app_connect)
    "reconnect" -> stringResource(R.string.app_reconnect)
    "configure" -> stringResource(R.string.app_configure)
    "disconnect" -> stringResource(R.string.app_disconnect)
    "uninstall" -> stringResource(R.string.app_uninstall)
    "link_workspace" -> stringResource(R.string.app_link)
    else -> action
}

@Composable
fun kindLabel(kind: String): String = when (kind) {
    "write" -> stringResource(R.string.app_kind_write)
    "notify" -> stringResource(R.string.app_kind_notify)
    else -> stringResource(R.string.app_kind_read)
}

/** One capability: what it does, whose account it uses, and whether this workspace allows it. */
@Composable
fun CapabilityRow(cap: CapabilityDto, showGrant: Boolean, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    val c = Griip.colors
    Row(modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.Top) {
        Icon(
            if (!showGrant || cap.granted) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked, null,
            tint = if (!showGrant || cap.granted) c.primary else c.textTertiary, modifier = Modifier.padding(top = 2.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(cap.label, style = Griip.type.titleSmall, color = c.textPrimary)
            if (cap.description.isNotBlank()) Text(cap.description, style = Griip.type.bodySmall, color = c.textSecondary)
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TagPill(kindLabel(cap.kind), if (cap.kind == "write") c.warning else c.textSecondary, if (cap.kind == "write") c.warningSoft else c.surfaceVariant)
                TagPill(
                    stringResource(if (cap.accountType == "workspace") R.string.app_account_workspace else R.string.app_account_member),
                    c.textSecondary, c.surfaceVariant,
                )
                if (showGrant) TagPill(
                    stringResource(if (cap.granted) R.string.app_granted else R.string.app_not_granted),
                    if (cap.granted) c.success else c.textTertiary, if (cap.granted) c.successSoft else Color.Transparent,
                )
            }
            if (cap.scopes.isNotEmpty()) Text(cap.scopes.joinToString(" · "), style = Griip.type.caption, color = c.textTertiary, modifier = Modifier.padding(top = 4.dp))
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
    }
}

/** One honest step of an app's setup: done only when the server says it is. */
@Composable
fun SetupStep(done: Boolean, title: String, detail: String? = null) {
    val c = Griip.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (done) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked, null, tint = if (done) c.success else c.textTertiary)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = Griip.type.bodyMedium, color = if (done) c.textPrimary else c.textSecondary)
            if (detail != null) Text(detail, style = Griip.type.caption, color = c.textSecondary)
        }
    }
}
