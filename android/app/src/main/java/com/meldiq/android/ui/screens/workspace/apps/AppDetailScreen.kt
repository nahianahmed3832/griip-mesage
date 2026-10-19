package com.meldiq.android.ui.screens.workspace.apps

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.meldiq.android.R
import com.meldiq.android.data.api.AppDetailRes
import com.meldiq.android.data.api.SubscriptionDto
import com.meldiq.android.di.AppContainer
import com.meldiq.android.ui.LinkOpener
import com.meldiq.android.ui.TimeFmt
import com.meldiq.android.ui.components.BannerKind
import com.meldiq.android.ui.components.ButtonTone
import com.meldiq.android.ui.components.ConfirmDialog
import com.meldiq.android.ui.components.GlassCard
import com.meldiq.android.ui.components.GriipTopBar
import com.meldiq.android.ui.components.ListCard
import com.meldiq.android.ui.components.PillButton
import com.meldiq.android.ui.components.SectionHeader
import com.meldiq.android.ui.components.ShimmerList
import com.meldiq.android.ui.components.StatusBanner
import com.meldiq.android.ui.components.TagPill
import com.meldiq.android.ui.containerViewModel
import com.meldiq.android.ui.friendly
import com.meldiq.android.ui.theme.Griip
import com.meldiq.android.ui.theme.Space
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class AppDetailViewModel(val c: AppContainer, val orgId: String, val appId: String) : ViewModel() {
    val detail = MutableStateFlow<AppDetailRes?>(null)
    val subscriptions = MutableStateFlow<List<SubscriptionDto>>(emptyList())
    val error = MutableStateFlow<String?>(null)
    val notice = MutableStateFlow<Pair<String, BannerKind>?>(null)
    /** A sign-in waiting in the browser, and how it ended. */
    val pendingFlow = MutableStateFlow<String?>(null)
    val outcome = MutableStateFlow<AppRules.FlowOutcome?>(null)
    val busy = MutableStateFlow(false)
    private var polling: Job? = null

    init {
        refresh()
        viewModelScope.launch { c.messages.appUpdates.collect { if (it == orgId) refresh() } }
        viewModelScope.launch { c.integrations.returned.collect { checkFlow() } }
    }

    fun refresh() = viewModelScope.launch {
        runCatching { c.integrations.detail(orgId, appId) }.onSuccess { d ->
            detail.value = d
            error.value = null
            val notifies = d.app.capabilities.any { it.kind == "notify" && it.granted }
            if (notifies && d.app.supports.workspaceLink && (d.actions.contains("configure") || d.actions.contains("link_workspace"))) {
                runCatching { c.integrations.subscriptions(orgId, appId) }.onSuccess { subscriptions.value = it }
            }
        }.onFailure { error.value = it.friendly() }
    }

    /** Runs an action, then reloads; failures show as a banner (the server's own explanation). */
    fun act(block: suspend () -> Unit, success: String? = null) = viewModelScope.launch {
        busy.value = true
        runCatching { block() }
            .onSuccess { success?.let { notice.value = it to BannerKind.Success } }
            .onFailure { notice.value = it.friendly() to BannerKind.Error }
        busy.value = false
        refresh()
    }

    /** Starts a sign-in: the server makes a single-use link the browser opens; nothing secret reaches the phone. */
    fun connect(open: (String) -> Boolean) = viewModelScope.launch {
        busy.value = true
        outcome.value = null
        runCatching { c.integrations.startConnect(orgId, appId) }.onSuccess { s ->
            if (!AppRules.safeBeginUrl(s.beginUrl, c.api.serverAddress()) || !open(s.beginUrl)) {
                notice.value = "Couldn't open the browser for this sign-in." to BannerKind.Error
            } else {
                pendingFlow.value = s.flowId
                outcome.value = AppRules.FlowOutcome.Waiting
            }
        }.onFailure { notice.value = it.friendly() to BannerKind.Error }
        busy.value = false
    }

    /** Asks the server how the sign-in ended; keeps asking for a while if it's still open in the browser. */
    fun checkFlow() {
        val flow = pendingFlow.value ?: return
        polling?.cancel()
        polling = viewModelScope.launch {
            repeat(POLLS) {
                val s = runCatching { c.integrations.flowStatus(flow) }.getOrNull()
                if (s != null) {
                    val o = AppRules.outcome(s.status, s.error, s.note)
                    outcome.value = o
                    if (!AppRules.stillOpen(o)) {
                        pendingFlow.value = null
                        refresh()
                        return@launch
                    }
                }
                delay(POLL_MS)
            }
        }
    }

    companion object {
        private const val POLLS = 60
        private const val POLL_MS = 2000L
    }
}

