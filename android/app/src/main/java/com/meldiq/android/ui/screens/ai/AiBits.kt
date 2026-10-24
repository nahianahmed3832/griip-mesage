package com.meldiq.android.ui.screens.ai

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.meldiq.android.R
import com.meldiq.android.data.ai.AgentRules
import com.meldiq.android.data.ai.AiFile
import com.meldiq.android.data.ai.AiJobStates
import com.meldiq.android.data.model.AttachmentPolicy
import com.meldiq.android.ui.LocalContainer
import com.meldiq.android.ui.components.BannerKind
import com.meldiq.android.ui.components.CircleIconButton
import com.meldiq.android.ui.components.SlimProgress
import com.meldiq.android.ui.components.StatusBanner
import com.meldiq.android.ui.components.TagPill
import com.meldiq.android.ui.friendly
import com.meldiq.android.ui.theme.Griip
import com.meldiq.android.ui.theme.Shapes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Small pieces of the AI Workforce screens (as Meldiq Web's AgentBits): an agent's avatar, always with the AI badge
 * so an agent never passes for a person; a job's state; a deliverable that downloads with the session and opens with
 * another app; the notice that AI content isn't end-to-end encrypted.
 */
@Composable
fun AgentAvatar(orgId: String, installId: String, name: String, color: String?, hasAvatar: Boolean, size: Dp = 40.dp) {
    val container = LocalContainer.current
    val context = LocalContext.current
    val image by produceState<ImageBitmap?>(null, orgId, installId, hasAvatar) {
        if (!hasAvatar) return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching {
                val f = File(File(context.cacheDir, "ai-avatars").apply { mkdirs() }, "$orgId-$installId")
                f.delete() // the avatar can change; it's small, so it's fetched each time it's shown
                container.ai.downloadAvatar(orgId, installId, f)
                BitmapFactory.decodeFile(f.path)?.asImageBitmap()
            }.getOrNull()
        }
    }
    val bg = AgentRules.hexColor(color)?.let { Color(it) } ?: Griip.colors.primary
    Box(Modifier.size(size)) {
        Box(Modifier.fillMaxSize().clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
            val img = image
            if (img != null) Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Text(name.trim().take(1).uppercase().ifEmpty { "A" }, color = Color.White, style = Griip.type.titleSmall)
        }
        AiBadge(Modifier.align(Alignment.BottomEnd))
    }
}

/** The "AI" mark beside every agent's name and on its avatar. */
@Composable
fun AiBadge(modifier: Modifier = Modifier) {
    val c = Griip.colors
    Text(
        stringResource(R.string.aw_badge), color = c.onPrimary, fontSize = 9.sp, fontWeight = FontWeight.Bold,
        modifier = modifier.clip(Shapes.pill).background(c.primaryDeep).border(1.dp, c.surface, Shapes.pill).padding(horizontal = 4.dp, vertical = 1.dp),
    )
}

@Composable
fun jobStateLabel(state: String): String = stringResource(
    when (state) {
        AiJobStates.QUEUED -> R.string.aw_state_queued
        AiJobStates.PLANNING -> R.string.aw_state_planning
        AiJobStates.RUNNING -> R.string.aw_state_running
        AiJobStates.AWAITING_INPUT -> R.string.aw_state_input
        AiJobStates.AWAITING_APPROVAL -> R.string.aw_state_approval
        AiJobStates.COMPLETED -> R.string.aw_state_completed
        AiJobStates.FAILED -> R.string.aw_state_failed
        AiJobStates.CANCELLED -> R.string.aw_state_cancelled
        else -> R.string.aw_state_unknown
    },
)

@Composable
fun JobStatePill(state: String) {
    val c = Griip.colors
    val (fg, bg) = when (state) {
        AiJobStates.COMPLETED -> c.success to c.successSoft
        AiJobStates.FAILED -> c.danger to c.dangerSoft
        AiJobStates.CANCELLED -> c.textSecondary to c.surfaceVariant
        AiJobStates.AWAITING_APPROVAL, AiJobStates.AWAITING_INPUT -> c.warning to c.warningSoft
        else -> c.primaryDeep to c.primarySoft
    }
    TagPill(jobStateLabel(state), fg, bg)
}

