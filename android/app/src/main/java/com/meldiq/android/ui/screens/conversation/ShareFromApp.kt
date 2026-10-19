package com.meldiq.android.ui.screens.conversation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
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
import com.meldiq.android.R
import com.meldiq.android.data.api.AppSummaryDto
import com.meldiq.android.data.api.ResourceDetailsRes
import com.meldiq.android.data.api.ResourceDto
import com.meldiq.android.data.api.SearchAllGroup
import com.meldiq.android.data.model.AttachmentPolicy
import com.meldiq.android.data.model.LinkPreview
import com.meldiq.android.ui.LocalContainer
import com.meldiq.android.ui.TimeFmt
import com.meldiq.android.ui.components.BannerKind
import com.meldiq.android.ui.components.ButtonTone
import com.meldiq.android.ui.components.ChipRow
import com.meldiq.android.ui.components.GlassSheet
import com.meldiq.android.ui.components.ListCard
import com.meldiq.android.ui.components.PillButton
import com.meldiq.android.ui.components.PillChip
import com.meldiq.android.ui.components.SearchPill
import com.meldiq.android.ui.components.StatusBanner
import com.meldiq.android.ui.friendly
import com.meldiq.android.ui.screens.workspace.apps.AppRules
import com.meldiq.android.ui.screens.workspace.apps.AppTile
import com.meldiq.android.ui.theme.Griip
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What a resource is, in a word, for its row. */
private fun kindWord(kind: String): String = when (kind) {
    "folder" -> "Folder"
    "document" -> "Document"
    "spreadsheet" -> "Spreadsheet"
    "presentation" -> "Presentation"
    "workbook" -> "Excel workbook"
    "event" -> "Event"
    "issue" -> "Issue"
    "pull_request" -> "Pull request"
    "page" -> "Page"
    "repository" -> "Repository"
    "site" -> "Site"
    "library" -> "Library"
    else -> "File"
}

/**
 * Sharing an item from a connected app (docs/INTEGRATIONS.md › Sharing): search or browse with the person's own account,
 * then send its name and link as a normal end-to-end encrypted message. Files stay where they are; the provider decides
 * who can open them, and the sheet says so before anything is sent.
 */