/**
 * One app (docs/INTEGRATIONS.md › Details): what it does and needs before anyone commits, its honest setup progress,
 * and only the actions this person may take. Connecting opens the provider's own sign-in in the browser; the phone never
 * handles a provider password or token.
 */
@Composable
fun AppDetailScreen(nav: NavHostController, orgId: String, appId: String) {
    val vm = containerViewModel("app-$orgId-$appId") { AppDetailViewModel(it, orgId, appId) }
    val c = Griip.colors
    val ctx = LocalContext.current
    val d by vm.detail.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val outcome by vm.outcome.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val subs by vm.subscriptions.collectAsStateWithLifecycle()
    var sheet by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf<String?>(null) }
    // Coming back from the browser (with or without the return link): ask how the sign-in ended.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) vm.checkFlow() }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(Unit) { vm.refresh() }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item { GriipTopBar(d?.app?.name ?: stringResource(R.string.apps_title), subtitle = d?.app?.publisher, onBack = { nav.popBackStack() }) }
        error?.let { item { StatusBanner(it, BannerKind.Warning, Modifier.padding(horizontal = Space.gutter, vertical = 4.dp), action = "Retry", onAction = { vm.refresh() }) } }
        val det = d
        if (det == null) {
            item { ShimmerList(4) }
            return@LazyColumn
        }
        val app = det.app
        item {
            GlassCard(Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppTile(app.icon, det.state, size = 56.dp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(app.name, style = Griip.type.title, color = c.textPrimary)
                        Text(app.summary, style = Griip.type.bodySmall, color = c.textSecondary)
                        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            StatePill(det.state)
                            TagPill(categoryLabel(app.category), c.textSecondary, c.surfaceVariant)
                            if (app.availability == "sandbox") TagPill(stringResource(R.string.app_sandbox_badge), c.lavender, c.lavenderSoft)
                            if (app.sensitivity == "high") TagPill(stringResource(R.string.app_high_sensitivity), c.warning, c.warningSoft)
                        }
                    }
                }
                notice?.let { (text, kind) -> StatusBanner(text, kind, Modifier.padding(top = 10.dp)) }
                OutcomeBanner(outcome, app.name)
                StateBanner(det)
                Spacer(Modifier.height(12.dp))
                ActionButtons(det, busy) { action ->
                    when (action) {
                        "install", "approve" -> sheet = action
                        "request" -> sheet = "request"
                        "connect", "reconnect" -> sheet = "connect"
                        "configure" -> sheet = "configure"
                        "link_workspace" -> sheet = "link"
                        "decline", "disconnect", "uninstall" -> confirm = action
                    }
                }
            }
        }
        // Setup progress: only steps that are really done are ticked (each from the server's state).
        if (det.install?.state == "installed") item { SetupProgress(det, subs) }
        item { SectionHeader(stringResource(R.string.app_what_it_does), Modifier.padding(horizontal = Space.gutter, vertical = 8.dp)) }
        item { Text(app.description, style = Griip.type.bodyMedium, color = c.textSecondary, modifier = Modifier.padding(horizontal = Space.gutter)) }
        if (app.capabilities.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.app_capabilities), Modifier.padding(horizontal = Space.gutter, vertical = 8.dp)) }
            item {
                GlassCard(Modifier.fillMaxWidth().padding(horizontal = Space.gutter)) {
                    app.capabilities.forEach { cap -> CapabilityRow(cap, showGrant = det.install?.state == "installed" || det.install?.state == "disabled") }
                }
            }
        }
        if (app.dataAccess.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.app_data_access), Modifier.padding(horizontal = Space.gutter, vertical = 8.dp)) }
            item {
                GlassCard(Modifier.fillMaxWidth().padding(horizontal = Space.gutter)) {
                    app.dataAccess.forEach { line -> Text("• $line", style = Griip.type.bodySmall, color = c.textSecondary, modifier = Modifier.padding(vertical = 3.dp)) }
                    app.reviewNote?.let {
                        Text(stringResource(R.string.app_review_note), style = Griip.type.label, color = c.textPrimary, modifier = Modifier.padding(top = 10.dp))
                        Text(it, style = Griip.type.caption, color = c.textSecondary)
                    }
                }
            }
        }
        if (det.install != null && app.capabilities.any { it.accountType == "member" }) item { AccountCard(det) }
        if (app.supports.workspaceLink && det.install?.state == "installed") item {
            WorkspaceLinkCard(det, subs, onLink = { sheet = "link" }, onAdd = { sheet = "subscribe" }, onRemove = { id -> vm.act({ vm.c.integrations.unsubscribe(orgId, id) }) },
                onTest = { vm.act({ vm.c.integrations.testEvent(orgId, appId, subs.firstOrNull()?.resource) }, ctx.getString(R.string.app_test_sent)) })
        }
        // Incoming webhooks: the workspace's own webhooks for its tools (the secret is shown once when made).
        if (app.id == "incoming-webhooks" && det.install?.state == "installed") item { HooksCard(orgId, canConfigure = "configure" in det.actions) }
        if (app.setup.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.app_setup_steps), Modifier.padding(horizontal = Space.gutter, vertical = 8.dp)) }
            item {
                GlassCard(Modifier.fillMaxWidth().padding(horizontal = Space.gutter)) {
                    app.setup.forEachIndexed { i, s -> Text("${i + 1}. $s", style = Griip.type.bodySmall, color = c.textSecondary, modifier = Modifier.padding(vertical = 3.dp)) }
                }
            }
        }
    }

    val det = d ?: return
    when (sheet) {
        "install", "approve" -> InstallSheet(det, approving = sheet == "approve", onDismiss = { sheet = null }) { caps ->
            sheet = null
            vm.act({ vm.c.integrations.install(orgId, appId, caps) })
        }
        "request" -> RequestSheet(det, onDismiss = { sheet = null }) { note ->
            sheet = null
            vm.act({ vm.c.integrations.request(orgId, appId, note) }, ctx.getString(R.string.app_request_sent))
        }
        "connect" -> ConnectSheet(det, onDismiss = { sheet = null }) {
            sheet = null
            vm.connect { url -> LinkOpener.open(ctx, url, inApp = true) }
        }
        "configure" -> ConfigureSheet(vm, det, onDismiss = { sheet = null })
        "link" -> LinkSheet(vm, det, onDismiss = { sheet = null }, openUrl = { LinkOpener.open(ctx, it, inApp = true) })
        "subscribe" -> SubscribeSheet(vm, det, onDismiss = { sheet = null })
    }
    when (confirm) {
        "decline" -> ConfirmDialog(stringResource(R.string.app_decline), det.install?.requestNote ?: "", stringResource(R.string.app_decline), onConfirm = {
            confirm = null
            vm.act({ vm.c.integrations.decline(orgId, appId) })
        }, onDismiss = { confirm = null }, destructive = true)
        "disconnect" -> ConfirmDialog(stringResource(R.string.app_disconnect_title, det.app.name), stringResource(R.string.app_disconnect_body), stringResource(R.string.app_disconnect), onConfirm = {
            confirm = null
            det.myAccount?.let { a -> vm.act({ vm.c.integrations.disconnect(orgId, a.id) }) }
        }, onDismiss = { confirm = null }, destructive = true)
        "uninstall" -> ConfirmDialog(stringResource(R.string.app_uninstall_title, det.app.name), stringResource(R.string.app_uninstall_body), stringResource(R.string.app_uninstall), onConfirm = {
            confirm = null
            vm.act({ vm.c.integrations.uninstall(orgId, appId) })
        }, onDismiss = { confirm = null }, destructive = true)
    }
}

