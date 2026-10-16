package com.griipbuddy.message.ui.screens.profile

import android.content.ClipData
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.CallMade
import androidx.compose.material.icons.outlined.CallReceived
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.PhoneMissed
import androidx.compose.material.icons.outlined.PhonelinkSetup
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.SyncAlt
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.rounded.GppMaybe
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.griipbuddy.message.BuildConfig
import com.griipbuddy.message.GriipApp
import com.griipbuddy.message.crypto.Fingerprint
import com.griipbuddy.message.crypto.b64
import com.griipbuddy.message.crypto.unb64
import com.griipbuddy.message.data.api.DevicesRes
import com.griipbuddy.message.data.api.MyDeviceDto
import com.griipbuddy.message.data.api.OkRes
import com.griipbuddy.message.data.api.PairingRequestInfo
import com.griipbuddy.message.data.api.UpdateMeReq
import com.griipbuddy.message.data.api.UserDto
import com.griipbuddy.message.data.api.UserRes
import com.griipbuddy.message.ui.LocalContainer
import com.griipbuddy.message.ui.Routes
import com.griipbuddy.message.ui.TimeFmt
import com.griipbuddy.message.ui.components.Avatar
import com.griipbuddy.message.ui.components.BannerKind
import com.griipbuddy.message.ui.components.ButtonTone
import com.griipbuddy.message.ui.components.ChipRow
import com.griipbuddy.message.ui.components.CircleIconButton
import com.griipbuddy.message.ui.components.ConfirmDialog
import com.griipbuddy.message.ui.components.GlassCard
import com.griipbuddy.message.ui.components.GlassSheet
import com.griipbuddy.message.ui.components.GriipSwitch
import com.griipbuddy.message.ui.components.GriipTextField
import com.griipbuddy.message.ui.components.GriipTopBar
import com.griipbuddy.message.ui.components.IconTile
import com.griipbuddy.message.ui.components.LargeHeader
import com.griipbuddy.message.ui.components.ListCard
import com.griipbuddy.message.ui.components.MenuRow
import com.griipbuddy.message.ui.components.PillButton
import com.griipbuddy.message.ui.components.PillChip
import com.griipbuddy.message.ui.components.SectionHeader
import com.griipbuddy.message.ui.components.SegmentedPills
import com.griipbuddy.message.ui.components.StatusBanner
import com.griipbuddy.message.ui.components.TagPill
import com.griipbuddy.message.ui.friendly
import com.griipbuddy.message.ui.screens.onboarding.ServerSheet
import com.griipbuddy.message.ui.theme.Griip
import com.griipbuddy.message.ui.theme.Shapes
import com.griipbuddy.message.ui.theme.Space
import com.griipbuddy.message.ui.theme.ThemeMode
import kotlinx.coroutines.launch

