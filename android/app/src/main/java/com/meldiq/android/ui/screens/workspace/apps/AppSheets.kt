package com.meldiq.android.ui.screens.workspace.apps

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.meldiq.android.R
import com.meldiq.android.data.api.AppAccess
import com.meldiq.android.data.api.AppDetailRes
import com.meldiq.android.data.api.ConversationDto
import com.meldiq.android.data.api.CustomRoleDto
import com.meldiq.android.data.api.LinkTargetDto
import com.meldiq.android.data.api.LinkTargetsRes
import com.meldiq.android.data.repo.ServerFeatures
import com.meldiq.android.ui.components.BannerKind
import com.meldiq.android.ui.components.ButtonTone
import com.meldiq.android.ui.components.ChipRow
import com.meldiq.android.ui.components.GlassSheet
import com.meldiq.android.ui.components.GriipSwitch
import com.meldiq.android.ui.components.GriipTextField
import com.meldiq.android.ui.components.ListCard
import com.meldiq.android.ui.components.PillButton
import com.meldiq.android.ui.components.PillChip
import com.meldiq.android.ui.components.SectionHeader
import com.meldiq.android.ui.components.StatusBanner
import com.meldiq.android.ui.friendly
import com.meldiq.android.ui.theme.Griip

/**
 * Install or approve: the capabilities to allow, with the app's least-privilege defaults (or, when approving, what was
 * requested) already chosen. Admins can narrow or widen it; nothing broader is ever preselected.
 */
@Composable
fun InstallSheet(d: AppDetailRes, approving: Boolean, onDismiss: () -> Unit, onConfirm: (List<String>) -> Unit) {
    val c = Griip.colors
    val initial = remember(d) {
        val requested = d.install?.requestedCapabilities.orEmpty()
        (if (approving && requested.isNotEmpty()) requested else d.app.capabilities.filter { it.default }.map { it.key }).toSet()
    }
    var chosen by remember(d) { mutableStateOf(initial) }
    GlassSheet(onDismiss, title = stringResource(R.string.app_choose_caps)) {
        Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
            Text(stringResource(R.string.app_choose_caps_sub), style = Griip.type.bodySmall, color = c.textSecondary)
            d.app.capabilities.forEach { cap ->
                CapabilityRow(cap.copy(granted = cap.key in chosen), showGrant = false) {
                    GriipSwitch(cap.key in chosen, { on -> chosen = if (on) chosen + cap.key else chosen - cap.key })
                }
            }
            Spacer(Modifier.height(12.dp))
            PillButton(stringResource(if (approving) R.string.app_approve else R.string.app_install), { onConfirm(chosen.toList().sorted()) }, Modifier.fillMaxWidth(), enabled = chosen.isNotEmpty(), height = 50.dp)
        }
    }
}

/** Asking an admin for an app: what it would do (before anyone commits) and an optional reason. */
@Composable
fun RequestSheet(d: AppDetailRes, onDismiss: () -> Unit, onSend: (String) -> Unit) {
    val c = Griip.colors
    var note by remember { mutableStateOf("") }
    GlassSheet(onDismiss, title = stringResource(R.string.app_request) + " · " + d.app.name) {
        d.app.capabilities.filter { it.default }.forEach { cap -> CapabilityRow(cap, showGrant = false) }
        GriipTextField(note, { note = it.take(300) }, stringResource(R.string.app_request_note), singleLine = false, minLines = 2)
        Spacer(Modifier.height(12.dp))
        PillButton(stringResource(R.string.app_request), { onSend(note) }, Modifier.fillMaxWidth(), height = 50.dp)
    }
}

/**
 * Before the browser opens: what connecting allows (the capabilities this workspace granted that use the person's own
 * account), that the sign-in happens at the provider, and that Meldiq never sees the password.
 */
@Composable
fun ConnectSheet(d: AppDetailRes, onDismiss: () -> Unit, onContinue: () -> Unit) {
    val c = Griip.colors
    val caps = d.app.capabilities.filter { it.granted && it.accountType == "member" }
    GlassSheet(onDismiss, title = stringResource(R.string.app_connect_title, d.app.name)) {
        Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
            Text(stringResource(R.string.app_connect_explain, d.app.publisher.ifBlank { d.app.name }), style = Griip.type.bodyMedium, color = c.textSecondary)
            caps.forEach { cap -> CapabilityRow(cap, showGrant = false) }
            if (caps.isEmpty()) Text(stringResource(R.string.app_link_needs_own), style = Griip.type.bodySmall, color = c.textSecondary, modifier = Modifier.padding(vertical = 8.dp))
            Spacer(Modifier.height(12.dp))
            PillButton(stringResource(R.string.app_connect_continue), onContinue, Modifier.fillMaxWidth(), height = 50.dp)
        }
    }
}

