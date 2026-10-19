package com.meldiq.android.ui.screens.conversation

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meldiq.android.R
import com.meldiq.android.data.model.Sticker
import com.meldiq.android.data.model.StickerCatalog
import com.meldiq.android.data.model.StickerRef
import com.meldiq.android.ui.components.SearchPill
import com.meldiq.android.ui.theme.Griip
import com.meldiq.android.ui.theme.Shapes
import com.meldiq.android.ui.theme.rememberReducedMotion
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** One cycle of a sticker's motion. */
private const val CYCLE_MS = 1200
/** Cycles played when a sticker appears (or is tapped); then it rests. Nothing loops while idle. */
private const val CYCLES = 2
private const val CONFETTI_PIECES = 14
private const val BURST_RAYS = 8
/** Below this size (the picker grid) a sticker's caption uses smaller type and padding so it fits. */
private const val COMPACT_STICKER_DP = 110f

/**
 * A sticker tone's two colours: a deep one that carries white caption text (4.5:1 or better) and the same hue for the
 * soft shape behind the emoji.
 */
private fun toneColor(tone: String): Color = when (tone) {
    "indigo" -> Color(0xFF4338CA)
    "violet" -> Color(0xFF6D28D9)
    "rose" -> Color(0xFFBE123C)
    "amber" -> Color(0xFFB45309)
    "green" -> Color(0xFF047857)
    "teal" -> Color(0xFF0F766E)
    "sky" -> Color(0xFF0369A1)
    else -> Color(0xFF334155)
}

/**
 * A Meldiq sticker drawn from its spec (docs/STICKERS.md): the emoji over a soft shape in its tone, the caption on a
 * tilted label, and its motion played a couple of times when it appears or is tapped. Still when [animate] is off or
 * the person turned animations off. An unknown sticker (from a newer catalogue) shows [fallback] instead.
 */
@Composable
fun StickerView(sticker: Sticker?, fallback: String, modifier: Modifier = Modifier, size: Dp = 132.dp, animate: Boolean = true) {
    val c = Griip.colors
    if (sticker == null) {
        Text(fallback, style = Griip.type.body, color = c.textPrimary, modifier = modifier)
        return
    }
    val spec = sticker.spec
    val tone = toneColor(spec.tone)
    val reduced = rememberReducedMotion()
    val playing = animate && !reduced
    var plays by remember { mutableIntStateOf(0) }
    val progress = remember(sticker.ref) { Animatable(if (playing) 0f else 1f) }
    LaunchedEffect(sticker.ref, plays, playing) {
        if (!playing) return@LaunchedEffect
        repeat(if (spec.motion == "spin" || spec.motion == "stamp" || spec.motion == "pop") 1 else CYCLES) {
            progress.snapTo(0f)
            progress.animateTo(1f, tween(CYCLE_MS, easing = LinearEasing))
        }
    }
    val p = progress.value
    val described = stringResource(R.string.sticker_described, spec.caption)
    Box(
        modifier.size(size).semantics { contentDescription = described }
            .then(if (playing) Modifier.clickable(role = Role.Image) { plays++ } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        val unit = size.value
        // The soft shape behind the emoji, and the particles of the confetti and burst motions.
        Canvas(Modifier.fillMaxSize()) {
            val r = this.size.minDimension * 0.36f
            val center = Offset(this.size.width / 2, this.size.height * 0.44f)
            drawCircle(tone.copy(alpha = if (c.isDark) 0.30f else 0.16f), r, center)
            if (!playing || p >= 1f) return@Canvas
            when (spec.motion) {
                "confetti" -> repeat(CONFETTI_PIECES) { i ->
                    val angle = (i.toFloat() / CONFETTI_PIECES) * 2 * PI.toFloat() + i * 0.37f
                    val dist = r * (0.6f + 1.1f * p)
                    val at = Offset(center.x + cos(angle) * dist, center.y + sin(angle) * dist + 20f * p * p)
                    val colors = listOf(tone, Color(0xFFF59E0B), Color(0xFF10B981), Color(0xFF3B82F6), Color(0xFFEC4899))
                    drawRect(colors[i % colors.size].copy(alpha = 1f - p), at, Size(r * 0.12f, r * 0.2f))
                }
                "burst" -> repeat(BURST_RAYS) { i ->
                    val angle = (i.toFloat() / BURST_RAYS) * 2 * PI.toFloat()
                    val from = r * (0.9f + 0.3f * p)
                    val to = from + r * 0.35f * (1f - p)
                    drawLine(
                        tone.copy(alpha = 1f - p), Offset(center.x + cos(angle) * from, center.y + sin(angle) * from),
                        Offset(center.x + cos(angle) * to, center.y + sin(angle) * to), strokeWidth = r * 0.08f, cap = StrokeCap.Round,
                    )
                }
            }
        }
        val m = motion(spec.motion, if (playing) p else 1f)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                spec.emoji, fontSize = (unit * 0.42f).sp,
                modifier = Modifier.graphicsLayer {
                    scaleX = m.scale
                    scaleY = m.scale
                    rotationZ = m.rotation
                    translationX = m.dx * density
                    translationY = m.dy * density
                    transformOrigin = if (spec.motion == "sway") TransformOrigin(0.5f, 1f) else TransformOrigin.Center
                },
            )
            val compact = unit < COMPACT_STICKER_DP
            Text(
                spec.caption,
                style = Griip.type.label.copy(
                    fontWeight = FontWeight.ExtraBold, fontSize = (unit * if (compact) 0.085f else 0.1f).coerceIn(10f, 16f).sp,
                    lineHeight = (unit * if (compact) 0.1f else 0.12f).coerceIn(12f, 19f).sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                ),
                color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.graphicsLayer { rotationZ = -3f }.clip(Shapes.pill).background(tone)
                    .padding(horizontal = if (compact) 6.dp else 10.dp, vertical = 3.dp),
            )
        }
    }
}

