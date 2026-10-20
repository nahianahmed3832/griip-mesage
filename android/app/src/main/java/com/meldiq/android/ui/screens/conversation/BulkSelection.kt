package com.meldiq.android.ui.screens.conversation

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.meldiq.android.R
import com.meldiq.android.data.model.AttachmentPolicy
import com.meldiq.android.ui.components.ButtonTone
import com.meldiq.android.ui.components.CircleIconButton
import com.meldiq.android.ui.components.GlassSheet
import com.meldiq.android.ui.components.PillButton
import com.meldiq.android.ui.media.BulkSave
import com.meldiq.android.ui.media.BulkSave.Status
import com.meldiq.android.ui.theme.Griip

/*
 * Selecting several messages' files and saving them with one action (round 7, docs/ATTACHMENTS.md › Saving several files).
 * Long-press a message with files › Select; then tap messages to add or remove them, and Save. Before saving, the
 * sheet lists every file so single photos of an album can be left out.
 */

/** The header while selecting: close, how many files, select all, save. */
@Composable
fun BulkSelectionBar(files: Int, messages: Int, onClose: () -> Unit, onSelectAll: () -> Unit, onSave: () -> Unit) {
    val c = Griip.colors
    Row(
        Modifier.fillMaxWidth().background(c.surface).statusBarsPadding().height(64.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircleIconButton(Icons.Rounded.Close, stringResource(R.string.bulk_cancel), onClose)
        Column(Modifier.weight(1f).padding(start = 4.dp)) {
            Text(stringResource(R.string.bulk_files_selected, files), style = Griip.type.titleSmall, color = c.textPrimary, maxLines = 1)
            Text(stringResource(R.string.bulk_in_messages, messages), style = Griip.type.caption, color = c.textSecondary, maxLines = 1)
        }
        CircleIconButton(Icons.Outlined.SelectAll, stringResource(R.string.bulk_select_all), onSelectAll, tint = c.textSecondary)
        CircleIconButton(Icons.Outlined.Download, stringResource(R.string.bulk_save), onSave, tint = c.primary, enabled = files > 0)
    }
}

/**
 * While selecting, a message row is one target: a tap toggles it (when it has files) instead of opening what's under
 * the finger, and a check shows on the right. Scrolling still works: a touch that moves past the touch slop isn't a tap.
 */
fun Modifier.bulkSelectable(active: Boolean, checked: Boolean, enabled: Boolean, accent: Color, onToggle: () -> Unit): Modifier {
    if (!active) return this
    return this
        .semantics { selected = checked; role = Role.Checkbox }
        .pointerInput(enabled, checked) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                down.consume()
                val up = waitForUpOrCancellation(PointerEventPass.Initial) ?: return@awaitEachGesture
                val moved = (up.position - down.position).getDistance() > viewConfiguration.touchSlop
                up.consume()
                if (enabled && !moved) onToggle()
            }
        }
        .drawWithContent {
            drawContent()
            if (checked) drawRect(accent.copy(alpha = 0.10f))
            if (!enabled) return@drawWithContent
            val r = 11.dp.toPx()
            val center = Offset(size.width - 22.dp.toPx(), size.height / 2)
            if (checked) {
                drawCircle(accent, r, center)
                val check = Path().apply {
                    moveTo(center.x - r * 0.45f, center.y)
                    lineTo(center.x - r * 0.1f, center.y + r * 0.35f)
                    lineTo(center.x + r * 0.45f, center.y - r * 0.35f)
                }
                drawPath(check, Color.White, style = Stroke(width = 2.dp.toPx()))
            } else {
                drawCircle(accent.copy(alpha = 0.6f), r, center, style = Stroke(width = 2.dp.toPx()))
            }
        }
}

/**
 * Review and progress for one batch. Before saving: every file with a tick (untick to leave it out), then Save to a
 * folder or, when they're all photos and videos, to the gallery. While saving: overall progress and each file's
 * result; afterwards Retry for what failed or was cancelled, and Done.
 */