/** An installed app's settings: granted capabilities, who may use it, and whether it's on. A workspace setting. */
@Composable
fun ConfigureSheet(vm: AppDetailViewModel, d: AppDetailRes, onDismiss: () -> Unit) {
    val c = Griip.colors
    val install = d.install ?: return
    var granted by remember(d) { mutableStateOf(install.grantedCapabilities.toSet()) }
    var mode by remember(d) { mutableStateOf(install.config.access.mode) }
    var roleIds by remember(d) { mutableStateOf(install.config.access.roleIds.toSet()) }
    var enabled by remember(d) { mutableStateOf(install.state == "installed") }
    var roles by remember { mutableStateOf<List<CustomRoleDto>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (vm.c.features.has(ServerFeatures.CUSTOM_ROLES) == true) runCatching { vm.c.roles.roles(vm.orgId) }.onSuccess { roles = it.roles }
    }
    val ctx = LocalContext.current
    GlassSheet(onDismiss, title = stringResource(R.string.app_configure) + " · " + d.app.name) {
        Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState())) {
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.app_enabled), style = Griip.type.bodyMedium, color = c.textPrimary, modifier = Modifier.weight(1f))
                GriipSwitch(enabled, { enabled = it })
            }
            SectionHeader(stringResource(R.string.app_capabilities), Modifier.padding(vertical = 6.dp))
            d.app.capabilities.forEach { cap ->
                CapabilityRow(cap.copy(granted = cap.key in granted), showGrant = false) {
                    GriipSwitch(cap.key in granted, { on -> granted = if (on) granted + cap.key else granted - cap.key })
                }
            }
            SectionHeader(stringResource(R.string.app_access), Modifier.padding(vertical = 6.dp))
            ChipRow {
                PillChip(stringResource(R.string.app_access_everyone), mode == "everyone", { mode = "everyone" })
                if (roles.isNotEmpty()) PillChip(stringResource(R.string.app_access_roles), mode == "roles", { mode = "roles" })
            }
            if (mode == "roles") ChipRow {
                roles.forEach { r -> PillChip(r.name, r.id in roleIds, { roleIds = if (r.id in roleIds) roleIds - r.id else roleIds + r.id }) }
            }
            error?.let { StatusBanner(it, BannerKind.Error, Modifier.padding(top = 8.dp)) }
            Spacer(Modifier.height(12.dp))
            PillButton(stringResource(R.string.app_save), {
                busy = true
                error = null
                vm.act({
                    runCatching {
                        vm.c.integrations.configure(
                            vm.orgId, vm.appId, install.rev, granted = granted.toList().sorted(),
                            access = AppAccess(mode, if (mode == "roles") roleIds.toList() else emptyList()),
                            enabled = enabled.takeIf { it != (install.state == "installed") },
                        )
                    }.onSuccess { onDismiss() }.onFailure { error = it.friendly(); busy = false }.getOrThrow()
                }, ctx.getString(R.string.app_saved))
            }, Modifier.fillMaxWidth(), enabled = granted.isNotEmpty() && (mode == "everyone" || roleIds.isNotEmpty()), loading = busy, height = 50.dp)
        }
    }
}

/**
 * Linking the workspace's shared connection: only targets the admin's own account can reach (the server checks again),
 * or a link to install the provider's app first.
 */
