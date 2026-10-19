package com.meldiq.android.ui.screens.onboarding

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.outlined.PhotoLibrary
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import com.meldiq.android.data.api.InvitePreviewRes
import com.meldiq.android.ui.LocalContainer
import com.meldiq.android.ui.Routes
import com.meldiq.android.ui.components.BannerKind
import com.meldiq.android.ui.components.ButtonTone
import com.meldiq.android.ui.components.GlassCard
import com.meldiq.android.ui.components.GriipTextField
import com.meldiq.android.ui.components.GriipTopBar
import com.meldiq.android.ui.components.IconTile
import com.meldiq.android.ui.components.MarkTile
import com.meldiq.android.ui.components.PillButton
import com.meldiq.android.ui.components.RoleBadge
import com.meldiq.android.ui.components.SegmentedPills
import com.meldiq.android.ui.components.StatusBanner
import com.meldiq.android.ui.friendly
import com.meldiq.android.ui.scan.InviteCodes
import com.meldiq.android.ui.scan.InviteScanner
import com.meldiq.android.ui.scan.QrImageImport
import com.meldiq.android.ui.theme.Griip
import com.meldiq.android.ui.theme.Shapes
import com.meldiq.android.ui.theme.Space
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

/** Join a workspace with an invite code, or create a new organisation (you become its owner). */
@Composable
fun JoinWorkspaceScreen(nav: NavHostController, initialCode: String?) {
    val c = Griip.colors
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var code by rememberSaveable { mutableStateOf(initialCode ?: "") }
    var preview by remember { mutableStateOf<InvitePreviewRes?>(null) }
    var checking by remember { mutableStateOf(false) }
    var orgName by rememberSaveable { mutableStateOf("") }
    var orgDesc by rememberSaveable { mutableStateOf("") }
    // One id per create form: a retry or a second tap returns the workspace the first one made (server dedupes).
    var createRequestId by rememberSaveable { mutableStateOf(UUID.randomUUID().toString()) }
    var existingOrg by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<String?>(null) }
    var scanning by rememberSaveable { mutableStateOf(false) }
    val typed = InviteCodes.check(code)

    LaunchedEffect(code) {
        preview = null
        error = null
        info = null
        val ok = InviteCodes.check(code) as? InviteCodes.Typed.Ok ?: return@LaunchedEffect
        delay(300)
        checking = true
        runCatching { container.orgs.previewInvite(ok.code) }
            .onSuccess { preview = it }
            .onFailure { error = InviteOutcome.message(it) }
        checking = false
    }

    fun done(orgId: String) {
        scope.launch { container.orgs.select(orgId) }
        // Leave this screen for good. switchTab() restored the Workspace tab's saved back stack, which still had this
        // screen on top, so a successful join or create looked like nothing happened, and people tapped again
        // (physical-device report: five identical workspaces).
        if (!nav.popBackStack(Routes.WORKSPACE, inclusive = false)) {
            nav.popBackStack()
            nav.navigate(Routes.WORKSPACE) {
                popUpTo(nav.graph.findStartDestination().id)
                launchSingleTop = true
            }
        }
    }

    // Gallery import: Android's photo picker (no storage permission); the image is decoded on the phone.
    val pickQr = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            error = null
            when (val r = QrImageImport.read(context, uri)) {
                is QrImageImport.Result.Code -> code = r.code
                QrImageImport.Result.NotInvite -> error = "That image has a QR code, but it isn't a Griip invite. Nothing was opened."
                QrImageImport.Result.NoCode -> error = "No QR code found in that image. Try a sharper screenshot of the invite, or type the code."
                QrImageImport.Result.Unreadable -> error = "Couldn't open that image."
            }
        }
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
                    code,
                    { v ->
                        // A pasted invite message or link becomes just its code; typing keeps what was typed.
                        // Stored as typed and shown in capitals (rewriting the text itself fought the keyboard's own
                        // editing); InviteCodes.check normalises it before anything is sent.
                        code = (if (v.length > 24 || v.contains("://")) InviteCodes.extract(v) else null) ?: v.take(24)
                    },
                    "Invite code", placeholder = "XXXX-XXXX-XXXX", leadingIcon = Icons.Outlined.ConfirmationNumber,
                    helper = if (checking) "Checking the code…" else "Ask an owner, admin, branch manager or team lead for a code. You can paste the whole invite message.",
                    error = (typed as? InviteCodes.Typed.Invalid)?.reason,
                    visualTransformation = { t -> TransformedText(AnnotatedString(t.text.uppercase()), OffsetMapping.Identity) },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PillButton("Scan QR code", { scanning = true }, Modifier.weight(1f), tone = ButtonTone.Glass, leadingIcon = Icons.Outlined.QrCodeScanner, height = 50.dp, showKnob = false)
                    PillButton(
                        "From Gallery", { pickQr.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        Modifier.weight(1f), tone = ButtonTone.Glass, leadingIcon = Icons.Outlined.PhotoLibrary, height = 50.dp, showKnob = false,
                    )
                }
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
                val open = existingOrg
                if (tab == 1 && open != null) StatusBanner(it, BannerKind.Warning, icon = Icons.Rounded.Warning, action = "Open it", onAction = { done(open) })
                else StatusBanner(it, BannerKind.Error, icon = Icons.Rounded.Warning)
            }
            info?.let {
                Spacer(Modifier.height(12.dp))
                StatusBanner(it, BannerKind.Info)
            }
        }
        PillButton(
            if (tab == 0) "Join workspace" else "Create workspace",
            {
                if (busy) return@PillButton
                busy = true; error = null; existingOrg = null
                scope.launch {
                    if (tab == 0) {
                        val ok = typed as? InviteCodes.Typed.Ok
                        if (ok != null) runCatching { container.orgs.acceptInvite(ok.code) }
                            .onSuccess { r -> if (r.alreadyMember) info = "You're already in ${r.org.name}."; done(r.org.id) }
                            .onFailure { error = InviteOutcome.message(it) }
                    } else {
                        runCatching { container.orgs.create(orgName.trim(), orgDesc.trim().ifEmpty { null }, createRequestId) }
                            .onSuccess { createRequestId = UUID.randomUUID().toString(); done(it.id) }
                            .onFailure {
                                existingOrg = InviteOutcome.existingOrg(it)
                                error = it.friendly()
                            }
                    }
                    busy = false
                }
            },
            enabled = if (tab == 0) preview != null && typed is InviteCodes.Typed.Ok else orgName.isNotBlank(), loading = busy,
            modifier = Modifier.fillMaxWidth().padding(horizontal = Space.gutter).navigationBarsPadding().padding(bottom = 16.dp),
        )
    }
}

@Suppress("unused")
private val spacing = Arrangement.spacedBy(8.dp)
