package com.meldiq.android.ui.screens.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.meldiq.android.R
import com.meldiq.android.ui.components.pressable
import com.meldiq.android.ui.theme.Griip
import com.meldiq.android.ui.theme.Motion
import com.meldiq.android.ui.theme.Shapes
import com.meldiq.android.ui.theme.Space

/** One step of the Get started checklist; [optional] steps never block "all done". */
data class StartStep(val label: String, val done: Boolean, val optional: Boolean = false, val onClick: () -> Unit)

/** Pure rules for the checklist (unit-tested in `HomeStartTest`). */
object StartRules {
    /** Shown until hidden, or until every required step is done. */
    fun visible(dismissed: Boolean, steps: List<StartStep>): Boolean = !dismissed && steps.any { !it.done && !it.optional }

    fun progress(steps: List<StartStep>): Pair<Int, Int> = steps.count { it.done } to steps.size
}

/**
 * Get started (2026-10-19): the first things a new person does in Meldiq, each one a tap away, checked off from real
 * state (a photo, a workspace, a teammate, notifications). Progressive: nothing is forced, every step can be skipped and
 * the card hidden; it goes away by itself once the required steps are done.
 */
@Composable
fun StartChecklist(steps: List<StartStep>, onHide: () -> Unit, modifier: Modifier = Modifier) {
    val c = Griip.colors
    val (done, total) = StartRules.progress(steps)
    val fraction by animateFloatAsState(if (total == 0) 0f else done.toFloat() / total, Motion.standard(), label = "startProgress")
    Column(modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = 6.dp).clip(Shapes.hero).background(c.surface).border(1.dp, c.border, Shapes.hero).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.start_title), style = Griip.type.titleSmall, color = c.textPrimary)
                Text(stringResource(R.string.start_progress, done, total), style = Griip.type.caption, color = c.textSecondary)
            }
            Text(
                stringResource(R.string.start_hide), style = Griip.type.label.copy(fontWeight = FontWeight.SemiBold), color = c.primary,
                modifier = Modifier.heightIn(min = 44.dp).clip(Shapes.pill).pressable(onHide).padding(horizontal = 10.dp, vertical = 12.dp),
            )
        }
        // Progress (the "n of m done" text above says the same for screen readers and in any colour scheme).
        Box(Modifier.padding(top = 6.dp, bottom = 6.dp).fillMaxWidth().height(6.dp).clip(Shapes.pill).background(c.surfaceVariant)) {
            Box(Modifier.fillMaxWidth(fraction).height(6.dp).clip(Shapes.pill).background(c.primary))
        }
        steps.forEach { step -> StepRow(step) }
    }
}

@Composable
private fun StepRow(step: StartStep) {
    val c = Griip.colors
    val doneText = stringResource(R.string.later_tab_done)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(Shapes.small).pressable(if (step.done) null else step.onClick)
            .semantics(mergeDescendants = true) { if (step.done) stateDescription = doneText; contentDescription = step.label }
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(22.dp).clip(CircleShape).background(if (step.done) c.success else c.surface)
                .border(if (step.done) 0.dp else 1.5.dp, if (step.done) c.success else c.outline, CircleShape),
            contentAlignment = Alignment.Center,
        ) { if (step.done) Icon(Icons.Rounded.Check, null, tint = c.surface, modifier = Modifier.size(14.dp)) }
        Spacer(Modifier.width(12.dp))
        Text(
            step.label, style = Griip.type.bodyMedium, color = if (step.done) c.textSecondary else c.textPrimary, modifier = Modifier.weight(1f),
        )
        if (step.optional && !step.done) Text(stringResource(R.string.start_optional), style = Griip.type.caption, color = c.textSecondary, modifier = Modifier.padding(end = 4.dp))
        if (!step.done) Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = c.textSecondary, modifier = Modifier.size(20.dp))
    }
}
