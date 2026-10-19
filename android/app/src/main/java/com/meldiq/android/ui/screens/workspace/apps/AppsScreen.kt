package com.meldiq.android.ui.screens.workspace.apps

import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Pending
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.meldiq.android.R
import com.meldiq.android.data.api.AppActivityRes
import com.meldiq.android.data.api.AppPolicyDto
import com.meldiq.android.data.api.AppSummaryDto
import com.meldiq.android.data.api.DirectoryRes
import com.meldiq.android.data.repo.ServerFeatures
import com.meldiq.android.di.AppContainer
import com.meldiq.android.ui.TimeFmt
import com.meldiq.android.ui.containerViewModel
import com.meldiq.android.ui.friendly
import com.meldiq.android.ui.components.ArrowKnob
import com.meldiq.android.ui.components.BannerKind
import com.meldiq.android.ui.components.ButtonTone
import com.meldiq.android.ui.components.ChipRow
import com.meldiq.android.ui.components.EmptyState
import com.meldiq.android.ui.components.GlassCard
import com.meldiq.android.ui.components.GriipSwitch
import com.meldiq.android.ui.components.GriipTopBar
import com.meldiq.android.ui.components.ListCard
import com.meldiq.android.ui.components.PillButton
import com.meldiq.android.ui.components.PillChip
import com.meldiq.android.ui.components.SearchPill
import com.meldiq.android.ui.components.SectionHeader
import com.meldiq.android.ui.components.ShimmerList
import com.meldiq.android.ui.components.StatusBanner
import com.meldiq.android.ui.components.TagPill
import com.meldiq.android.ui.theme.Griip
import com.meldiq.android.ui.theme.Space
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class AppsViewModel(val c: AppContainer, val orgId: String) : ViewModel() {
    val directory = MutableStateFlow<DirectoryRes?>(null)
    val activity = MutableStateFlow<AppActivityRes?>(null)
    val policyRev = MutableStateFlow(0)
    val error = MutableStateFlow<String?>(null)
    val supported = MutableStateFlow<Boolean?>(null)
    val loading = MutableStateFlow(true)

    init {
        refresh()
        viewModelScope.launch { c.messages.appUpdates.collect { if (it == orgId) refresh() } }
        viewModelScope.launch { c.integrations.returned.collect { refresh() } }
    }

    fun refresh() = viewModelScope.launch {
        loading.value = true
        supported.value = c.features.has(ServerFeatures.INTEGRATIONS)
        if (supported.value != false) {
            runCatching { c.integrations.directory(orgId) }
                .onSuccess { directory.value = it; error.value = null }
                .onFailure { error.value = it.friendly() }
            if (directory.value?.permissions?.canViewActivity == true) runCatching { c.integrations.activity(orgId) }.onSuccess { activity.value = it }
            if (directory.value?.permissions?.canManagePolicy == true) runCatching { c.integrations.policy(orgId) }.onSuccess { policyRev.value = it.rev }
        }
        loading.value = false
    }

    fun savePolicy(p: AppPolicyDto, done: (String?) -> Unit) = viewModelScope.launch {
        runCatching { c.integrations.putPolicy(orgId, policyRev.value, p) }
            .onSuccess { policyRev.value = it.rev; done(null); refresh() }
            .onFailure { done(it.friendly()); refresh() }
    }
}

private enum class AppsTab { Directory, Installed, Requests, Mine, Workflows, Activity }

/**
 * The app directory (docs/INTEGRATIONS.md › Directory): search, featured apps, categories, and for each app its honest
 * state and the one action that fits this person's permissions. Installed apps, pending requests, the person's own
 * connections and (for admins) activity have their own tabs. Nothing here exposes provider data: that only comes after
 * someone connects their own account.
 */