@Composable
private fun OutcomeBanner(o: AppRules.FlowOutcome?, name: String) {
    val m = Modifier.padding(top = 10.dp)
    when (o) {
        null -> Unit
        AppRules.FlowOutcome.Waiting -> StatusBanner(stringResource(R.string.app_connect_waiting), BannerKind.Info, m)
        AppRules.FlowOutcome.Approved -> StatusBanner(stringResource(R.string.app_connect_approved), BannerKind.Info, m)
        AppRules.FlowOutcome.Connected -> StatusBanner(stringResource(R.string.app_connect_done, name), BannerKind.Success, m)
        is AppRules.FlowOutcome.ConnectedPartly -> StatusBanner(o.note, BannerKind.Warning, m)
        AppRules.FlowOutcome.Denied -> StatusBanner(stringResource(R.string.app_connect_denied), BannerKind.Warning, m)
        AppRules.FlowOutcome.Expired -> StatusBanner(stringResource(R.string.app_connect_expired), BannerKind.Warning, m)
        is AppRules.FlowOutcome.Failed -> StatusBanner(stringResource(R.string.app_connect_failed, o.reason), BannerKind.Error, m)
    }
}

/** Why nothing (or something limited) can happen, in plain words. */
@Composable
private fun StateBanner(d: AppDetailRes) {
    val m = Modifier.padding(top = 10.dp)
    when (d.state) {
        "coming_soon" -> StatusBanner(stringResource(R.string.app_coming_soon_body), BannerKind.Info, m, icon = Icons.Outlined.Info)
        "needs_configuration" -> StatusBanner(stringResource(R.string.app_setup_body), BannerKind.Info, m, icon = Icons.Outlined.Info)
        "blocked" -> StatusBanner(stringResource(R.string.app_blocked_body), BannerKind.Warning, m, icon = Icons.Outlined.Lock)
        "error" -> StatusBanner(d.myAccount?.lastError ?: stringResource(R.string.app_state_error), BannerKind.Error, m, icon = Icons.Outlined.Warning)
        "approval_required" -> d.install?.requestedByName?.let { StatusBanner(stringResource(R.string.app_requested_by, it) + (d.install.requestNote?.let { n -> ": “$n”" } ?: ""), BannerKind.Info, m) }
        else -> Unit
    }
}