@Composable
fun LinkSheet(vm: AppDetailViewModel, d: AppDetailRes, onDismiss: () -> Unit, openUrl: (String) -> Unit) {
    val c = Griip.colors
    var res by remember { mutableStateOf<LinkTargetsRes?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { runCatching { vm.c.integrations.linkTargets(vm.orgId, vm.appId) }.onSuccess { res = it }.onFailure { error = it.friendly() } }
    GlassSheet(onDismiss, title = stringResource(R.string.app_link_title)) {
        error?.let { StatusBanner(it, BannerKind.Warning, Modifier.padding(bottom = 8.dp)) }
        val r = res
        if (r != null) {
            r.linkedTo?.let { Text(stringResource(R.string.app_linked_to, it.label), style = Griip.type.bodyMedium, color = c.textSecondary) }
            if (r.targets.isEmpty()) Text(stringResource(R.string.app_link_none), style = Griip.type.bodyMedium, color = c.textSecondary, modifier = Modifier.padding(vertical = 8.dp))
            r.targets.forEach { t: LinkTargetDto ->
                ListCard(t.label, selected = r.linkedTo?.id == t.id, onClick = {
                    vm.act({ vm.c.integrations.link(vm.orgId, vm.appId, t.id) })
                    onDismiss()
                })
            }
            r.installUrl?.let { url -> PillButton(stringResource(R.string.app_install_provider), { openUrl(url) }, Modifier.fillMaxWidth().padding(top = 8.dp), tone = ButtonTone.Glass, height = 44.dp) }
        }
        Spacer(Modifier.height(8.dp))
    }
}

/**
 * A new channel notification: a channel you're in, a resource the linked connection can see, and events (the most
 * common ones preselected). Private resources say plainly that the channel will see their updates.
 */
@Composable
fun SubscribeSheet(vm: AppDetailViewModel, d: AppDetailRes, onDismiss: () -> Unit) {
    val c = Griip.colors
    var channels by remember { mutableStateOf<List<ConversationDto>>(emptyList()) }
    var resources by remember { mutableStateOf<List<LinkTargetDto>>(emptyList()) }
    var channel by remember { mutableStateOf<String?>(null) }
    var resource by remember { mutableStateOf<LinkTargetDto?>(null) }
    val defaults = remember(d) { d.app.eventTypes.map { it.key }.filter { k -> DEFAULT_EVENTS.any { k.endsWith(it) } }.ifEmpty { d.app.eventTypes.take(1).map { it.key } }.toSet() }
    var events by remember(d) { mutableStateOf(defaults) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        runCatching { vm.c.orgs.channels(vm.orgId) }.onSuccess { list -> channels = list.filter { it.joined && !it.archived } }
        runCatching { vm.c.integrations.watchable(vm.orgId, vm.appId) }.onSuccess { resources = it.items }.onFailure { error = it.friendly() }
    }
    GlassSheet(onDismiss, title = stringResource(R.string.app_add_notification)) {
        Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState())) {
            SectionHeader(stringResource(R.string.app_pick_channel), Modifier.padding(vertical = 4.dp))
            ChipRow { channels.forEach { ch -> PillChip("#${ch.name}", channel == ch.id, { channel = ch.id }) } }
            SectionHeader(stringResource(R.string.app_pick_resource), Modifier.padding(vertical = 4.dp))
            ChipRow { resources.forEach { r -> PillChip(r.label, resource?.id == r.id, { resource = r }) } }
            if (resource?.label?.contains("(private)") == true) StatusBanner(stringResource(R.string.app_private_warning), BannerKind.Warning, Modifier.padding(vertical = 6.dp))
            SectionHeader(stringResource(R.string.app_pick_events), Modifier.padding(vertical = 4.dp))
            d.app.eventTypes.forEach { e ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(e.label, style = Griip.type.bodyMedium, color = c.textPrimary, modifier = Modifier.weight(1f))
                    GriipSwitch(e.key in events, { on -> events = if (on) events + e.key else events - e.key })
                }
            }
            Text(stringResource(R.string.app_notifications_sub), style = Griip.type.caption, color = c.textSecondary, modifier = Modifier.padding(top = 6.dp))
            error?.let { StatusBanner(it, BannerKind.Error, Modifier.padding(top = 8.dp)) }
            Spacer(Modifier.height(12.dp))
            PillButton(stringResource(R.string.app_add), {
                val ch = channel ?: return@PillButton
                val r = resource ?: return@PillButton
                busy = true
                vm.act({
                    runCatching { vm.c.integrations.subscribe(vm.orgId, vm.appId, ch, r.id, events.toList().sorted()) }
                        .onSuccess { onDismiss() }.onFailure { error = it.friendly(); busy = false }.getOrThrow()
                })
            }, Modifier.fillMaxWidth(), enabled = channel != null && resource != null && events.isNotEmpty(), loading = busy, height = 50.dp)
        }
    }
}

/** Events chosen by default: openings, merges, failures and releases (the ones teams usually want). */
private val DEFAULT_EVENTS = listOf(".opened", ".merged", ".failure", "release.published", "sample.created")