@Composable
fun AppsScreen(nav: NavHostController, orgId: String) {
    val vm = containerViewModel("apps-$orgId") { AppsViewModel(it, orgId) }
    val c = Griip.colors
    val dir by vm.directory.collectAsStateWithLifecycle()
    val activity by vm.activity.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val supported by vm.supported.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf<String?>(null) }
    var tab by rememberSaveable { mutableStateOf(AppsTab.Directory) }
    LaunchedEffect(Unit) { vm.refresh() }

    val apps = dir?.apps.orEmpty()
    val perms = dir?.permissions
    val tabs = buildList {
        add(AppsTab.Directory)
        add(AppsTab.Installed)
        if (perms?.canInstall == true) add(AppsTab.Requests)
        add(AppsTab.Mine)
        if (perms?.canConfigure == true || perms?.canViewActivity == true) add(AppsTab.Workflows)
        if (perms?.canViewActivity == true) add(AppsTab.Activity)
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item { GriipTopBar(stringResource(R.string.apps_title), onBack = { nav.popBackStack() }) }
        if (supported == false) {
            item { StatusBanner(stringResource(R.string.apps_needs_update), BannerKind.Info, Modifier.padding(horizontal = Space.gutter)) }
            return@LazyColumn
        }
        error?.let { item { StatusBanner(it, BannerKind.Warning, Modifier.padding(horizontal = Space.gutter, vertical = 4.dp), action = "Retry", onAction = { vm.refresh() }) } }
        item {
            ChipRow(Modifier.padding(vertical = 6.dp)) {
                tabs.forEach { t ->
                    val count = when (t) {
                        AppsTab.Requests -> AppRules.requests(apps).size
                        AppsTab.Installed -> AppRules.installed(apps).size
                        AppsTab.Mine -> AppRules.mine(apps).size
                        else -> 0
                    }
                    PillChip(tabLabel(t) + if (count > 0) " · $count" else "", tab == t, { tab = t })
                }
            }
        }
        if (dir == null && loading) {
            item { ShimmerList(5) }
            return@LazyColumn
        }
        when (tab) {
            AppsTab.Directory -> {
                item { SearchPill(query, { query = it }, stringResource(R.string.apps_search), Modifier.padding(horizontal = Space.gutter, vertical = 6.dp)) }
                item {
                    ChipRow {
                        PillChip(stringResource(R.string.apps_all), category == null, { category = null })
                        AppRules.CATEGORIES.forEach { cat -> PillChip(categoryLabel(cat), category == cat, { category = if (category == cat) null else cat }) }
                    }
                }
                val featured = apps.filter { it.featured && it.availability != "coming_soon" }
                if (query.isBlank() && category == null && featured.isNotEmpty()) {
                    item { SectionHeader(stringResource(R.string.apps_featured), Modifier.padding(horizontal = Space.gutter, vertical = 8.dp)) }
                    item {
                        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = Space.gutter), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            featured.forEach { a -> FeaturedCard(a) { nav.navigate(appRoute(orgId, a.id)) } }
                        }
                    }
                }
                val shown = AppRules.filter(apps, query, category)
                item { SectionHeader(categoryLabel(category), Modifier.padding(horizontal = Space.gutter, vertical = 8.dp)) }
                if (shown.isEmpty()) item { EmptyState(Icons.Outlined.Apps, stringResource(R.string.apps_none_found), "") }
                items(shown, key = { it.id }) { a -> AppCard(a) { nav.navigate(appRoute(orgId, a.id)) } }
            }
            AppsTab.Installed -> {
                val list = AppRules.installed(apps)
                if (list.isEmpty()) item { EmptyState(Icons.Outlined.Apps, stringResource(R.string.apps_none_installed), "") }
                items(list, key = { it.id }) { a -> AppCard(a) { nav.navigate(appRoute(orgId, a.id)) } }
                if (perms?.canManagePolicy == true) item { PolicyCard(dir!!.policy, apps, vm) }
            }
            AppsTab.Requests -> {
                val list = AppRules.requests(apps)
                if (list.isEmpty()) item { EmptyState(Icons.Outlined.Pending, stringResource(R.string.apps_none_requests), "") }
                items(list, key = { it.id }) { a -> AppCard(a) { nav.navigate(appRoute(orgId, a.id)) } }
            }
            AppsTab.Mine -> {
                val list = AppRules.mine(apps)
                if (list.isEmpty()) item { EmptyState(Icons.Outlined.Link, stringResource(R.string.apps_none_mine), "") }
                items(list, key = { it.id }) { a -> AppCard(a, showAccount = true) { nav.navigate(appRoute(orgId, a.id)) } }
            }
            AppsTab.Workflows -> item { WorkflowsPanel(orgId) }
            AppsTab.Activity -> item { ActivityPanel(activity, apps) }
        }
    }
}

fun appRoute(orgId: String, appId: String) = "apps/$orgId/$appId"

@Composable
private fun tabLabel(t: AppsTab): String = when (t) {
    AppsTab.Directory -> stringResource(R.string.apps_tab_directory)
    AppsTab.Installed -> stringResource(R.string.apps_tab_installed)
    AppsTab.Requests -> stringResource(R.string.apps_tab_requests)
    AppsTab.Mine -> stringResource(R.string.apps_tab_mine)
    AppsTab.Workflows -> stringResource(R.string.apps_tab_workflows)
    AppsTab.Activity -> stringResource(R.string.apps_tab_activity)
}

@Composable
private fun FeaturedCard(a: AppSummaryDto, onClick: () -> Unit) {
    val c = Griip.colors
    GlassCard(Modifier.width(220.dp), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppTile(a.icon, a.state, size = 40.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(a.name, style = Griip.type.titleSmall, color = c.textPrimary, maxLines = 1)
                Text(a.publisher, style = Griip.type.caption, color = c.textSecondary, maxLines = 1)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(a.summary, style = Griip.type.bodySmall, color = c.textSecondary, maxLines = 3, minLines = 3)
        Spacer(Modifier.height(8.dp))
        StatePill(a.state)
    }
}

@Composable
private fun AppCard(a: AppSummaryDto, showAccount: Boolean = false, onClick: () -> Unit) {
    val c = Griip.colors
    ListCard(
        a.name, Modifier.padding(horizontal = 8.dp, vertical = 1.dp),
        subtitle = if (showAccount) a.myAccount?.label?.let { stringResource(R.string.app_connected_as, it) } ?: a.summary else a.summary,
        leading = { AppTile(a.icon, a.state) },
        meta = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StatePill(a.state)
                TagPill(categoryLabel(a.category), c.textSecondary, c.surfaceVariant)
                if (a.availability == "sandbox") TagPill(stringResource(R.string.app_sandbox_badge), c.lavender, c.lavenderSoft)
                if (a.sensitivity == "high") TagPill(stringResource(R.string.app_high_sensitivity), c.warning, c.warningSoft)
            }
        },
        trailing = {
            val action = AppRules.primaryAction(a.actions)
            if (action != null && action != "configure") PillButton(actionLabel(action), onClick, tone = ButtonTone.Glass, height = 38.dp) else ArrowKnob()
        },
        onClick = onClick,
    )
}

