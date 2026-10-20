package com.meldiq.android.ui.screens.profile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.meldiq.android.data.api.HealthDto
import com.meldiq.android.data.api.HealthRes
import com.meldiq.android.data.api.MeasuredDto
import com.meldiq.android.ui.LocalContainer
import com.meldiq.android.ui.Routes
import com.meldiq.android.ui.components.BannerKind
import com.meldiq.android.ui.components.CircleIconButton
import com.meldiq.android.ui.components.GlassCard
import com.meldiq.android.ui.components.GriipTopBar
import com.meldiq.android.ui.components.SectionHeader
import com.meldiq.android.ui.components.ShimmerList
import com.meldiq.android.ui.components.StatusBanner
import com.meldiq.android.ui.friendly
import com.meldiq.android.ui.theme.Griip
import com.meldiq.android.ui.theme.Space

/**
 * Service health for platform admins (round 7 slice C1, docs/OPERATIONS.md): what the server measures about itself,
 * fetched when the screen opens or Refresh is tapped, never on a timer. A value the server can't measure says so.
 * English only: an operator tool, like the admin CLI.
 */
@Composable
fun ServiceHealthScreen(nav: NavHostController) {
    val container = LocalContainer.current
    var health by remember { mutableStateOf<HealthDto?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var refreshes by remember { mutableIntStateOf(0) }
    LaunchedEffect(refreshes) {
        error = null
        runCatching { container.api.get<HealthRes>("/v1/admin/health").health }
            .onSuccess { health = it }
            .onFailure { error = it.friendly() }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding())) {
        item {
            GriipTopBar("Service health", onBack = { nav.popBackStack() }, actions = {
                CircleIconButton(Icons.Rounded.Refresh, "Refresh", { refreshes++ }, tint = Griip.colors.textSecondary)
            })
        }
        error?.let { e -> item { StatusBanner(e, BannerKind.Error, Modifier.padding(horizontal = Space.gutter, vertical = 6.dp)) } }
        val h = health
        if (h == null) {
            if (error == null) item { ShimmerList(4) }
            return@LazyColumn
        }
        item { Summary(h) }
        item {
            GlassCard(
                Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = 4.dp).clickable { nav.navigate(Routes.CONNECTORS) },
                contentPadding = PaddingValues(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Connectors", style = Griip.type.body.copy(fontWeight = FontWeight.SemiBold), color = Griip.colors.textPrimary)
                        Text("Every outside service, what works and what's missing", style = Griip.type.bodySmall, color = Griip.colors.textSecondary)
                    }
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = Griip.colors.textTertiary)
                }
            }
        }
        item { SectionHeader("Checks", Modifier.padding(top = 12.dp, bottom = 4.dp)) }
        item {
            Card {
                MeasuredLine("Database", h.checks.database) { "ok · ${mb(it.sizeBytes)}" }
                MeasuredLine("MongoDB", h.checks.mongodb) { if (it.ok == true) "ok" else "NOT ANSWERING" }
                MeasuredLine("Calling (LiveKit)", h.checks.livekit) { if (it.configured == true) "configured" else "not configured" }
                MeasuredLine("File storage", h.checks.storage) { "${it.backend} · ${if (it.reachable == true) "reachable" else "UNREACHABLE (${it.errorKind})"}" }
            }
        }
        item { SectionHeader("Load", Modifier.padding(top = 12.dp, bottom = 4.dp)) }
        item {
            val w = h.metrics.window
            Card {
                Line("Requests, last ${w.minutes} min", "${w.requests}")
                Line("Server errors", "${w.serverErrors}" + (w.errorRate?.let { " (${"%.1f".format(it * 100)} %)" } ?: ""))
                Line("Response time, 95th percentile", w.p95Ms?.let { "≤ $it ms" } ?: if (w.requests == 0) "no requests yet" else "over 5 s")
                Line("Event-loop delay, 99th percentile", h.process.eventLoopDelayP99Ms?.let { "$it ms" } ?: "not measured yet")
                Line("Memory", "${mb(h.process.rssBytes)} (heap ${mb(h.process.heapUsedBytes)})")
                Line("Devices online", "${h.realtime.onlineDevices}")
            }
        }
        item { SectionHeader("Queues", Modifier.padding(top = 12.dp, bottom = 4.dp)) }
        item {
            Card {
                Line("Waiting deliveries", "${h.queues.delivery.items}" + (h.queues.delivery.oldestAgeSeconds?.let { " · oldest ${age(it)}" } ?: ""))
                Line("Reminders late (> 5 min)", "${h.queues.reminders.overdueUndelivered}")
                Line("Reminders retrying", "${h.queues.reminders.retrying}")
                MeasuredLine("App jobs", h.queues.integrations) { "${it.jobsQueued} queued · ${it.jobsDead} gave up · ${it.deliveriesFailed24h} failed deliveries (24 h)" }
            }
        }
        if (h.metrics.jobs.isNotEmpty()) {
            item { SectionHeader("Background jobs (since start)", Modifier.padding(top = 12.dp, bottom = 4.dp)) }
            item { Card { h.metrics.jobs.forEach { j -> Line(j.name, "${j.runs} runs · ${j.failures} failed" + (j.lastErrorKind?.let { " · last: $it" } ?: "") + (j.lastDurationMs?.let { " · ${it} ms" } ?: "")) } } }
        }
        item { SectionHeader("Outside services (since start)", Modifier.padding(top = 12.dp, bottom = 4.dp)) }
        item {
            Card {
                if (h.metrics.providers.isEmpty()) Line("No calls yet", "email, push and storage appear after their first use")
                h.metrics.providers.forEach { p -> Line(p.name, "${p.ok} ok · ${p.failed} failed" + (p.lastErrorKind?.let { " · last: $it" } ?: "")) }
            }
        }
        val busy = h.metrics.routes.filter { it.serverErrors > 0 || (it.p95Ms ?: Int.MAX_VALUE) >= 1000 }.take(8)
        if (busy.isNotEmpty()) {
            item { SectionHeader("Failing or slow requests", Modifier.padding(top = 12.dp, bottom = 4.dp)) }
            item { Card { busy.forEach { r -> Line(r.route, "${r.count} · ${r.serverErrors} errors · p95 ${r.p95Ms?.let { "≤ $it" } ?: "> 5000"} ms") } } }
        }
    }
}

