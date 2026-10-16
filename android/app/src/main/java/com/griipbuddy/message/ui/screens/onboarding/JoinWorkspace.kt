package com.griipbuddy.message.ui.screens.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apartment
import androidx.compose.material.icons.outlined.ConfirmationNumber
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.griipbuddy.message.data.api.InvitePreviewRes
import com.griipbuddy.message.ui.LocalContainer
import com.griipbuddy.message.ui.Routes
import com.griipbuddy.message.ui.components.BannerKind
import com.griipbuddy.message.ui.components.ButtonTone
import com.griipbuddy.message.ui.components.GlassCard
import com.griipbuddy.message.ui.components.GriipTextField
import com.griipbuddy.message.ui.components.GriipTopBar
import com.griipbuddy.message.ui.components.IconTile
import com.griipbuddy.message.ui.components.MarkTile
import com.griipbuddy.message.ui.components.PillButton
import com.griipbuddy.message.ui.components.RoleBadge
import com.griipbuddy.message.ui.components.SegmentedPills
import com.griipbuddy.message.ui.components.StatusBanner
import com.griipbuddy.message.ui.friendly
import com.griipbuddy.message.ui.scan.InviteScanner
import com.griipbuddy.message.ui.switchTab
import com.griipbuddy.message.ui.theme.Griip
import com.griipbuddy.message.ui.theme.Shapes
import com.griipbuddy.message.ui.theme.Space
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Join a workspace with an invite code, or create a new organisation (you become its owner). */
@Composable
fun JoinWorkspaceScreen(nav: NavHostController, initialCode: String?) {
    val c = Griip.colors
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var code by rememberSaveable { mutableStateOf(initialCode ?: "") }
    var preview by remember { mutableStateOf<InvitePreviewRes?>(null) }
    var orgName by rememberSaveable { mutableStateOf("") }
    var orgDesc by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var scanning by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(code) {
        preview = null
        error = null
        val clean = code.filter { it.isLetterOrDigit() }
        if (clean.length < 12) return@LaunchedEffect
        delay(300)
        runCatching { container.orgs.previewInvite(code) }.onSuccess { preview = it }.onFailure { error = it.friendly() }
    }

    fun done(orgId: String) {
        scope.launch { container.orgs.select(orgId) }
        nav.switchTab(Routes.WORKSPACE)
    }

    if (scanning) {
        InviteScanner(onCode = { code = it; scanning = false }, onClose = { scanning = false })
        return
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        GriipTopBar("Your workspace", onBack = { nav.popBackStack() })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = Space.gutter)) {
            Spacer(Modifier.height(8.dp))
            SegmentedPills(listOf("Join with invite", "Create new"), tab, { tab = it; error = null })
            Spacer(Modifier.height(20.dp))
            if (tab == 0) {
                GriipTextField(
                    code, { code = it.uppercase().take(20) }, "Invite code", placeholder = "XXXX-XXXX-XXXX", leadingIcon = Icons.Outlined.ConfirmationNumber,
                    helper = "Ask an owner, admin, branch manager or team lead for a code.",
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                )
                Spacer(Modifier.height(12.dp))
                PillButton("Scan invite QR code", { scanning = true }, Modifier.fillMaxWidth(), tone = ButtonTone.Glass, leadingIcon = Icons.Outlined.QrCodeScanner, height = 50.dp)
                preview?.let { p ->
                    Spacer(Modifier.height(16.dp))
                    GlassCard(Modifier.fillMaxWidth(), shape = Shapes.hero) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            MarkTile(p.org.name, p.org.name.hashCode(), size = 52.dp)
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(p.org.name, style = Griip.type.title, color = c.textPrimary)
                                Text(listOfNotNull(p.branch?.name, p.team?.name).joinToString(" · ").ifEmpty { "Organization-wide" }, style = Griip.type.bodySmall, color = c.textSecondary)
                            }
                            RoleBadge(p.role)
                        }
                        if (p.alreadyMember) {
                            Spacer(Modifier.height(10.dp))
                            Text("You're already a member — accepting may add you to this branch/team.", style = Griip.type.caption, color = c.textTertiary)
                        }
                    }
                }
            } else {
                GlassCard(Modifier.fillMaxWidth()) {
                    Row {
                        IconTile(Icons.Outlined.Apartment, c.lavender, c.lavenderSoft, size = 40.dp)
                        Spacer(Modifier.width(12.dp))
                        Text("You'll be the owner. Add branches, departments and teams next, then invite colleagues.", style = Griip.type.bodySmall, color = c.textSecondary)
                    }
                }
                Spacer(Modifier.height(16.dp))
                GriipTextField(orgName, { orgName = it.take(80) }, "Organization name", placeholder = "GriipBuddy", leadingIcon = Icons.Outlined.Groups,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words))
                Spacer(Modifier.height(16.dp))
                GriipTextField(orgDesc, { orgDesc = it.take(300) }, "Description (optional)", placeholder = "What is this workspace for?", singleLine = false, minLines = 2)
            }
            error?.let {
                Spacer(Modifier.height(12.dp))
                StatusBanner(it, BannerKind.Error, icon = Icons.Rounded.Warning)
            }
        }
        PillButton(
            if (tab == 0) "Join workspace" else "Create workspace",
            {
                busy = true; error = null
                scope.launch {
                    runCatching {
                        if (tab == 0) container.orgs.acceptInvite(code) else container.orgs.create(orgName.trim(), orgDesc.trim().ifEmpty { null })
                    }.onSuccess { done(it.id) }.onFailure { error = it.friendly() }
                    busy = false
                }
            },
            enabled = if (tab == 0) preview != null else orgName.isNotBlank(), loading = busy,
            modifier = Modifier.fillMaxWidth().padding(horizontal = Space.gutter).navigationBarsPadding().padding(bottom = 16.dp),
        )
    }
}

@Suppress("unused")
private val spacing = Arrangement.spacedBy(8.dp)