/** The Settings tab: profile, then account, appearance, notifications, privacy, calls, server and about. */
@Composable
fun ProfileScreen(nav: NavHostController) {
    val container = LocalContainer.current
    val c = Griip.colors
    val session = container.session.session
    val me by container.db.users().observe(session?.userId ?: "").collectAsStateWithLifecycle(null)
    val settings by container.settings.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var edit by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { session?.userId?.let { runCatching { container.users.fetch(it) } } }
    val name = me?.displayName?.takeIf { it.isNotEmpty() } ?: session?.displayName ?: ""

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { LargeHeader("Settings") }
        item {
            GlassCard(Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = 6.dp), onClick = { edit = true }, contentPadding = PaddingValues(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Avatar(name, me?.avatarColor ?: 0, size = 60.dp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(name, style = Griip.type.title, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("@${session?.username}", style = Griip.type.bodySmall, color = c.textSecondary, maxLines = 1)
                        me?.statusText?.let { Text(it, style = Griip.type.caption, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                    CircleIconButton(Icons.Outlined.Edit, "Edit profile", { edit = true }, tint = c.primary)
                }
            }
        }
        settingsSections(nav, container, settings, c)
    }
    if (edit) EditProfileSheet(name, me?.statusText, me?.avatarColor ?: 0, { edit = false }) { dn, st, col ->
        scope.launch {
            runCatching { container.api.patch<UpdateMeReq, UserRes>("/v1/users/me", UpdateMeReq(dn, st, col)) }.onSuccess {
                container.users.remember(it.user)
                container.session.updateDisplayName(dn)
            }
            edit = false
        }
    }
}

/** The settings groups, shared by the Settings tab and the older pushed Settings route. Every control persists. */
private fun androidx.compose.foundation.lazy.LazyListScope.settingsSections(
    nav: NavHostController,
    container: com.griipbuddy.message.di.AppContainer,
    settings: com.griipbuddy.message.data.repo.Settings,
    c: com.griipbuddy.message.ui.theme.GriipColors,
) {
    fun update(block: (com.griipbuddy.message.data.repo.Settings) -> com.griipbuddy.message.data.repo.Settings) = container.settings.update(block)
    item { SectionHeader("Account", Modifier.padding(top = 14.dp, bottom = 6.dp)) }
    item {
        GlassCard(Modifier.fillMaxWidth().padding(horizontal = Space.gutter), contentPadding = PaddingValues(4.dp)) {
            MenuRow(Icons.Outlined.Security, "Security & recovery", subtitle = "Recovery key, safety numbers, what is encrypted", onClick = { nav.navigate(Routes.SECURITY) })
            MenuRow(Icons.Outlined.Devices, "Devices", subtitle = "Linked phones, link a new device, revoke", onClick = { nav.navigate(Routes.DEVICES) })
        }
    }
    item { SectionHeader("Appearance", Modifier.padding(top = 18.dp, bottom = 8.dp)) }
    item {
        SegmentedPills(
            ThemeMode.entries.map { m -> if (m == ThemeMode.SYSTEM) "System" else m.name.lowercase().replaceFirstChar { it.uppercase() } },
            ThemeMode.entries.indexOf(settings.theme),
            { i -> update { it.copy(theme = ThemeMode.entries[i]) } },
            Modifier.padding(horizontal = Space.gutter),
        )
    }
    item { SectionHeader("Notifications", Modifier.padding(top = 18.dp, bottom = 6.dp)) }
    item {
        GlassCard(Modifier.fillMaxWidth().padding(horizontal = Space.gutter), contentPadding = PaddingValues(4.dp)) {
            MenuRow(Icons.Outlined.Notifications, "Message notifications", subtitle = "Shown privately on the lock screen", trailing = { GriipSwitch(settings.notifications, { v -> update { it.copy(notifications = v) } }) })
            MenuRow(Icons.Outlined.SyncAlt, "Stay connected in background", subtitle = "Receive messages and calls without push services. Uses more battery.", trailing = {
                GriipSwitch(settings.backgroundConnection, { v -> update { it.copy(backgroundConnection = v) } })
            })
        }
    }
    item { SectionHeader("Privacy", Modifier.padding(top = 18.dp, bottom = 6.dp)) }
    item {
        GlassCard(Modifier.fillMaxWidth().padding(horizontal = Space.gutter), contentPadding = PaddingValues(4.dp)) {
            MenuRow(Icons.Outlined.DoneAll, "Send read receipts", subtitle = "Direct messages and groups of up to 20", trailing = { GriipSwitch(settings.readReceipts, { v -> update { it.copy(readReceipts = v) } }) })
            MenuRow(Icons.Outlined.Keyboard, "Show when I'm typing", trailing = { GriipSwitch(settings.typingIndicators, { v -> update { it.copy(typingIndicators = v) } }) })
        }
    }
    item { SectionHeader("Calls", Modifier.padding(top = 18.dp, bottom = 6.dp)) }
    item {
        GlassCard(Modifier.fillMaxWidth().padding(horizontal = Space.gutter), contentPadding = PaddingValues(4.dp)) {
            MenuRow(
                Icons.Outlined.Shield, "End-to-end encrypt call media",
                subtitle = if (settings.mediaE2ee) "On: calls won't join without the key. The call screen says \"End-to-end encrypted\" only once media has actually decrypted."
                else "Off: media is protected in transit only",
                trailing = { GriipSwitch(settings.mediaE2ee, { v -> update { it.copy(mediaE2ee = v) } }) },
            )
        }
    }
    item { SectionHeader("Server and about", Modifier.padding(top = 18.dp, bottom = 6.dp)) }
    item {
        GlassCard(Modifier.fillMaxWidth().padding(horizontal = Space.gutter), contentPadding = PaddingValues(4.dp)) {
            MenuRow(Icons.Outlined.Dns, container.session.serverUrl, subtitle = "Signed in to this relay. Changing servers needs a new account.", tint = c.textSecondary, tintBg = c.surfaceVariant, trailing = null)
            MenuRow(Icons.Outlined.Info, "About Griip Message", subtitle = "Version ${BuildConfig.VERSION_NAME} · internal beta · how your data is protected", tint = c.textSecondary, tintBg = c.surfaceVariant, onClick = { nav.navigate(Routes.SECURITY) })
        }
        if (container.session.serverUrl.startsWith("http://")) {
            StatusBanner("Unencrypted connection to the server (internal testing). Message content stays end-to-end encrypted; metadata and session tokens don't.", BannerKind.Warning, Modifier.padding(horizontal = Space.gutter, vertical = 8.dp), icon = Icons.Rounded.GppMaybe)
        }
    }
}

@Composable
private fun EditProfileSheet(name: String, status: String?, color: Int, onDismiss: () -> Unit, onSave: (String, String?, Int) -> Unit) {
    var n by remember { mutableStateOf(name) }
    var s by remember { mutableStateOf(status ?: "") }
    var col by remember { mutableIntStateOf(color) }
    GlassSheet(onDismiss, title = "Edit profile") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(n.ifBlank { "?" }, col, size = 56.dp)
            Spacer(Modifier.width(12.dp))
            ChipRow(Modifier.weight(1f)) { (0..7).forEach { i -> PillChip("${i + 1}", col == i, { col = i }) } }
        }
        Spacer(Modifier.height(12.dp))
        GriipTextField(n, { n = it.take(60) }, "Display name", keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words))
        Spacer(Modifier.height(12.dp))
        GriipTextField(s, { s = it.take(140) }, "Status (optional)", placeholder = "In the Dhaka office today")
        Spacer(Modifier.height(16.dp))
        PillButton("Save", { onSave(n.trim(), s.trim().ifEmpty { null }, col) }, Modifier.fillMaxWidth(), enabled = n.isNotBlank(), height = 50.dp)
    }
}