@Composable
private fun Summary(h: HealthDto) {
    val c = Griip.colors
    GlassCard(Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = 6.dp), contentPadding = PaddingValues(14.dp)) {
        Text("Meldiq ${h.version} · up ${age(h.uptimeSeconds)}", style = Griip.type.caption, color = c.textSecondary)
        Spacer(Modifier.padding(top = 6.dp))
        if (h.warnings.isEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.CheckCircle, null, tint = c.success)
                Spacer(Modifier.width(8.dp))
                Text("No problems found in what the server measures.", style = Griip.type.body, color = c.textPrimary)
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                h.warnings.forEach { w -> Text("• $w", style = Griip.type.body.copy(fontWeight = FontWeight.SemiBold), color = c.warning) }
            }
        }
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    GlassCard(Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = 4.dp), contentPadding = PaddingValues(12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

@Composable
private fun Line(label: String, value: String) {
    val c = Griip.colors
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = Griip.type.bodySmall, color = c.textSecondary, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        Text(value, style = Griip.type.bodySmall.copy(fontWeight = FontWeight.SemiBold), color = c.textPrimary, modifier = Modifier.weight(1.3f))
    }
}

@Composable
private fun MeasuredLine(label: String, m: MeasuredDto, describe: (MeasuredDto) -> String) =
    Line(label, if (m.available) describe(m) else "not measured: ${m.reason ?: "unavailable"}")

private fun mb(bytes: Long?): String = bytes?.let { "%.1f MB".format(it / 1_048_576.0) } ?: "—"

private fun age(seconds: Long): String = when {
    seconds < 120 -> "${seconds} s"
    seconds < 7200 -> "${seconds / 60} min"
    seconds < 172_800 -> "${seconds / 3600} h"
    else -> "${seconds / 86_400} days"
}
