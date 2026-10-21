package com.meldiq.android.ui.screens.community

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.EmojiEmotions
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.meldiq.android.R
import com.meldiq.android.data.api.ExpressionDto
import com.meldiq.android.data.api.ExpressionLimitsDto
import com.meldiq.android.data.model.ExpressionRules
import com.meldiq.android.ui.LocalContainer
import com.meldiq.android.ui.components.BannerKind
import com.meldiq.android.ui.components.ButtonTone
import com.meldiq.android.ui.components.CircleIconButton
import com.meldiq.android.ui.components.CustomEmojiImage
import com.meldiq.android.ui.components.EmptyState
import com.meldiq.android.ui.components.GlassSheet
import com.meldiq.android.ui.components.GriipTextField
import com.meldiq.android.ui.components.GriipTopBar
import com.meldiq.android.ui.components.ListCard
import com.meldiq.android.ui.components.PillButton
import com.meldiq.android.ui.components.SectionHeader
import com.meldiq.android.ui.components.StatusBanner
import com.meldiq.android.ui.friendly
import com.meldiq.android.ui.theme.Griip
import com.meldiq.android.ui.theme.Space
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * A community's own emoji and stickers (batch B9, Discord reference EXPR-CTRL-002), for people with
 * `expressions.manage`: upload (a picture is shrunk to fit: 128 px emoji, 320 px stickers, at most 256 KB), rename,
 * remove. Animated GIF, PNG and WebP files small enough are kept as they are.
 */
@Composable
fun ExpressionsScreen(nav: NavHostController, orgId: String) {
    val container = LocalContainer.current
    val c = Griip.colors
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val lists by container.expressions.lists.collectAsStateWithLifecycle()
    var limits by remember { mutableStateOf(ExpressionLimitsDto()) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var pending by remember { mutableStateOf<Pair<String, ByteArray>?>(null) } // kind, image
    var pendingName by remember { mutableStateOf("") }
    var renaming by remember { mutableStateOf<ExpressionDto?>(null) }
    var pickKind by remember { mutableStateOf("emoji") }
    LaunchedEffect(reload) { runCatching { container.expressions.refresh(orgId) }.onSuccess { limits = it.limits; error = null }.onFailure { error = it.friendly() } }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            val bytes = withContext(Dispatchers.IO) { prepare(ctx, uri, pickKind) }
            if (bytes == null) error = ctx.getString(R.string.b9_bad_image) else {
                pending = pickKind to bytes
                pendingName = ExpressionRules.suggestName(uri.lastPathSegment.orEmpty()).takeIf { ExpressionRules.NAME.matches(it) }.orEmpty()
            }
        }
    }
    val all = lists[orgId].orEmpty()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item { GriipTopBar(stringResource(R.string.b9_title), onBack = { nav.popBackStack() }) }
        item { Text(stringResource(R.string.b9_intro), style = Griip.type.bodySmall, color = c.textSecondary, modifier = Modifier.padding(horizontal = Space.gutter, vertical = 6.dp)) }
        error?.let { item { StatusBanner(it, BannerKind.Warning, Modifier.padding(horizontal = Space.gutter, vertical = 4.dp)) } }
        for ((kind, label, max) in listOf(Triple("emoji", R.string.b9_emoji, limits.emoji), Triple("sticker", R.string.stickers_tab, limits.sticker))) {
            val mine = all.filter { it.kind == kind }
            item(key = "head-$kind") {
                SectionHeader(
                    stringResource(label) + " · ${mine.size}/$max", Modifier.padding(top = 14.dp),
                    action = stringResource(R.string.b9_upload).takeIf { mine.size < max },
                    onAction = {
                        pickKind = kind
                        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                )
            }
            if (mine.isEmpty()) item(key = "empty-$kind") { EmptyState(Icons.Outlined.EmojiEmotions, stringResource(R.string.b9_none), "") }
            items(mine, key = { it.id }) { e ->
                ListCard(
                    ":${e.name}:", Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    subtitle = e.createdBy?.let { stringResource(R.string.b9_by, it) },
                    leading = { CustomEmojiImage(container.expressions.ref(e), if (kind == "emoji") 36.dp else 56.dp) },
                    trailing = {
                        CircleIconButton(Icons.Outlined.DeleteOutline, stringResource(R.string.b9_remove), {
                            scope.launch { runCatching { container.expressions.delete(e) }.onSuccess { reload++ }.onFailure { error = it.friendly() } }
                        }, size = 36.dp, tint = c.danger)
                    },
                    onClick = { renaming = e },
                )
            }
        }
    }
    pending?.let { (kind, bytes) ->
        GlassSheet({ pending = null }, title = stringResource(if (kind == "emoji") R.string.b9_new_emoji else R.string.b9_new_sticker)) {
            GriipTextField(pendingName, { pendingName = it.lowercase().take(32) }, stringResource(R.string.b9_name), prefix = ":", helper = stringResource(R.string.b9_name_help))
            Spacer(Modifier.height(12.dp))
            PillButton(stringResource(R.string.b9_upload), {
                val name = pendingName.trim()
                scope.launch {
                    runCatching { container.expressions.upload(orgId, kind, name, bytes) }
                        .onSuccess { pending = null; reload++ }
                        .onFailure { error = it.friendly(); pending = null }
                }
            }, Modifier.fillMaxWidth().padding(bottom = 8.dp), enabled = ExpressionRules.NAME.matches(pendingName.trim()), height = 50.dp)
        }
    }
    renaming?.let { e ->
        var name by remember(e.id) { mutableStateOf(e.name) }
        GlassSheet({ renaming = null }, title = stringResource(R.string.b9_rename)) {
            GriipTextField(name, { name = it.lowercase().take(32) }, stringResource(R.string.b9_name), prefix = ":", helper = stringResource(R.string.b9_name_help))
            Spacer(Modifier.height(12.dp))
            PillButton(stringResource(R.string.ja_save), {
                scope.launch { runCatching { container.expressions.rename(e, name.trim()) }.onSuccess { renaming = null; reload++ }.onFailure { error = it.friendly(); renaming = null } }
            }, Modifier.fillMaxWidth().padding(bottom = 8.dp), enabled = ExpressionRules.NAME.matches(name.trim()), height = 50.dp)
        }
    }
}

/**
 * The picked image as it will be uploaded: a PNG, GIF or WebP small enough stays as it is (animation kept); anything
 * else is decoded, shrunk to [kind]'s size and written as PNG (or WebP when PNG is too big). Null when it can't be read.
 */
private fun prepare(ctx: Context, uri: Uri, kind: String): ByteArray? {
    val raw = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
    val isKept = raw.size <= ExpressionRules.MAX_BYTES && (raw.startsWithBytes(PNG) || raw.startsWithBytes("GIF8".toByteArray()) || (raw.size > 12 && String(raw, 8, 4, Charsets.ISO_8859_1) == "WEBP"))
    if (isKept) return raw
    val bmp = BitmapFactory.decodeByteArray(raw, 0, raw.size) ?: return null
    val max = if (kind == "emoji") 128 else 320
    val scale = minOf(1f, max.toFloat() / maxOf(bmp.width, bmp.height))
    val scaled = Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt().coerceAtLeast(1), (bmp.height * scale).toInt().coerceAtLeast(1), true)
    val png = ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    if (png.size <= ExpressionRules.MAX_BYTES) return png
    @Suppress("DEPRECATION")
    val webp = ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.WEBP, 85, it) }.toByteArray()
    return webp.takeIf { it.size <= ExpressionRules.MAX_BYTES }
}

private val PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
private fun ByteArray.startsWithBytes(prefix: ByteArray) = size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }
