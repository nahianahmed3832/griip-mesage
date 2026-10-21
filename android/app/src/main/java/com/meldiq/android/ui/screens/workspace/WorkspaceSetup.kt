package com.meldiq.android.ui.screens.workspace

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.meldiq.android.R
import com.meldiq.android.data.api.OrgDetailRes
import com.meldiq.android.ui.components.GlassCard
import com.meldiq.android.ui.components.SlimProgress
import com.meldiq.android.ui.theme.Griip
import com.meldiq.android.ui.theme.Space

/** The community's setup steps, each done only when the server's data says so (no fake progress). */
object WorkspaceSetupRules {
    data class Step(val key: String, val done: Boolean)

    fun steps(d: OrgDetailRes): List<Step> = listOf(
        Step("describe", !d.org.description.isNullOrBlank()),
        Step("photo", d.org.photoRev != null),
        Step("rules", !d.org.rules.isNullOrBlank()),
        Step("invite", d.stats.members > 1),
        Step("channels", d.stats.channels > 0),
    )

    fun progress(steps: List<Step>): Float = if (steps.isEmpty()) 1f else steps.count { it.done }.toFloat() / steps.size
}

/**
 * Setting up a community, for the people who can (workspace.edit): a few real steps with honest progress. It disappears
 * once every step is done; nothing here is required.
 */
@Composable
fun WorkspaceSetupCard(d: OrgDetailRes) {
    val c = Griip.colors
    val steps = WorkspaceSetupRules.steps(d)
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
                when (s.key) {
                    "describe" -> stringResource(R.string.setup_describe)
                    "photo" -> stringResource(R.string.setup_photo)
                    "rules" -> stringResource(R.string.cs_rules)
                    "invite" -> stringResource(R.string.setup_invite)
                    else -> stringResource(R.string.setup_channels)
                },
            )
        }
    }
}

@Composable
private fun SetupStep(done: Boolean, title: String) {
    val c = Griip.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (done) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked, null, tint = if (done) c.success else c.textTertiary)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) { Text(title, style = Griip.type.bodyMedium, color = if (done) c.textPrimary else c.textSecondary) }
    }
}