// ───────────────────────────── Devices ─────────────────────────────

@Composable
fun DevicesScreen(nav: NavHostController) {
    val container = LocalContainer.current
    val c = Griip.colors
    val scope = rememberCoroutineScope()
    var devices by remember { mutableStateOf<List<MyDeviceDto>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var revoke by remember { mutableStateOf<MyDeviceDto?>(null) }
    var linking by remember { mutableStateOf(false) }
    LaunchedEffect(reload) { runCatching { container.api.get<DevicesRes>("/v1/devices") }.onSuccess { devices = it.devices }.onFailure { error = it.friendly() } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 40.dp)) {
        item { GriipTopBar("Devices", onBack = { nav.popBackStack() }) }
        error?.let { item { StatusBanner(it, BannerKind.Error, Modifier.padding(horizontal = Space.gutter)) } }
        item {
            GlassCard(Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = 8.dp)) {
                Row {
                    IconTile(Icons.Outlined.PhonelinkSetup, c.primary, c.primarySoft, size = 42.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Link a new device", style = Griip.type.titleSmall, color = c.textPrimary)
                        Text("On the new phone choose “Link device”, then enter the code it shows here. Its history starts empty — messages are stored per device.", style = Griip.type.bodySmall, color = c.textSecondary)
                    }
                }
                Spacer(Modifier.height(12.dp))
                PillButton("Enter link code", { linking = true }, Modifier.fillMaxWidth(), height = 48.dp)
            }
        }
        item { SectionHeader("Signed-in devices", Modifier.padding(top = 10.dp, bottom = 6.dp)) }
        items(devices.orEmpty(), key = { it.id }) { d ->
            ListCard(
                d.name, Modifier.padding(horizontal = 8.dp, vertical = 1.dp),
                subtitle = (if (d.revokedAt != null) "Removed ${TimeFmt.listStamp(d.revokedAt)}" else "Added ${TimeFmt.listStamp(d.createdAt)} · last seen ${TimeFmt.relative(d.lastSeenAt)}") +
                    "\nKey ${Fingerprint.of(d.signingKey.unb64()).take(17)}…",
                leading = { IconTile(Icons.Outlined.Devices, if (d.revokedAt == null) c.primary else c.textTertiary, if (d.revokedAt == null) c.primarySoft else c.surfaceVariant, size = 42.dp) },
                trailing = {
                    when {
                        d.current -> TagPill("This device", c.primaryDeep, c.primarySoft)
                        d.revokedAt == null -> PillButton("Remove", { revoke = d }, tone = ButtonTone.Glass, showKnob = false, height = 36.dp)
                    }
                },
            )
        }
    }
    revoke?.let { d ->
        ConfirmDialog("Remove ${d.name}?", "It is signed out immediately and stops receiving new messages. Its local history isn't erased remotely.", "Remove",
            onConfirm = { revoke = null; scope.launch { runCatching { container.api.delete<OkRes>("/v1/devices/${d.id}") }.onFailure { error = it.friendly() }; reload++ } },
            onDismiss = { revoke = null }, destructive = true)
    }
    if (linking) ApproveLinkSheet({ linking = false }) { reload++ }
}