private data class MotionFrame(val scale: Float = 1f, val rotation: Float = 0f, val dx: Float = 0f, val dy: Float = 0f)

/** Where a motion is at progress [p] (0…1 of one cycle); 1 is always the resting pose. */
private fun motion(kind: String, p: Float): MotionFrame {
    val wave = sin(p * 2 * PI.toFloat())
    return when (kind) {
        "stamp" -> if (p < 0.3f) MotionFrame(scale = 1.6f - 0.6f * (p / 0.3f), rotation = -8f + 5f * (p / 0.3f)) else MotionFrame(scale = 1f + 0.05f * sin((p - 0.3f) / 0.7f * PI.toFloat()) * (1f - p), rotation = -3f * (1f - p))
        "rise" -> MotionFrame(dy = -14f * sin(p * PI.toFloat()), scale = 1f + 0.06f * sin(p * PI.toFloat()))
        "bounce" -> MotionFrame(dy = -10f * abs(sin(p * 2 * PI.toFloat())))
        "pulse" -> MotionFrame(scale = 1f + 0.12f * wave * wave)
        "wiggle" -> MotionFrame(rotation = 10f * sin(p * 4 * PI.toFloat()) * (1f - p * 0.5f))
        "pop" -> if (p < 0.25f) MotionFrame(scale = 0.6f + 0.55f * (p / 0.25f)) else MotionFrame(scale = 1.15f - 0.15f * ((p - 0.25f) / 0.75f))
        "float" -> MotionFrame(dy = -6f * wave, rotation = 3f * wave)
        "sway" -> MotionFrame(rotation = 9f * wave)
        "shake" -> MotionFrame(dx = 6f * sin(p * 8 * PI.toFloat()) * (1f - p))
        "spin" -> MotionFrame(rotation = 360f * p)
        "confetti", "burst" -> MotionFrame(scale = 1f + 0.1f * sin(p * PI.toFloat()))
        else -> MotionFrame()
    }
}

/**
 * The Stickers tab of the emoji sheet: search, favourites, recently used, then each pack. Tap sends; long-press adds or
 * removes a favourite. Shown still in the grid (cheaper, calmer); they move once sent.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun StickerPicker(recent: List<StickerRef>, favourites: List<StickerRef>, onSend: (Sticker) -> Unit, onToggleFavourite: (Sticker) -> Unit) {
    val c = Griip.colors
    var query by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf("") }
    SearchPill(query, { query = it }, stringResource(R.string.stickers_search))
    Spacer(Modifier.height(8.dp))
    @Composable
    fun grid(items: List<Sticker>) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val cell = (maxWidth - 6.dp * 3) / 4
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items.forEach { s ->
                    Box(
                        Modifier.size(cell).clip(Shapes.tile).background(c.surfaceVariant)
                            .combinedClickable(onClick = { onSend(s) }, onLongClick = { onToggleFavourite(s) }, onClickLabel = s.spec.caption, onLongClickLabel = stringResource(R.string.stickers_favourites)),
                        contentAlignment = Alignment.Center,
                    ) { StickerView(s, s.fallback, size = cell, animate = false) }
                }
            }
        }
    }
    @Composable
    fun header(text: String) = Text(text, style = Griip.type.label.copy(fontWeight = FontWeight.SemiBold), color = c.textSecondary, modifier = Modifier.padding(top = 10.dp, bottom = 6.dp))
    if (query.isNotBlank()) {
        val found = StickerCatalog.search(query)
        if (found.isEmpty()) Text(stringResource(R.string.stickers_none), style = Griip.type.bodySmall, color = c.textSecondary, modifier = Modifier.padding(vertical = 12.dp))
        else grid(found)
        return
    }
    val favs = favourites.mapNotNull { StickerCatalog.find(it) }
    if (favs.isNotEmpty()) {
        header(stringResource(R.string.stickers_favourites))
        grid(favs)
    }
    val recents = recent.mapNotNull { StickerCatalog.find(it) }.take(8)
    if (recents.isNotEmpty()) {
        header(stringResource(R.string.stickers_recent))
        grid(recents)
    }
    StickerCatalog.packs.forEach { pack ->
        header(pack.name)
        grid(pack.stickers.map { Sticker(pack, it) })
    }
    if (favs.isEmpty()) Text(stringResource(R.string.stickers_fav_hint), style = Griip.type.caption, color = c.textSecondary, modifier = Modifier.padding(top = 10.dp))
}