/** What AI content is, said where it's sent or shown. */
@Composable
fun AiNotice(modifier: Modifier = Modifier, text: String? = null) {
    StatusBanner(text?.takeIf { it.isNotBlank() } ?: stringResource(R.string.aw_notice), BannerKind.Warning, modifier)
}

fun sizeText(n: Long): String = if (n < 1024 * 1024) "${maxOf(1, n / 1024)} KB" else "%.1f MB".format(n / 1024.0 / 1024.0)

/**
 * One file an agent made: name, size, version and approval state; Open and Share download it with the session (the
 * server checks access every time) and hand it to another app. A file that could install or run software isn't opened.
 */
@Composable
fun AiFileRow(orgId: String, file: AiFile, modifier: Modifier = Modifier) {
    val container = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var progress by remember { mutableStateOf<Float?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val c = Griip.colors
    val state = when (file.approvalState) {
        "superseded" -> stringResource(R.string.aw_file_replaced)
        "pending" -> stringResource(R.string.aw_file_pending)
        "approved" -> stringResource(R.string.aw_file_approved)
        "rejected" -> stringResource(R.string.aw_file_rejected)
        else -> null
    }
    val noApp = stringResource(R.string.aw_file_no_app)
    val unsafe = stringResource(R.string.aw_file_unsafe)
    fun fetch(share: Boolean) {
        error = null
        progress = 0f
        scope.launch {
            runCatching { AiFiles.fetch(context, container.ai, orgId, file) { progress = it } }
                .onSuccess { f ->
                    if (AttachmentPolicy.risk(file.name, file.mime, null) != null) error = unsafe
                    else if (!AiFiles.hand(context, f, file.mime, share)) error = noApp
                }
                .onFailure { error = it.friendly() }
            progress = null
        }
    }
    Column(modifier.fillMaxWidth().clip(Shapes.tile).background(c.surfaceVariant).padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(file.name, style = Griip.type.bodyMedium, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val meta = listOfNotNull(sizeText(file.size), stringResource(R.string.aw_file_version, file.version).takeIf { file.version > 1 }, state)
                Text(meta.joinToString(" · "), style = Griip.type.caption, color = c.textSecondary)
            }
            CircleIconButton(Icons.Outlined.OpenInNew, stringResource(R.string.aw_file_open, file.name), { fetch(share = false) }, size = 40.dp, tint = c.primary)
            CircleIconButton(Icons.Outlined.Share, stringResource(R.string.aw_file_share, file.name), { fetch(share = true) }, size = 40.dp, tint = c.textSecondary)
        }
        progress?.let { SlimProgress(it, Modifier.padding(top = 6.dp)) }
        error?.let { Text(it, style = Griip.type.caption, color = c.danger, modifier = Modifier.padding(top = 4.dp)) }
    }
}

/** Where AI files are kept on the phone (the app's cache, shared through its FileProvider only when opened). */
object AiFiles {
    private fun dir(context: Context) = File(context.cacheDir, "shared/ai").apply { mkdirs() }

    /** The file's bytes on the phone: downloaded once (resumable), then reused while the cache keeps them. */
    suspend fun fetch(context: Context, api: com.meldiq.android.data.ai.AiWorkforceApi, orgId: String, file: AiFile, onProgress: (Float) -> Unit): File {
        val safe = file.name.replace(Regex("[^A-Za-z0-9._ -]"), "_").take(120).ifBlank { "file" }
        val out = File(File(dir(context), file.id).apply { mkdirs() }, safe)
        if (out.exists() && out.length() == file.size) return out
        api.download(orgId, file.id, out, onProgress)
        return out
    }

    /** Opens [f] with another app, or offers to share it; false when no app takes it. */
    fun hand(context: Context, f: File, mime: String, share: Boolean): Boolean {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", f)
        val intent = if (share) Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
        else Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
    }
}