@Composable
private fun ActionButtons(d: AppDetailRes, busy: Boolean, onAction: (String) -> Unit) {
    val primary = AppRules.primaryAction(d.actions)
    val secondary = d.actions.filter { it != primary && it != "uninstall" && it != "disconnect" }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        primary?.let { PillButton(actionLabel(it), { onAction(it) }, Modifier.fillMaxWidth(), loading = busy, height = 50.dp) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            secondary.forEach { a -> PillButton(actionLabel(a), { onAction(a) }, Modifier.weight(1f), tone = if (a == "decline") ButtonTone.Danger else ButtonTone.Glass, height = 42.dp) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if ("disconnect" in d.actions) PillButton(actionLabel("disconnect"), { onAction("disconnect") }, Modifier.weight(1f), tone = ButtonTone.Danger, height = 42.dp)
            if ("uninstall" in d.actions) PillButton(actionLabel("uninstall"), { onAction("uninstall") }, Modifier.weight(1f), tone = ButtonTone.Danger, height = 42.dp)
        }
    }
}

@Composable
private fun SetupProgress(d: AppDetailRes, subs: List<SubscriptionDto>) {
    val memberCaps = d.app.capabilities.any { it.accountType == "member" && it.granted }
    val notify = d.app.capabilities.any { it.kind == "notify" && it.granted }
    val c = Griip.colors
    GlassCard(Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = 4.dp)) {
        SetupStep(true, stringResource(R.string.app_state_installed), d.install?.installedByName?.let { stringResource(R.string.app_installed_by, it) })
        if (memberCaps) SetupStep(d.myAccount?.status == "active", stringResource(R.string.app_your_account), d.myAccount?.label?.let { stringResource(R.string.app_connected_as, it) })
        // Linking and subscriptions are for apps that link the workspace (GitHub, the sandbox); incoming webhooks post
        // through their own webhooks, listed in their card.
        if (notify && d.app.supports.workspaceLink) {
            val link = d.workspaceConnections.firstOrNull()
            SetupStep(link != null, stringResource(R.string.app_link_title), link?.label?.let { stringResource(R.string.app_linked_to, it) })
            SetupStep(subs.any { it.active }, stringResource(R.string.app_notifications), subs.firstOrNull()?.let { "#${it.channelName ?: "?"} · ${it.resource}" })
        }
        if (d.memberConnectionCount > 0) Text(stringResource(R.string.app_member_connections, d.memberConnectionCount), style = Griip.type.caption, color = c.textSecondary, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun AccountCard(d: AppDetailRes) {
    val c = Griip.colors
    SectionHeader(stringResource(R.string.app_your_account), Modifier.padding(horizontal = Space.gutter, vertical = 8.dp))
    GlassCard(Modifier.fillMaxWidth().padding(horizontal = Space.gutter)) {
        val a = d.myAccount
        if (a == null) {
            Text(stringResource(R.string.app_not_connected), style = Griip.type.bodyMedium, color = c.textSecondary)
        } else {
            Text(a.label?.let { stringResource(R.string.app_connected_as, it) } ?: stringResource(R.string.app_state_connected), style = Griip.type.titleSmall, color = c.textPrimary)
            Text(TimeFmt.listStamp(a.connectedAt), style = Griip.type.caption, color = c.textSecondary)
            if (a.scopes.isNotEmpty()) Text(a.scopes.joinToString(" · "), style = Griip.type.caption, color = c.textTertiary, modifier = Modifier.padding(top = 4.dp))
            a.lastError?.let { StatusBanner(it, BannerKind.Error, Modifier.padding(top = 8.dp)) }
        }
    }
}

@Composable
private fun WorkspaceLinkCard(d: AppDetailRes, subs: List<SubscriptionDto>, onLink: () -> Unit, onAdd: () -> Unit, onRemove: (String) -> Unit, onTest: () -> Unit) {
    val c = Griip.colors
    val canConfigure = "configure" in d.actions
    SectionHeader(stringResource(R.string.app_notifications), Modifier.padding(horizontal = Space.gutter, vertical = 8.dp))
    GlassCard(Modifier.fillMaxWidth().padding(horizontal = Space.gutter)) {
        Text(stringResource(R.string.app_notifications_sub), style = Griip.type.caption, color = c.textSecondary)
        Spacer(Modifier.height(8.dp))
        val link = d.workspaceConnections.firstOrNull()
        if (link == null) {
            Text(stringResource(R.string.app_link_title), style = Griip.type.titleSmall, color = c.textPrimary)
            if ("link_workspace" in d.actions) PillButton(stringResource(R.string.app_link), onLink, Modifier.fillMaxWidth().padding(top = 8.dp), tone = ButtonTone.Glass, height = 44.dp)
        } else {
            Text(stringResource(R.string.app_linked_to, link.label ?: "—"), style = Griip.type.titleSmall, color = c.textPrimary)
            subs.forEach { s ->
                ListCard(
                    "#${s.channelName ?: "?"} · ${s.resource}",
                    subtitle = if (!s.active) stringResource(R.string.app_paused, s.lastError ?: "") else s.events.joinToString(", ") { e -> d.app.eventTypes.firstOrNull { it.key == e }?.label ?: e },
                    trailing = if (canConfigure) ({ PillButton(stringResource(R.string.app_remove_notification), { onRemove(s.id) }, tone = ButtonTone.Glass, height = 36.dp) }) else null,
                )
            }
            if (canConfigure) {
                PillButton(stringResource(R.string.app_add_notification), onAdd, Modifier.fillMaxWidth().padding(top = 8.dp), tone = ButtonTone.Glass, height = 44.dp)
                if (d.app.availability == "sandbox" && subs.isNotEmpty()) PillButton(stringResource(R.string.app_send_test), onTest, Modifier.fillMaxWidth().padding(top = 8.dp), tone = ButtonTone.Glass, height = 44.dp)
            }
        }
    }
}
