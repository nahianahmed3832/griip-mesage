package com.meldiq.android.ui.screens.workspace

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.meldiq.android.R
import com.meldiq.android.data.api.OrgDetailRes
import com.meldiq.android.data.repo.ServerFeatures
import com.meldiq.android.ui.LocalContainer
import com.meldiq.android.ui.components.GlassCard
import com.meldiq.android.ui.components.SlimProgress
import com.meldiq.android.ui.screens.workspace.apps.SetupStep
import com.meldiq.android.ui.theme.Griip
import com.meldiq.android.ui.theme.Space

/** The workspace's setup steps, each done only when the server's data says so (no fake progress). */
object WorkspaceSetupRules {
    data class Step(val key: String, val done: Boolean)

    fun steps(d: OrgDetailRes, installedApps: Int?): List<Step> = listOfNotNull(
        Step("describe", !d.org.description.isNullOrBlank()),
        Step("photo", d.org.photoRev != null),
        Step("invite", d.stats.members > 1),
        Step("channels", d.stats.channels > 0),
        Step("teams", d.stats.teams > 0),
        installedApps?.let { Step("apps", it > 0) },
    )

    fun progress(steps: List<Step>): Float = if (steps.isEmpty()) 1f else steps.count { it.done }.toFloat() / steps.size
}

/**
 * Setting up a workspace, for the people who can (workspace.edit): a few real steps with honest progress. It disappears
 * once every step is done; nothing here is required.
 */
@Composable
fun WorkspaceSetupCard(d: OrgDetailRes) {
    val container = LocalContainer.current
    val c = Griip.colors
    var installed by remember(d.org.id) { mutableStateOf<Int?>(null) }
    LaunchedEffect(d.org.id) {
        if (d.permissions.canViewApps && container.features.has(ServerFeatures.INTEGRATIONS) == true) {
            runCatching { container.integrations.directory(d.org.id) }.onSuccess { r -> installed = r.apps.count { it.installState == "installed" } }
        }
    }
    val steps = WorkspaceSetupRules.steps(d, installed)
    if (steps.all { it.done }) return
    GlassCard(Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.setup_title), style = Griip.type.section, color = c.textPrimary, modifier = Modifier.weight(1f))
            Text(stringResource(R.string.setup_count, steps.count { it.done }, steps.size), style = Griip.type.caption, color = c.textSecondary)
        }
        Spacer(Modifier.height(8.dp))
        SlimProgress(WorkspaceSetupRules.progress(steps))
        Spacer(Modifier.height(6.dp))
        steps.forEach { s ->
            SetupStep(
                s.done,
                stringResource(
                    when (s.key) {
                        "describe" -> R.string.setup_describe
                        "photo" -> R.string.setup_photo
                        "invite" -> R.string.setup_invite
                        "channels" -> R.string.setup_channels
                        "teams" -> R.string.setup_teams
                        else -> R.string.setup_apps
                    },
                ),
            )
        }
    }
}