@Composable
fun BulkSaveSheet(
    plan: BulkSave.Plan,
    candidates: List<BulkSave.Item>,
    excluded: Set<String>,
    onToggle: (String) -> Unit,
    batch: BulkSaver.Batch?,
    onSaveToFolder: () -> Unit,
    onSaveToGallery: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onDone: () -> Unit,
    onClose: () -> Unit,
) {
    val c = Griip.colors
    GlassSheet({ if (batch == null) onClose() else if (!batch.summary.running) onDone() }, title = stringResource(R.string.bulk_sheet_title)) {
        if (batch != null) {
            BatchProgress(batch, onCancel, onRetry, onDone)
            return@GlassSheet
        }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
            items(candidates, key = { it.key }) { item -> ReviewRow(item, item.key !in excluded) { onToggle(item.key) } }
        }
        Spacer(Modifier.height(8.dp))
        when (plan) {
            is BulkSave.Plan.TooMany -> Notice(stringResource(R.string.bulk_too_many, plan.count, plan.limit))
            is BulkSave.Plan.TooLarge -> Notice(stringResource(R.string.bulk_too_large, humanSize(plan.bytes), humanSize(plan.limit)))
            BulkSave.Plan.Empty -> Notice(stringResource(R.string.bulk_nothing))
            is BulkSave.Plan.Ready -> {
                val bytes = plan.items.sumOf { it.ref.size.coerceAtLeast(0) }
                Text(stringResource(R.string.bulk_ready, plan.items.size, humanSize(bytes)), style = Griip.type.caption, color = c.textSecondary)
                Spacer(Modifier.height(10.dp))
                PillButton(stringResource(R.string.bulk_to_folder), onSaveToFolder, Modifier.fillMaxWidth(), height = 48.dp)
                val allMedia = plan.items.all { AttachmentPolicy.kindOfMime(it.ref.mime) != AttachmentPolicy.Kind.FILE }
                if (allMedia && Build.VERSION.SDK_INT >= 29) {
                    Spacer(Modifier.height(8.dp))
                    PillButton(stringResource(R.string.bulk_to_gallery), onSaveToGallery, Modifier.fillMaxWidth(), tone = ButtonTone.Glass, height = 48.dp)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun Notice(text: String) {
    Text(text, style = Griip.type.bodySmall, color = Griip.colors.warning, modifier = Modifier.padding(vertical = 4.dp))
}

@Composable
private fun ReviewRow(item: BulkSave.Item, included: Boolean, onToggle: () -> Unit) {
    val c = Griip.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(included, { onToggle() })
        Icon(kindIcon(item), null, tint = c.textSecondary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(item.fileName, style = Griip.type.bodySmall, color = if (included) c.textPrimary else c.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(humanSize(item.ref.size), style = Griip.type.caption, color = c.textSecondary)
    }
}

@Composable
private fun BatchProgress(batch: BulkSaver.Batch, onCancel: () -> Unit, onRetry: () -> Unit, onDone: () -> Unit) {
    val c = Griip.colors
    val s = batch.summary
    val done = s.saved + s.failed + s.cancelled
    Text(
        when {
            s.running -> stringResource(R.string.bulk_saving, done + 1, s.total)
            s.failed + s.cancelled == 0 -> stringResource(R.string.bulk_saved_all, s.saved)
            else -> stringResource(R.string.bulk_saved_some, s.saved, s.total)
        },
        style = Griip.type.body.copy(fontWeight = FontWeight.SemiBold), color = c.textPrimary,
    )
    Spacer(Modifier.height(8.dp))
    LinearProgressIndicator(progress = { if (s.total == 0) 0f else done.toFloat() / s.total }, modifier = Modifier.fillMaxWidth(), color = c.primary, trackColor = c.surfaceVariant)
    Spacer(Modifier.height(8.dp))
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
        items(batch.items, key = { it.key }) { item -> ProgressRow(item, batch.status[item.key] ?: Status.WAITING, batch.errors[item.key]) }
    }
    Spacer(Modifier.height(10.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (s.running) {
            PillButton(stringResource(R.string.bulk_stop), onCancel, Modifier.weight(1f), tone = ButtonTone.Glass, height = 48.dp)
        } else {
            if (s.retryable > 0) PillButton(stringResource(R.string.bulk_retry, s.retryable), onRetry, Modifier.weight(1f), tone = ButtonTone.Glass, height = 48.dp)
            PillButton(stringResource(R.string.bulk_done), onDone, Modifier.weight(1f), height = 48.dp)
        }
    }
    Spacer(Modifier.height(16.dp))
}

@Composable
private fun ProgressRow(item: BulkSave.Item, status: Status, error: String?) {
    val c = Griip.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
            when (status) {
                Status.SAVING -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = c.primary)
                Status.SAVED -> Icon(Icons.Rounded.CheckCircle, stringResource(R.string.bulk_state_saved), tint = c.success, modifier = Modifier.size(18.dp))
                Status.FAILED -> Icon(Icons.Rounded.ErrorOutline, stringResource(R.string.bulk_state_failed), tint = c.danger, modifier = Modifier.size(18.dp))
                Status.CANCELLED -> Icon(Icons.Rounded.Close, stringResource(R.string.bulk_state_cancelled), tint = c.textTertiary, modifier = Modifier.size(18.dp))
                Status.WAITING -> Icon(kindIcon(item), null, tint = c.textTertiary, modifier = Modifier.size(16.dp))
            }
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(item.fileName, style = Griip.type.bodySmall, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (error != null) Text(error, style = Griip.type.caption, color = c.danger, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

private fun kindIcon(item: BulkSave.Item) = when (AttachmentPolicy.kindOfMime(item.ref.mime)) {
    AttachmentPolicy.Kind.PHOTO -> Icons.Outlined.Image
    AttachmentPolicy.Kind.VIDEO -> Icons.Outlined.Movie
    AttachmentPolicy.Kind.FILE -> Icons.Outlined.Description
}