/** Workspace-wide app policy (policies.manage): a workspace setting, not anyone's personal preference. */
@Composable
private fun PolicyCard(policy: AppPolicyDto, apps: List<AppSummaryDto>, vm: AppsViewModel) {
    val c = Griip.colors
    val scope = rememberCoroutineScope()
    var draft by remember(policy) { mutableStateOf(policy) }
    var msg by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val saved = stringResource(R.string.apps_policy_saved)
    GlassCard(Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = 12.dp)) {
        Text(stringResource(R.string.apps_policy), style = Griip.type.section, color = c.textPrimary)
        Spacer(Modifier.height(8.dp))
        PolicySwitch(stringResource(R.string.apps_policy_requests), draft.requestsEnabled) { draft = draft.copy(requestsEnabled = it) }
        PolicySwitch(stringResource(R.string.apps_policy_member_accounts), draft.memberAccounts) { draft = draft.copy(memberAccounts = it) }
        PolicySwitch(stringResource(R.string.apps_policy_block), draft.blockUnlisted) { draft = draft.copy(blockUnlisted = it) }
        if (draft.blockUnlisted) {
            Text(stringResource(R.string.apps_policy_allowed), style = Griip.type.label, color = c.textSecondary, modifier = Modifier.padding(top = 6.dp))
            ChipRow {
                apps.filter { it.availability != "coming_soon" }.forEach { a ->
                    val on = a.id in draft.allowedConnectors
                    PillChip(a.name, on, { draft = draft.copy(allowedConnectors = if (on) draft.allowedConnectors - a.id else draft.allowedConnectors + a.id) })
                }
            }
        }
        msg?.let { StatusBanner(it, if (it == saved) BannerKind.Success else BannerKind.Error, Modifier.padding(top = 8.dp)) }
        Spacer(Modifier.height(10.dp))
        PillButton(stringResource(R.string.app_save), {
            busy = true
            scope.launch { vm.savePolicy(draft) { err -> msg = err ?: saved; busy = false } }
        }, Modifier.fillMaxWidth(), enabled = draft != policy, loading = busy, height = 46.dp)
    }
}

@Composable
private fun PolicySwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val c = Griip.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = Griip.type.bodyMedium, color = c.textPrimary, modifier = Modifier.weight(1f))
        GriipSwitch(checked, onChange)
    }
}

/** Integration admins: each installed app's health, recent channel notifications and recorded changes. */
@Composable
private fun ActivityPanel(a: AppActivityRes?, apps: List<AppSummaryDto>) {
    val c = Griip.colors
    val name = { id: String -> apps.firstOrNull { it.id == id }?.name ?: id }
    Column(Modifier.padding(horizontal = Space.gutter)) {
        if (a == null || (a.audit.isEmpty() && a.health.isEmpty() && a.notifications.isEmpty())) {
            EmptyState(Icons.Outlined.History, stringResource(R.string.apps_activity_empty), "")
            return
        }
        if (a.health.isNotEmpty()) {
            SectionHeader(stringResource(R.string.apps_health), Modifier.padding(vertical = 8.dp))
            a.health.forEach { h ->
                ListCard(
                    name(h.connectorId),
                    subtitle = h.lastError ?: h.lastEventAt?.let { TimeFmt.listStamp(it) } ?: stateLabel(h.state),
                    leading = { AppTile(apps.firstOrNull { it.id == h.connectorId }?.icon ?: "apps", if (h.lastError != null) "error" else "installed", size = 38.dp) },
                )
            }
        }
        if (a.notifications.isNotEmpty()) {
            SectionHeader(stringResource(R.string.apps_recent_notifications), Modifier.padding(vertical = 8.dp))
            a.notifications.forEach { n -> ListCard(n.title, subtitle = "${name(n.connectorId)} · #${n.channelName ?: "?"} · ${TimeFmt.listStamp(n.createdAt)}") }
        }
        if (a.audit.isNotEmpty()) {
            SectionHeader(stringResource(R.string.apps_changes), Modifier.padding(vertical = 8.dp))
            a.audit.forEach { e ->
                ListCard(
                    "${e.actorName ?: "Meldiq"} · ${e.action.removePrefix("integration.").replace('.', ' ').replace('_', ' ')}",
                    subtitle = listOfNotNull(e.target?.let(name), TimeFmt.listStamp(e.at)).joinToString(" · "),
                )
            }
        }
    }
}
