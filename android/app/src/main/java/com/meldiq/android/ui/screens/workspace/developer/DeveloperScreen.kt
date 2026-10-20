package com.meldiq.android.ui.screens.workspace.developer

import android.content.ClipData
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Webhook
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.meldiq.android.R
import com.meldiq.android.data.api.ApiKeyCreatedRes
import com.meldiq.android.data.api.ApiKeyDto
import com.meldiq.android.data.api.ApiKeyReq
import com.meldiq.android.data.api.ApiKeysRes
import com.meldiq.android.data.api.DevCatalogRes
import com.meldiq.android.data.api.OkRes
import com.meldiq.android.data.api.WebhookCreatedRes
import com.meldiq.android.data.api.WebhookDeliveriesRes
import com.meldiq.android.data.api.WebhookDeliveryDto
import com.meldiq.android.data.api.WebhookDto
import com.meldiq.android.data.api.WebhookReq
import com.meldiq.android.data.api.WebhookRes
import com.meldiq.android.data.api.WebhookSecretRes
import com.meldiq.android.data.api.WebhooksRes
import com.meldiq.android.ui.LocalContainer
import com.meldiq.android.ui.TimeFmt
import com.meldiq.android.ui.components.BannerKind
import com.meldiq.android.ui.components.ButtonTone
import com.meldiq.android.ui.components.ChipRow
import com.meldiq.android.ui.components.GlassSheet
import com.meldiq.android.ui.components.GriipTextField
import com.meldiq.android.ui.components.GriipTopBar
import com.meldiq.android.ui.components.IconTile
import com.meldiq.android.ui.components.ListCard
import com.meldiq.android.ui.components.PillButton
import com.meldiq.android.ui.components.PillChip
import com.meldiq.android.ui.components.SectionHeader
import com.meldiq.android.ui.components.StatusBanner
import com.meldiq.android.ui.friendly
import com.meldiq.android.ui.theme.Griip
import com.meldiq.android.ui.theme.Space
import kotlinx.coroutines.launch

/**
 * API keys and webhooks for a workspace (round 7 slice C6, docs/DEVELOPER_API.md), for people who can manage developer
 * access. Secrets are shown once, right after they're made; Meldiq keeps only a hash of a key.
 */
