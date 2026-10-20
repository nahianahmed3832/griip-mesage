package com.meldiq.android.ui.screens.workspace.ai

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.QuestionAnswer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.meldiq.android.R
import com.meldiq.android.data.api.AiPolicyReq
import com.meldiq.android.data.api.AiStatusDto
import com.meldiq.android.data.api.AiStatusRes
import com.meldiq.android.ui.LocalContainer
import com.meldiq.android.ui.Routes
import com.meldiq.android.ui.components.BannerKind
import com.meldiq.android.ui.components.ChipRow
import com.meldiq.android.ui.components.GriipSwitch
import com.meldiq.android.ui.components.GriipTopBar
import com.meldiq.android.ui.components.ListCard
import com.meldiq.android.ui.components.MenuRow
import com.meldiq.android.ui.components.PillChip
import com.meldiq.android.ui.components.SectionHeader
import com.meldiq.android.ui.components.StatusBanner
import com.meldiq.android.ui.friendly
import com.meldiq.android.ui.theme.Griip
import com.meldiq.android.ui.theme.Space
import kotlinx.coroutines.launch

/**
 * A workspace's AI page (docs/AI.md, round 7 slice C8): what's sent and kept, asking the workspace's documents, and for
 * admins the switch and which configured provider the workspace uses.
 */
@Composable
fun AiScreen(nav: NavHostController, orgId: String) {
    val container = LocalContainer.current
    val c = Griip.colors
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<AiStatusDto?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var asking by remember { mutableStateOf(false) }
    LaunchedEffect(orgId) {
        runCatching { container.api.get<AiStatusRes>("/v1/orgs/$orgId/ai").ai }.onSuccess { status = it }.onFailure { error = it.friendly() }
    }
    fun setPolicy(enabled: Boolean, provider: String) = scope.launch {
        runCatching { container.api.put<AiPolicyReq, AiStatusRes>("/v1/orgs/$orgId/ai", AiPolicyReq(enabled, provider)).ai }
            .onSuccess { status = it; error = null }.onFailure { error = it.friendly() }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding())) {
        item { GriipTopBar(stringResource(R.string.ai_title), onBack = { nav.popBackStack() }) }
        error?.let { e -> item { StatusBanner(e, BannerKind.Error, Modifier.padding(horizontal = Space.gutter, vertical = 6.dp)) } }
        val s = status ?: return@LazyColumn
        aiReasonTextItem(s)
        if (s.canManage && s.providers.isNotEmpty()) {
            item {
                ListCard(
                    stringResource(R.string.ai_settings_switch), Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    subtitle = stringResource(R.string.ai_settings_sub),
                    trailing = { GriipSwitch(s.enabled, { on -> setPolicy(on, s.chosenProvider ?: "default") }) },
                )
            }
            item { SectionHeader(stringResource(R.string.ai_settings_provider), Modifier.padding(top = 10.dp, bottom = 2.dp)) }
            item {
                ChipRow {
                    val first = s.providers.first()
                    PillChip(stringResource(R.string.ai_settings_default, first.name), s.chosenProvider == null, { setPolicy(s.enabled, "default") })
                    s.providers.forEach { p -> PillChip(p.name, s.chosenProvider == p.id, { setPolicy(s.enabled, p.id) }) }
                }
            }
        }
        s.provider?.let { p ->
            item {
                Text(
                    "${p.name} · ${p.model}\n" + stringResource(R.string.ai_settings_month, s.monthly.used, AiRules.monthLabelLimit(s.monthly.limit, stringResource(R.string.ai_unlimited))),
                    style = Griip.type.caption, color = c.textSecondary, modifier = Modifier.padding(horizontal = Space.gutter + 4.dp, vertical = 6.dp),
                )
            }
        }
        if (s.available) item {
            MenuRow(Icons.Outlined.QuestionAnswer, stringResource(R.string.ai_ask_title), Modifier.padding(horizontal = 8.dp), subtitle = stringResource(R.string.ai_ask_note), onClick = { asking = true })
        }
        item { SectionHeader(stringResource(R.string.ai_privacy_title), Modifier.padding(top = 14.dp, bottom = 2.dp)) }
        item {
            ListCard(stringResource(R.string.ai_privacy_title), Modifier.padding(horizontal = 8.dp), subtitle = stringResource(R.string.ai_privacy_body), leading = {
                com.meldiq.android.ui.components.IconTile(Icons.Outlined.AutoAwesome, c.primary, c.primarySoft, size = 40.dp)
            })
        }
    }
    if (asking) AiAskSheet(orgId, onOpenDocument = { id -> asking = false; nav.navigate(Routes.document(id)) }, onDismiss = { asking = false })
}

private fun androidx.compose.foundation.lazy.LazyListScope.aiReasonTextItem(s: AiStatusDto) {
    if (s.reason == null) return
    item { aiReasonText(s.reason)?.let { StatusBanner(it, BannerKind.Info, Modifier.padding(horizontal = Space.gutter, vertical = 6.dp)) } }
}