@Composable
private fun ApproveLinkSheet(onDismiss: () -> Unit, onDone: () -> Unit) {
    val container = LocalContainer.current
    val c = Griip.colors
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var info by remember { mutableStateOf<PairingRequestInfo?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    GlassSheet(onDismiss, title = "Approve new device") {
        if (info == null) {
            GriipTextField(code, { code = it.uppercase().take(14) }, "Link code from the new device", placeholder = "ABCDE FGHJK", keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters))
            error?.let { Spacer(Modifier.height(10.dp)); StatusBanner(it, BannerKind.Error) }
            Spacer(Modifier.height(16.dp))
            PillButton("Look up", {
                busy = true
                scope.launch { runCatching { container.session.lookupPairing(code) }.onSuccess { info = it }.onFailure { error = it.friendly() }; busy = false }
            }, Modifier.fillMaxWidth(), enabled = code.filter { it.isLetterOrDigit() }.length >= 10, loading = busy, height = 50.dp)
        } else {
            val i = info!!
            StatusBanner("Only approve a device you're holding right now. It will get full access to your account and receive new messages.", BannerKind.Warning, icon = Icons.Rounded.Warning)
            Spacer(Modifier.height(12.dp))
            Text(i.deviceName, style = Griip.type.title, color = c.textPrimary)
            Text("Requested ${TimeFmt.relative(i.createdAt)}", style = Griip.type.caption, color = c.textTertiary)
            Spacer(Modifier.height(8.dp))
            Text("Device key: ${Fingerprint.of(i.signingKey.unb64())}", style = Griip.type.bodySmall.copy(fontFamily = FontFamily.Monospace), color = c.textSecondary)
            error?.let { Spacer(Modifier.height(10.dp)); StatusBanner(it, BannerKind.Error) }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton("Cancel", onDismiss, Modifier.weight(1f), tone = ButtonTone.Glass, showKnob = false, height = 50.dp)
                PillButton("Approve", {
                    busy = true
                    scope.launch {
                        runCatching { container.session.approvePairing(code, i) }.onSuccess { onDone(); onDismiss() }.onFailure { error = it.friendly() }
                        busy = false
                    }
                }, Modifier.weight(1f), loading = busy, height = 50.dp)
            }
        }
    }
}

// ───────────────────────────── Security ─────────────────────────────

