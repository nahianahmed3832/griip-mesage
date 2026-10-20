package com.meldiq.android.ui.screens.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.meldiq.android.data.api.ConnectorCheckRes
import com.meldiq.android.data.api.ConnectorDto
import com.meldiq.android.data.api.ConnectorsRes
import com.meldiq.android.ui.LocalContainer
import com.meldiq.android.ui.components.BannerKind
import com.meldiq.android.ui.components.ButtonTone
import com.meldiq.android.ui.components.CircleIconButton
import com.meldiq.android.ui.components.GlassCard
import com.meldiq.android.ui.components.GriipTopBar
import com.meldiq.android.ui.components.PillButton
import com.meldiq.android.ui.components.SectionHeader
import com.meldiq.android.ui.components.ShimmerList
import com.meldiq.android.ui.components.StatusBanner
import com.meldiq.android.ui.friendly
import com.meldiq.android.ui.theme.Griip
import com.meldiq.android.ui.theme.Space
import kotlinx.coroutines.launch

/**
 * The connector register for platform admins (docs/CONNECTORS.md): every outside service with the state the server
 * measured, the names of settings still missing (never values) and what the owner has to do. "Check" runs only the
 * checks that send nothing to anyone and cost nothing. English only, like Service health.
 */
@Composable
fun ConnectorsScreen(nav: NavHostController) {
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf<List<ConnectorDto>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var refreshes by remember { mutableIntStateOf(0) }
    val checking = remember { mutableStateMapOf<String, Boolean>() }
    val results = remember { mutableStateMapOf<String, String>() }
    LaunchedEffect(refreshes) {
        error = null
        runCatching { container.api.get<ConnectorsRes>("/v1/admin/connectors").connectors }
            .onSuccess { list = it }
            .onFailure { error = it.friendly() }
    }
    val check: (String) -> Unit = { id ->
        checking[id] = true
        scope.launch {
            runCatching { container.api.post<ConnectorCheckRes>("/v1/admin/connectors/$id/check") }
                .onSuccess { r ->
                    list = r.connectors
                    results[id] = if (r.result.ok) "Check passed just now." else "Check failed: ${r.result.errorKind ?: "error"}."
                }
                .onFailure { results[id] = it.friendly() }
            checking[id] = false
        }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding())) {
        item {
            GriipTopBar("Connectors", onBack = { nav.popBackStack() }, actions = {
                CircleIconButton(Icons.Rounded.Refresh, "Refresh", { refreshes++ }, tint = Griip.colors.textSecondary)
            })
        }
        error?.let { e -> item { StatusBanner(e, BannerKind.Error, Modifier.padding(horizontal = Space.gutter, vertical = 6.dp)) } }
        val all = list
        if (all == null) {
            if (error == null) item { ShimmerList(5) }
            return@LazyColumn
        }
        item {
            Text(
                ConnectorRules.summary(all),
                style = Griip.type.bodySmall,
                color = Griip.colors.textSecondary,
                modifier = Modifier.padding(horizontal = Space.gutter, vertical = 6.dp),
            )
        }
        ConnectorRules.sections(all).forEach { (state, items) ->
            item(key = "h-$state") { SectionHeader("${ConnectorRules.label(state)} (${items.size})", Modifier.padding(top = 12.dp, bottom = 4.dp)) }
            items(items, key = { it.id }) { c ->
                ConnectorCard(c, checking[c.id] == true, results[c.id]) { check(c.id) }
            }
        }
    }
}

@Composable
private fun ConnectorCard(c: ConnectorDto, busy: Boolean, result: String?, onCheck: () -> Unit) {
    val colors = Griip.colors
    val dot: Color = when (ConnectorRules.tone(c.state)) {
        ConnectorRules.Tone.Good -> colors.success
        ConnectorRules.Tone.Bad -> colors.danger
        ConnectorRules.Tone.Warning -> colors.warning
        ConnectorRules.Tone.Neutral -> colors.primary
        ConnectorRules.Tone.Muted -> colors.textTertiary
    }
    GlassCard(Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = 4.dp), contentPadding = PaddingValues(12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(dot, CircleShape))
                Spacer(Modifier.width(8.dp))
                Text(c.name, style = Griip.type.body.copy(fontWeight = FontWeight.SemiBold), color = colors.textPrimary, modifier = Modifier.weight(1f))
                Text(c.category, style = Griip.type.caption, color = colors.textTertiary)
            }
            if (c.detail.isNotBlank()) Text(c.detail, style = Griip.type.bodySmall, color = colors.textSecondary)
            ConnectorRules.usage(c.usage)?.let { Text(it, style = Griip.type.caption, color = colors.textSecondary) }
            c.lastErrorKind?.takeIf { c.state == "failing" }?.let { Text("Last error: $it", style = Griip.type.caption, color = colors.danger) }
            if (c.missing.isNotEmpty()) Text("Missing: ${c.missing.joinToString(", ")}", style = Griip.type.caption, color = colors.warning)
            c.ownerAction?.let { Text(it, style = Griip.type.caption, color = colors.textSecondary) }
            result?.let { Text(it, style = Griip.type.caption.copy(fontWeight = FontWeight.SemiBold), color = colors.textPrimary) }
            if (c.checkable) {
                PillButton("Check", onCheck, Modifier.padding(top = 4.dp), tone = ButtonTone.Glass, loading = busy, height = 40.dp)
            }
        }
    }
}