@Composable
fun ShareFromAppSheet(orgId: String?, onShare: (text: String, card: LinkPreview?) -> Unit, onDismiss: () -> Unit) {
    val container = LocalContainer.current
    val c = Griip.colors
    val scope = rememberCoroutineScope()
    var apps by remember { mutableStateOf<List<AppSummaryDto>?>(null) }
    var app by remember { mutableStateOf<AppSummaryDto?>(null) }
    var q by remember { mutableStateOf("") }
    var path by remember { mutableStateOf<List<ResourceDto>>(emptyList()) }
    var items by remember { mutableStateOf<List<ResourceDto>>(emptyList()) }
    var cursor by remember { mutableStateOf<String?>(null) }
    var chosen by remember { mutableStateOf<ResourceDto?>(null) }
    var details by remember { mutableStateOf<ResourceDetailsRes?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    LaunchedEffect(orgId) {
        if (orgId == null) {
            apps = emptyList()
            return@LaunchedEffect
        }
        runCatching { container.integrations.directory(orgId) }.onSuccess { d -> apps = AppRules.shareable(d.apps); app = apps?.firstOrNull() }.onFailure { error = it.friendly(); apps = emptyList() }
    }
    val granted = { a: AppSummaryDto, suffix: String -> a.capabilities.any { it.granted && it.key.endsWith(suffix) } }
    // "All apps": one search across every connected app, each with the person's own access.
    var allMode by remember { mutableStateOf(false) }
    var groups by remember { mutableStateOf<List<SearchAllGroup>>(emptyList()) }
    LaunchedEffect(allMode, q) {
        val org = orgId ?: return@LaunchedEffect
        if (!allMode || q.isBlank()) {
            groups = emptyList()
            return@LaunchedEffect
        }
        delay(400)
        loading = true
        runCatching { container.integrations.searchAll(org, q) }.onSuccess { groups = it.results; error = null }.onFailure { error = it.friendly() }
        loading = false
    }
    // Searching or browsing, with a short pause while typing so each key isn't a provider call.
    LaunchedEffect(app?.id, q, path, allMode) {
        if (allMode) return@LaunchedEffect
        val a = app ?: return@LaunchedEffect
        val org = orgId ?: return@LaunchedEffect
        delay(if (q.isBlank()) 0 else 350)
        loading = true
        error = null
        runCatching {
            if (q.isBlank() && granted(a, ".browse")) container.integrations.browse(org, a.id, path.lastOrNull()?.id)
            else container.integrations.search(org, a.id, q)
        }.onSuccess { items = it.items; cursor = it.cursor }.onFailure { items = emptyList(); cursor = null; error = it.friendly() }
        loading = false
    }
    GlassSheet(onDismiss, title = stringResource(R.string.share_app_title)) {
        val list = apps
        if (list != null && list.isEmpty()) {
            StatusBanner(error ?: stringResource(R.string.share_app_none), BannerKind.Info, icon = Icons.Outlined.Info)
            Spacer(Modifier.height(12.dp))
            return@GlassSheet
        }
        ChipRow {
            if (list.orEmpty().size > 1) PillChip(stringResource(R.string.share_app_all), allMode, { allMode = true; chosen = null })
            list.orEmpty().forEach { a -> PillChip(a.name, !allMode && app?.id == a.id, { allMode = false; app = a; path = emptyList(); q = ""; chosen = null }) }
        }
        if (allMode) {
            SearchPill(q, { q = it }, stringResource(R.string.share_app_all), Modifier.padding(vertical = 6.dp))
            error?.let { StatusBanner(it, BannerKind.Warning, Modifier.padding(vertical = 4.dp)) }
            Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
                groups.forEach { g ->
                    Text(g.appName, style = Griip.type.label, color = c.textPrimary, modifier = Modifier.padding(top = 8.dp))
                    g.error?.let { Text(it, style = Griip.type.caption, color = c.warning) }
                    if (g.error == null && g.items.isEmpty()) Text(stringResource(R.string.share_app_empty), style = Griip.type.caption, color = c.textSecondary)
                    g.items.forEach { r ->
                        ListCard(r.title, subtitle = AppRules.subtitle(r, kindWord(r.kind), AttachmentPolicy::sizeLabel, TimeFmt::listStamp), leading = { AppTile(g.icon, "connected", size = 36.dp) }, onClick = {
                            list.orEmpty().firstOrNull { it.id == g.connectorId }?.let { a -> app = a; allMode = false; chosen = r }
                        })
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            return@GlassSheet
        }
        val a = app ?: return@GlassSheet
        val pick = chosen
        if (pick != null) {
            ListCard(pick.title, subtitle = AppRules.subtitle(pick, kindWord(pick.kind), AttachmentPolicy::sizeLabel, TimeFmt::listStamp), leading = { AppTile(a.icon, "connected", size = 40.dp) })
            StatusBanner(stringResource(R.string.share_app_privacy, a.name), BannerKind.Info, Modifier.padding(vertical = 8.dp), icon = Icons.Outlined.Info)
            details?.let { d ->
                d.sections.forEach { s ->
                    Text(s.title, style = Griip.type.label, color = c.textPrimary, modifier = Modifier.padding(top = 6.dp))
                    s.rows.take(20).forEach { r -> Text("${r.label} · ${r.value}", style = Griip.type.caption, color = c.textSecondary) }
                }
            }
            val hasDetails = (pick.kind == "workbook" || pick.kind == "spreadsheet") && (granted(a, "workbook.inspect") || granted(a, "sheets.inspect"))
            if (hasDetails && details == null) PillButton(stringResource(R.string.share_app_details), {
                scope.launch { runCatching { container.integrations.details(orgId!!, a.id, pick.id) }.onSuccess { details = it }.onFailure { error = it.friendly() } }
            }, Modifier.fillMaxWidth().padding(vertical = 4.dp), tone = ButtonTone.Glass, height = 42.dp)
            error?.let { StatusBanner(it, BannerKind.Warning, Modifier.padding(vertical = 4.dp)) }
            val message = AppRules.linkMessage(pick, a.name, AppRules.subtitle(pick, kindWord(pick.kind), AttachmentPolicy::sizeLabel, TimeFmt::listStamp))
            PillButton(stringResource(R.string.share_app_send), { message?.let { (text, card) -> onShare(text, card) } }, Modifier.fillMaxWidth().padding(top = 8.dp), enabled = message != null, height = 50.dp)
            PillButton(stringResource(R.string.common_back), { chosen = null; details = null }, Modifier.fillMaxWidth().padding(top = 8.dp), tone = ButtonTone.Glass, height = 42.dp)
            Spacer(Modifier.height(12.dp))
            return@GlassSheet
        }
        SearchPill(q, { q = it }, stringResource(R.string.share_app_search, a.name), Modifier.padding(vertical = 6.dp))
        if (q.isBlank() && path.isNotEmpty()) ChipRow {
            PillChip(stringResource(R.string.share_app_root), false, { path = emptyList() })
            path.forEachIndexed { i, p -> PillChip(p.title, i == path.lastIndex, { path = path.take(i + 1) }) }
        }
        error?.let { StatusBanner(it, BannerKind.Warning, Modifier.padding(vertical = 4.dp)) }
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
            if (!loading && items.isEmpty()) Text(stringResource(R.string.share_app_empty), style = Griip.type.bodySmall, color = c.textSecondary, modifier = Modifier.padding(12.dp))
            items.forEach { r ->
                ListCard(
                    r.title, subtitle = AppRules.subtitle(r, kindWord(r.kind), AttachmentPolicy::sizeLabel, TimeFmt::listStamp),
                    onClick = { if (r.browsable && q.isBlank()) path = path + r else chosen = r },
                )
            }
            if (cursor != null) PillButton(stringResource(R.string.share_app_more), {
                val org = orgId ?: return@PillButton
                scope.launch {
                    runCatching {
                        if (q.isBlank() && granted(a, ".browse")) container.integrations.browse(org, a.id, path.lastOrNull()?.id, cursor)
                        else container.integrations.search(org, a.id, q, cursor)
                    }.onSuccess { items = items + it.items; cursor = it.cursor }.onFailure { error = it.friendly() }
                }
            }, Modifier.fillMaxWidth().padding(vertical = 8.dp), tone = ButtonTone.Glass, height = 42.dp)
        }
        Spacer(Modifier.height(8.dp))
    }
}