@Composable
fun SecurityScreen(nav: NavHostController) {
    val container = LocalContainer.current
    val c = Griip.colors
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current
    val keys = container.session.keys
    var showKey by remember { mutableStateOf(false) }
    var revealConfirm by remember { mutableStateOf(false) }
    var eraseConfirm by remember { mutableStateOf(false) }
    var unconfirmedRevoke by remember { mutableStateOf<String?>(null) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 40.dp)) {
        item { GriipTopBar("Security & recovery", onBack = { nav.popBackStack() }) }
        item {
            GlassCard(Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = 8.dp), shape = Shapes.hero) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconTile(Icons.Outlined.VerifiedUser, c.primary, c.primarySoft, size = 46.dp)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("Your safety number", style = Griip.type.titleSmall, color = c.textPrimary)
                        Text("Compare in person to verify your identity key", style = Griip.type.caption, color = c.textTertiary)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(keys?.let { Fingerprint.of(it.identityPublic) } ?: "—", style = Griip.type.title.copy(fontFamily = FontFamily.Monospace, letterSpacing = 1.sp), color = c.primaryDeep, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            }
        }
        item {
            GlassCard(Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconTile(Icons.Outlined.Key, c.lavender, c.lavenderSoft, size = 42.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Recovery key", style = Griip.type.titleSmall, color = c.textPrimary)
                        Text("Lets you sign in on a new phone if you lose this one. Anyone with it can impersonate you — store it offline.", style = Griip.type.bodySmall, color = c.textSecondary)
                    }
                }
                Spacer(Modifier.height(12.dp))
                if (showKey) {
                    val code = container.session.recoveryKey() ?: ""
                    Text(code, style = Griip.type.bodyMedium.copy(fontFamily = FontFamily.Monospace), color = c.textPrimary, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PillButton("Copy", { scope.launch { clipboard.setClipEntry(ClipData.newPlainText("recovery", code).toClipEntry()) } }, Modifier.weight(1f), tone = ButtonTone.Glass, showKnob = false, height = 46.dp)
                        PillButton("Hide", { showKey = false }, Modifier.weight(1f), tone = ButtonTone.Glass, showKnob = false, height = 46.dp)
                    }
                } else PillButton("Show recovery key", { revealConfirm = true }, Modifier.fillMaxWidth(), tone = ButtonTone.Glass, height = 48.dp)
            }
        }
        item { SectionHeader("How your data is protected", Modifier.padding(top = 12.dp, bottom = 6.dp)) }
        item {
            GlassCard(Modifier.fillMaxWidth().padding(horizontal = Space.gutter), contentPadding = PaddingValues(6.dp)) {
                MenuRow(Icons.Outlined.Shield, "Messages", subtitle = "Encrypted on this phone (HPKE X25519 + AES-256-GCM, Ed25519 signatures) before upload. No forward secrecy yet — see SECURITY.md.", trailing = null)
                MenuRow(Icons.Outlined.ChatBubbleOutline, "History", subtitle = "Stored only on your devices in an encrypted database (SQLCipher). The relay deletes copies once delivered, or after 7 days.", trailing = null)
                MenuRow(Icons.Outlined.Videocam, "Calls", subtitle = "Media is end-to-end encrypted with a per-call key sent through your encrypted messages. The call screen shows the live status.", trailing = null)
                MenuRow(Icons.Outlined.SyncAlt, "What the server can see", subtitle = "Who talks to whom and when, message sizes, org structure, channel names and your IP address.", trailing = null)
            }
        }
        item {
            PillButton("Sign out & erase this device", { eraseConfirm = true }, Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = 20.dp), tone = ButtonTone.Danger, showKnob = false, leadingIcon = Icons.AutoMirrored.Outlined.Logout, height = 50.dp)
        }
    }
    if (revealConfirm) ConfirmDialog("Show recovery key?", "Make sure nobody can see your screen.", "Show", onConfirm = { revealConfirm = false; showKey = true }, onDismiss = { revealConfirm = false })
    if (eraseConfirm) ConfirmDialog(
        "Erase this device?", "Deletes your keys, message history and files from this phone. Without your recovery key or another linked device, you can't get back into this account.", "Erase",
        onConfirm = {
            eraseConfirm = false
            scope.launch {
                // Revoke this device first (T1.9): the server drops its sessions and queue, and contacts stop
                // encrypting to keys that are about to be deleted. Erasing never pretends this happened.
                val myDevice = container.session.session?.deviceId
                val revoked = runCatching { container.api.delete<OkRes>("/v1/devices/$myDevice") }
                if (myDevice != null && revoked.isSuccess) (ctx.applicationContext as GriipApp).eraseEverything()
                else unconfirmedRevoke = revoked.exceptionOrNull()?.friendly() ?: "not signed in"
            }
        },
        onDismiss = { eraseConfirm = false }, destructive = true,
    )
    unconfirmedRevoke?.let { reason ->
        ConfirmDialog(
            "Couldn't remove this device from your account",
            "The server didn't confirm it ($reason). If you erase now, contacts may keep encrypting messages to this phone's keys until you remove it from another device under Profile → Devices.",
            "Erase anyway",
            onConfirm = { unconfirmedRevoke = null; (ctx.applicationContext as GriipApp).eraseEverything() },
            onDismiss = { unconfirmedRevoke = null }, destructive = true,
        )
    }
}

// ───────────────────────────── Settings ─────────────────────────────

/** Older pushed route to the settings; same sections as the Settings tab. */
@Composable
fun SettingsScreen(nav: NavHostController) {
    val container = LocalContainer.current
    val c = Griip.colors
    val settings by container.settings.state.collectAsStateWithLifecycle()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 40.dp)) {
        item { GriipTopBar("Settings", onBack = { nav.popBackStack() }) }
        settingsSections(nav, container, settings, c)
    }
}