@Composable
fun DeveloperScreen(nav: NavHostController, orgId: String) {
    val container = LocalContainer.current
    val c = Griip.colors
    val scope = rememberCoroutineScope()
    var catalog by remember { mutableStateOf<DevCatalogRes?>(null) }
    var keys by remember { mutableStateOf<List<ApiKeyDto>>(emptyList()) }
    var hooks by remember { mutableStateOf<List<WebhookDto>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var sheet by remember { mutableStateOf<String?>(null) }
    var openKey by remember { mutableStateOf<ApiKeyDto?>(null) }
    var openHook by remember { mutableStateOf<WebhookDto?>(null) }
    var shownSecret by remember { mutableStateOf<Pair<String, String>?>(null) }
    LaunchedEffect(reload) {
        runCatching {
            catalog = catalog ?: container.api.get<DevCatalogRes>("/v1/developer/catalog")
            keys = container.api.get<ApiKeysRes>("/v1/orgs/$orgId/api-keys").keys
            hooks = container.api.get<WebhooksRes>("/v1/orgs/$orgId/webhooks").webhooks
        }.onSuccess { error = null }.onFailure { error = it.friendly() }
    }
    fun call(block: suspend () -> Unit) = scope.launch { runCatching { block() }.onSuccess { reload++ }.onFailure { error = it.friendly() } }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding())) {
        item { GriipTopBar(stringResource(R.string.dev_title), onBack = { nav.popBackStack() }) }
        item { Text(stringResource(R.string.dev_intro), style = Griip.type.caption, color = c.textSecondary, modifier = Modifier.padding(horizontal = Space.gutter + 4.dp, vertical = 2.dp)) }
        error?.let { e -> item { StatusBanner(e, BannerKind.Error, Modifier.padding(horizontal = Space.gutter, vertical = 6.dp)) } }
        item { SectionHeader(stringResource(R.string.dev_keys), Modifier.padding(top = 10.dp, bottom = 4.dp), action = stringResource(R.string.dev_new_key), onAction = { sheet = "key" }) }
        items(keys, key = { it.id }) { k ->
            val state = when {
                k.revoked -> stringResource(R.string.dev_revoked)
                k.expired -> stringResource(R.string.dev_expired)
                !k.ownerAllowed -> stringResource(R.string.dev_owner_lost)
                else -> stringResource(R.string.dev_expires, TimeFmt.dayHeader(k.expiresAt))
            }
            ListCard(
                "${k.name} · …${k.hint}", Modifier.padding(horizontal = 8.dp, vertical = 1.dp),
                subtitle = "$state · ${k.scopes.joinToString(", ")}" + (k.lastUsedAt?.let { "\n" + stringResource(R.string.dev_last_used, TimeFmt.listStamp(it)) } ?: ""),
                leading = { IconTile(Icons.Outlined.Key, if (k.revoked || k.expired) c.textTertiary else c.primary, c.surfaceVariant, size = 40.dp) },
                onClick = if (k.revoked) null else ({ openKey = k }),
            )
        }
        item { SectionHeader(stringResource(R.string.dev_webhooks), Modifier.padding(top = 14.dp, bottom = 4.dp), action = stringResource(R.string.dev_new_hook), onAction = { sheet = "hook" }) }
        items(hooks, key = { it.id }) { h ->
            ListCard(
                h.name, Modifier.padding(horizontal = 8.dp, vertical = 1.dp),
                subtitle = "${h.url}\n" + (h.disabledReason ?: if (!h.enabled) stringResource(R.string.dev_off) else stringResource(R.string.dev_hook_stats, h.deliveries.delivered, h.deliveries.failed, h.deliveries.waiting)),
                leading = { IconTile(Icons.Outlined.Webhook, if (h.enabled) c.success else c.textTertiary, c.surfaceVariant, size = 40.dp) },
                onClick = { openHook = h },
            )
        }
    }

    when (sheet) {
        "key" -> catalog?.let { cat -> NewKeySheet(cat.scopes, onDismiss = { sheet = null }) { req ->
            scope.launch {
                runCatching { container.api.post<ApiKeyReq, ApiKeyCreatedRes>("/v1/orgs/$orgId/api-keys", req) }
                    .onSuccess { sheet = null; shownSecret = it.key.name to it.token; reload++ }.onFailure { error = it.friendly() }
            }
        } }
        "hook" -> catalog?.let { cat -> HookSheet(null, cat.events, onDismiss = { sheet = null }) { req ->
            scope.launch {
                runCatching { container.api.post<WebhookReq, WebhookCreatedRes>("/v1/orgs/$orgId/webhooks", req) }
                    .onSuccess { sheet = null; shownSecret = it.webhook.name to it.secret; reload++ }.onFailure { error = it.friendly() }
            }
        } }
    }
    openKey?.let { k ->
        GlassSheet({ openKey = null }, title = k.name) {
            Text(stringResource(R.string.dev_key_actions), style = Griip.type.caption, color = c.textSecondary)
            Spacer(Modifier.height(8.dp))
            PillButton(stringResource(R.string.dev_rotate), {
                openKey = null
                scope.launch {
                    runCatching { container.api.post<ApiKeyCreatedRes>("/v1/orgs/$orgId/api-keys/${k.id}/rotate") }
                        .onSuccess { shownSecret = it.key.name to it.token; reload++ }.onFailure { error = it.friendly() }
                }
            }, Modifier.fillMaxWidth(), tone = ButtonTone.Glass, height = 44.dp)
            PillButton(stringResource(R.string.dev_revoke), { openKey = null; call { container.api.delete<OkRes>("/v1/orgs/$orgId/api-keys/${k.id}") } }, Modifier.fillMaxWidth().padding(top = 6.dp), tone = ButtonTone.Danger, height = 44.dp)
            Spacer(Modifier.height(16.dp))
        }
    }
    openHook?.let { h ->
        catalog?.let { cat -> HookDetailSheet(orgId, h, cat.events, onSecret = { s -> openHook = null; shownSecret = h.name to s }, onChanged = { openHook = null; reload++ }, onError = { error = it }, onDismiss = { openHook = null }) }
    }
    shownSecret?.let { (name, secret) -> SecretSheet(name, secret) { shownSecret = null } }
}

@Composable
private fun NewKeySheet(scopes: List<String>, onDismiss: () -> Unit, onCreate: (ApiKeyReq) -> Unit) {
    var name by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf(setOf<String>()) }
    var days by remember { mutableIntStateOf(90) }
    GlassSheet(onDismiss, title = stringResource(R.string.dev_new_key)) {
        GriipTextField(name, { name = it.take(60) }, stringResource(R.string.dev_name))
        Text(stringResource(R.string.dev_scopes), style = Griip.type.label, color = Griip.colors.textSecondary, modifier = Modifier.padding(top = 8.dp))
        ChipRow(Modifier.padding(horizontal = 0.dp)) { scopes.forEach { s -> PillChip(s, s in picked, { picked = if (s in picked) picked - s else picked + s }) } }
        Text(stringResource(R.string.dev_expires_in), style = Griip.type.label, color = Griip.colors.textSecondary, modifier = Modifier.padding(top = 8.dp))
        ChipRow(Modifier.padding(horizontal = 0.dp)) { listOf(30, 90, 365).forEach { d -> PillChip(stringResource(R.string.guest_days, d), days == d, { days = d }) } }
        Spacer(Modifier.height(8.dp))
        PillButton(stringResource(R.string.dev_create), { onCreate(ApiKeyReq(name.trim(), picked.toList(), days)) }, Modifier.fillMaxWidth(), enabled = name.isNotBlank() && picked.isNotEmpty(), height = 48.dp)
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun HookSheet(existing: WebhookDto?, events: List<String>, onDismiss: () -> Unit, onSave: (WebhookReq) -> Unit) {
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var url by remember { mutableStateOf(existing?.url ?: "https://") }
    var picked by remember { mutableStateOf(existing?.events?.toSet() ?: emptySet()) }
    GlassSheet(onDismiss, title = stringResource(if (existing == null) R.string.dev_new_hook else R.string.dev_edit_hook)) {
        GriipTextField(name, { name = it.take(60) }, stringResource(R.string.dev_name))
        GriipTextField(url, { url = it.take(1000) }, stringResource(R.string.dev_url), helper = stringResource(R.string.dev_url_help))
        Text(stringResource(R.string.dev_events), style = Griip.type.label, color = Griip.colors.textSecondary, modifier = Modifier.padding(top = 8.dp))
        ChipRow(Modifier.padding(horizontal = 0.dp)) { events.forEach { e -> PillChip(e, e in picked, { picked = if (e in picked) picked - e else picked + e }) } }
        Spacer(Modifier.height(8.dp))
        PillButton(stringResource(R.string.dev_save), { onSave(WebhookReq(name.trim(), url.trim(), picked.toList(), existing?.enabled)) }, Modifier.fillMaxWidth(), enabled = name.isNotBlank() && url.startsWith("https://") && picked.isNotEmpty(), height = 48.dp)
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun HookDetailSheet(orgId: String, h: WebhookDto, events: List<String>, onSecret: (String) -> Unit, onChanged: () -> Unit, onError: (String) -> Unit, onDismiss: () -> Unit) {
    val container = LocalContainer.current
    val c = Griip.colors
    val scope = rememberCoroutineScope()
    var deliveries by remember { mutableStateOf<List<WebhookDeliveryDto>>(emptyList()) }
    var editing by remember { mutableStateOf(false) }
    LaunchedEffect(h.id) { deliveries = runCatching { container.api.get<WebhookDeliveriesRes>("/v1/orgs/$orgId/webhooks/${h.id}/deliveries").deliveries }.getOrDefault(emptyList()) }
    fun call(block: suspend () -> Unit) = scope.launch { runCatching { block() }.onSuccess { onChanged() }.onFailure { onError(it.friendly()) } }
    if (editing) {
        HookSheet(h, events, onDismiss = { editing = false }) { req -> call { container.api.put<WebhookReq, WebhookRes>("/v1/orgs/$orgId/webhooks/${h.id}", req.copy(enabled = h.enabled)) } }
        return
    }
    GlassSheet(onDismiss, title = h.name) {
        Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
            Text(h.url, style = Griip.type.bodySmall, color = c.textSecondary)
            h.disabledReason?.let { StatusBanner(it, BannerKind.Warning, Modifier.padding(vertical = 6.dp)) }
            PillButton(stringResource(if (h.enabled) R.string.dev_turn_off else R.string.dev_turn_on), {
                call { container.api.put<WebhookReq, WebhookRes>("/v1/orgs/$orgId/webhooks/${h.id}", WebhookReq(h.name, h.url, h.events, !h.enabled)) }
            }, Modifier.fillMaxWidth().padding(top = 6.dp), tone = ButtonTone.Glass, height = 44.dp)
            PillButton(stringResource(R.string.dev_send_test), { call { container.api.post<WebhookRes>("/v1/orgs/$orgId/webhooks/${h.id}/test") } }, Modifier.fillMaxWidth().padding(top = 6.dp), tone = ButtonTone.Glass, height = 44.dp)
            PillButton(stringResource(R.string.dev_edit_hook), { editing = true }, Modifier.fillMaxWidth().padding(top = 6.dp), tone = ButtonTone.Glass, height = 44.dp)
            PillButton(stringResource(R.string.dev_rotate_secret), {
                scope.launch { runCatching { container.api.post<WebhookSecretRes>("/v1/orgs/$orgId/webhooks/${h.id}/rotate-secret").secret }.onSuccess(onSecret).onFailure { onError(it.friendly()) } }
            }, Modifier.fillMaxWidth().padding(top = 6.dp), tone = ButtonTone.Glass, height = 44.dp)
            PillButton(stringResource(R.string.dev_delete_hook), { call { container.api.delete<OkRes>("/v1/orgs/$orgId/webhooks/${h.id}") } }, Modifier.fillMaxWidth().padding(top = 6.dp), tone = ButtonTone.Danger, height = 44.dp)
            SectionHeader(stringResource(R.string.dev_deliveries), Modifier.padding(top = 12.dp, bottom = 4.dp))
            deliveries.forEach { d ->
                Text(
                    "${TimeFmt.listStamp(d.createdAt)} · ${d.event} · ${d.state}" + (d.lastStatus?.let { " · $it" } ?: "") + (d.lastError?.takeIf { d.state != "delivered" }?.let { " · $it" } ?: "") + " · ${d.attempts}×",
                    style = Griip.type.caption, color = if (d.state == "failed") c.danger else c.textSecondary,
                )
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

/** A key's token or a webhook's signing secret, shown once with a copy button. */
@Composable
private fun SecretSheet(name: String, secret: String, onDismiss: () -> Unit) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    GlassSheet(onDismiss, title = name) {
        Text(stringResource(R.string.dev_secret_once), style = Griip.type.body, color = Griip.colors.warning)
        Spacer(Modifier.height(8.dp))
        SelectionContainer { Text(secret, style = Griip.type.bodySmall.copy(fontFamily = FontFamily.Monospace), color = Griip.colors.textPrimary) }
        Spacer(Modifier.height(8.dp))
        PillButton(stringResource(R.string.dev_copy), { scope.launch { clipboard.setClipEntry(ClipData.newPlainText("secret", secret).toClipEntry()) } }, Modifier.fillMaxWidth(), height = 44.dp)
        PillButton(stringResource(R.string.dev_done), onDismiss, Modifier.fillMaxWidth().padding(top = 6.dp), tone = ButtonTone.Glass, height = 44.dp)
        Spacer(Modifier.height(16.dp))
    }
}