// ───────────────────────────── Contact profile ─────────────────────────────

@Composable
fun UserProfileScreen(nav: NavHostController, userId: String) {
    val container = LocalContainer.current
    val c = Griip.colors
    val scope = rememberCoroutineScope()
    var user by remember { mutableStateOf<UserDto?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val local by container.db.users().observe(userId).collectAsStateWithLifecycle(null)
    val myKey = container.session.keys?.identityPublic
    LaunchedEffect(userId) { runCatching { container.users.fetch(userId) }.onSuccess { user = it }.onFailure { error = it.friendly() } }
    val u = user
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 40.dp)) {
        item { GriipTopBar("Profile", onBack = { nav.popBackStack() }) }
        error?.let { item { StatusBanner(it, BannerKind.Error, Modifier.padding(horizontal = Space.gutter)) } }
        if (u != null) {
            item {
                GlassCard(Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = 8.dp), shape = Shapes.hero) {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Avatar(u.displayName, u.avatarColor, size = 84.dp, online = u.online)
                        Spacer(Modifier.height(10.dp))
                        Text(u.displayName, style = Griip.type.headline, color = c.textPrimary)
                        Text("@${u.username}", style = Griip.type.bodySmall, color = c.textSecondary)
                        Text(if (u.online == true) "Online" else "Last seen ${TimeFmt.relative(u.lastSeenAt)}", style = Griip.type.caption, color = if (u.online == true) c.success else c.textTertiary)
                        u.statusText?.let { Spacer(Modifier.height(6.dp)); Text(it, style = Griip.type.bodySmall, color = c.textSecondary) }
                        Spacer(Modifier.height(14.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            PillButton("Message", {
                                scope.launch { runCatching { container.conversations.openDm(u.id) }.onSuccess { nav.navigate(Routes.conversation(it.id)) }.onFailure { error = it.friendly() } }
                            }, Modifier.weight(1f), height = 48.dp)
                        }
                    }
                }
            }
            item {
                GlassCard(Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconTile(if (local?.identityChanged == true) Icons.Rounded.GppMaybe else Icons.Outlined.VerifiedUser, if (local?.identityChanged == true) c.warning else c.primary, if (local?.identityChanged == true) c.warningSoft else c.primarySoft, size = 42.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Safety number", style = Griip.type.titleSmall, color = c.textPrimary)
                            Text(
                                when {
                                    local?.identityChanged == true -> "Their identity key changed since you first talked. Verify before trusting."
                                    local?.verified == true -> "You marked this identity as verified."
                                    else -> "Compare with ${u.displayName}'s screen in person."
                                },
                                style = Griip.type.caption, color = c.textTertiary,
                            )
                        }
                        if (local?.verified == true) TagPill("Verified", c.success, c.successSoft)
                    }
                    Spacer(Modifier.height(10.dp))
                    val theirs = u.identityKey?.unb64()
                    val combined = if (theirs != null && myKey != null) safetyNumber(myKey, theirs) else "—"
                    Text(combined, style = Griip.type.bodyMedium.copy(fontFamily = FontFamily.Monospace, letterSpacing = 1.sp), color = c.primaryDeep, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(12.dp))
                    if (local?.identityChanged == true) {
                        PillButton("I verified the new key", {
                            // Pin exactly the key whose safety number is shown, not whatever the server returns next.
                            val shown = u.identityKey ?: return@PillButton
                            scope.launch { container.messages.acceptIdentity(userId, shown); container.users.markVerified(userId) }
                        }, Modifier.fillMaxWidth(), height = 46.dp)
                    } else if (local?.verified != true) {
                        PillButton("Mark as verified", { scope.launch { container.users.markVerified(userId) } }, Modifier.fillMaxWidth(), tone = ButtonTone.Glass, height = 46.dp)
                    }
                }
            }
        }
    }
}

/** Both parties see the same number: fingerprints of both identity keys, sorted. */
fun safetyNumber(a: ByteArray, b: ByteArray): String {
    val fa = Fingerprint.of(a)
    val fb = Fingerprint.of(b)
    return listOf(fa, fb).sorted().joinToString("\n")
}

@Suppress("unused")
private fun keyB64(k: ByteArray) = k.b64()
