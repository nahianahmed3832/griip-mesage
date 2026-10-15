package com.griipbuddy.message.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.griipbuddy.message.ui.theme.AvatarGradients
import com.griipbuddy.message.ui.theme.Griip
import com.griipbuddy.message.ui.theme.Shapes
import com.griipbuddy.message.ui.theme.Space

fun initialsOf(name: String): String {
    val parts = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    return when {
        parts.isEmpty() -> "?"
        parts.size == 1 -> parts[0].take(2).uppercase()
        else -> (parts[0].take(1) + parts.last().take(1)).uppercase()
    }
}

/** Circle avatar: initials on a deterministic gradient, white ring, optional presence dot. */
@Composable
fun Avatar(
    name: String,
    colorIndex: Int,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    online: Boolean? = null,
    ring: Boolean = true,
) {
    val c = Griip.colors
    val (a, b) = AvatarGradients[((colorIndex % AvatarGradients.size) + AvatarGradients.size) % AvatarGradients.size]
    Box(modifier.size(size).semantics(mergeDescendants = true) { contentDescription = "$name avatar" }) {
        Box(
            Modifier.size(size)
                .shadow(if (ring) 6.dp else 0.dp, CircleShape, ambientColor = c.shadow, spotColor = c.shadow)
                .clip(CircleShape)
                .background(Brush.linearGradient(listOf(a, b)))
                .then(if (ring) Modifier.border(2.dp, Color.White.copy(alpha = if (c.isDark) 0.25f else 0.95f), CircleShape) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                initialsOf(name),
                color = Color.White,
                style = Griip.type.titleSmall.copy(fontSize = (size.value * 0.36f).sp, fontWeight = FontWeight.Bold),
                maxLines = 1,
            )
        }
        if (online != null) {
            val dot = (size.value * 0.26f).coerceIn(10f, 16f).dp
            Box(
                Modifier.align(Alignment.BottomEnd).size(dot).clip(CircleShape)
                    .background(if (online) c.success else c.textTertiary)
                    .border(2.dp, if (c.isDark) c.canvasMid else Color.White, CircleShape),
            )
        }
    }
}

/** Rounded-square mark for orgs, branches, teams and channels. */
@Composable
fun MarkTile(label: String, colorIndex: Int, modifier: Modifier = Modifier, size: Dp = 48.dp) {
    val (a, b) = AvatarGradients[((colorIndex % AvatarGradients.size) + AvatarGradients.size) % AvatarGradients.size]
    Box(
        modifier.size(size).clip(Shapes.iconTile).background(Brush.linearGradient(listOf(a, b))),
        contentAlignment = Alignment.Center,
    ) {
        Text(initialsOf(label), color = Color.White, style = Griip.type.titleSmall.copy(fontSize = (size.value * 0.34f).sp, fontWeight = FontWeight.Bold))
    }
}

/** Tinted rounded tile holding a line icon (reference quick-action tiles). */
@Composable
fun IconTile(icon: ImageVector, tint: Color, background: Color, modifier: Modifier = Modifier, size: Dp = 44.dp) {
    Box(modifier.size(size).clip(Shapes.iconTile).background(background), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(size * 0.5f))
    }
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, action: String? = null, onAction: (() -> Unit)? = null) {
    val c = Griip.colors
    Row(modifier.fillMaxWidth().padding(horizontal = Space.gutter), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = Griip.type.section, color = c.textPrimary, modifier = Modifier.weight(1f))
        if (action != null && onAction != null) {
            Text(
                action,
                style = Griip.type.label,
                color = c.primaryDeep,
                modifier = Modifier.clip(Shapes.pill).pressable(onAction).padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
fun CountBadge(count: Int, modifier: Modifier = Modifier, muted: Boolean = false) {
    if (count <= 0) return
    val c = Griip.colors
    Box(
        modifier.height(20.dp).clip(Shapes.pill).background(if (muted) c.textTertiary else c.actionEnd).padding(horizontal = 7.dp),
        contentAlignment = Alignment.Center,
    ) { Text(if (count > 99) "99+" else "$count", style = Griip.type.caption.copy(fontWeight = FontWeight.Bold), color = Color.White) }
}

@Composable
fun TagPill(text: String, color: Color, background: Color, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    Row(
        modifier.clip(Shapes.pill).background(background).padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = color, modifier = Modifier.size(12.dp))
            Spacer(Modifier.width(4.dp))
        }
        Text(text, style = Griip.type.caption, color = color, maxLines = 1)
    }
}

@Composable
fun RoleBadge(role: String?, modifier: Modifier = Modifier) {
    if (role == null) return
    val c = Griip.colors
    val (fg, bg) = when (role.uppercase()) {
        "OWNER" -> c.peach to c.peachSoft
        "ADMIN" -> c.lavender to c.lavenderSoft
        "MANAGER", "LEAD" -> c.primaryDeep to c.primarySoft
        "GUEST" -> c.warning to c.warningSoft
        else -> c.textSecondary to c.hairline
    }
    val label = when (role.uppercase()) {
        "MANAGER" -> "Branch manager"
        "LEAD" -> "Team lead"
        else -> role.lowercase().replaceFirstChar { it.uppercase() }
    }
    TagPill(label, fg, bg, modifier)
}

/** Ring gauge (reference "Dental Health Score 85%"). */
@Composable
fun RingProgress(progress: Float, modifier: Modifier = Modifier, size: Dp = 76.dp, stroke: Dp = 8.dp, label: String) {
    val c = Griip.colors
    val p by animateFloatAsState(progress.coerceIn(0f, 1f), tween(700), label = "ring")
    Box(modifier.size(size).semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val sw = stroke.toPx()
            val arc = Size(this.size.width - sw, this.size.height - sw)
            val tl = Offset(sw / 2, sw / 2)
            drawArc(c.primarySoft, 0f, 360f, false, tl, arc, style = Stroke(sw))
            drawArc(Brush.sweepGradient(listOf(c.primaryBright, c.primary, c.primaryDeep, c.primaryBright)), -90f, 360f * p, false, tl, arc, style = Stroke(sw, cap = StrokeCap.Round))
        }
        Text(label, style = Griip.type.titleSmall, color = c.textPrimary)
    }
}

@Composable
fun SlimProgress(progress: Float, modifier: Modifier = Modifier, color: Color = Griip.colors.primary) {
    val c = Griip.colors
    val p by animateFloatAsState(progress.coerceIn(0f, 1f), tween(400), label = "bar")
    Box(modifier.fillMaxWidth().height(6.dp).clip(Shapes.pill).background(c.primarySoft)) {
        Box(Modifier.fillMaxWidth(p).height(6.dp).clip(Shapes.pill).background(Brush.horizontalGradient(listOf(c.primaryBright, color))))
    }
}

/** Three-up stat tile (reference: Cavities / Plaque / Gum Health). */
@Composable
fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    val c = Griip.colors
    GlassCard(modifier, shape = Shapes.tile, elevation = 6.dp, contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 14.dp, horizontal = 10.dp)) {
        Text(label, style = Griip.type.caption, color = c.textTertiary, maxLines = 1, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        Text(value, style = Griip.type.headline, color = c.textPrimary, maxLines = 1, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
    }
}

/** Card row: leading visual, title, subtitle, trailing slot (reference Treatments list). */
@Composable
fun ListCard(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    meta: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    selected: Boolean = false,
) {
    val c = Griip.colors
    GlassCard(modifier.fillMaxWidth(), onClick = onClick, selected = selected, contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (leading != null) {
                leading()
                Spacer(Modifier.width(14.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = Griip.type.titleSmall, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(subtitle, style = Griip.type.bodySmall, color = c.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (meta != null) {
                    Spacer(Modifier.height(6.dp))
                    meta()
                }
            }
            if (trailing != null) {
                Spacer(Modifier.width(10.dp))
                trailing()
            }
        }
    }
}

/** Settings/menu row (reference "My Profile" list): tinted icon, label, chevron/switch/value. */
@Composable
fun MenuRow(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    tint: Color = Griip.colors.primary,
    tintBg: Color = Griip.colors.primarySoft,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = Griip.colors.textTertiary) },
) {
    val c = Griip.colors
    Row(
        modifier.fillMaxWidth().clip(Shapes.tile).pressable(onClick).padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile(icon, tint, tintBg, size = 38.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = Griip.type.bodyMedium, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, style = Griip.type.caption, color = c.textTertiary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (trailing != null) trailing()
    }
}

enum class BannerKind { Info, Warning, Error, Success }

@Composable
fun StatusBanner(text: String, kind: BannerKind, modifier: Modifier = Modifier, icon: ImageVector? = null, action: String? = null, onAction: (() -> Unit)? = null) {
    val c = Griip.colors
    val (fg, bg) = when (kind) {
        BannerKind.Info -> c.primaryDeep to c.primarySoft
        BannerKind.Warning -> c.warning to c.warningSoft
        BannerKind.Error -> c.danger to c.dangerSoft
        BannerKind.Success -> c.success to c.successSoft
    }
    Row(
        modifier.fillMaxWidth().clip(Shapes.tile).background(bg.copy(alpha = 0.92f)).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = fg, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
        }
        Text(text, style = Griip.type.label, color = fg, modifier = Modifier.weight(1f))
        if (action != null && onAction != null) {
            Text(action, style = Griip.type.label.copy(fontWeight = FontWeight.Bold), color = fg, modifier = Modifier.clip(Shapes.pill).pressable(onAction).padding(horizontal = 8.dp, vertical = 4.dp))
        }
    }
}

/** Illustrated empty/error state: soft orb with icon, title, hint, optional action. */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    action: String? = null,
    onAction: (() -> Unit)? = null,
    tint: Color = Griip.colors.primary,
) {
    val c = Griip.colors
    Column(modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(120.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(120.dp).clip(CircleShape).background(Brush.radialGradient(listOf(tint.copy(alpha = 0.22f), Color.Transparent))))
            Box(
                Modifier.size(76.dp).shadow(14.dp, CircleShape, ambientColor = tint.copy(alpha = 0.4f), spotColor = tint.copy(alpha = 0.4f)).clip(CircleShape)
                    .background(Brush.linearGradient(if (c.isDark) listOf(c.glassStrong, c.primarySoft) else listOf(Color.White, c.primarySoft)))
                    .border(1.dp, if (c.isDark) c.glassBorder else Color.White, CircleShape),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, null, tint = tint, modifier = Modifier.size(34.dp)) }
        }
        Spacer(Modifier.height(16.dp))
        Text(title, style = Griip.type.title, color = c.textPrimary, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(message, style = Griip.type.bodySmall, color = c.textSecondary, textAlign = TextAlign.Center)
        if (action != null && onAction != null) {
            Spacer(Modifier.height(20.dp))
            PillButton(action, onAction, Modifier.fillMaxWidth(0.8f), height = 50.dp)
        }
    }
}

/** Glass shimmer placeholder for loading lists. */
@Composable
fun ShimmerBlock(modifier: Modifier = Modifier, shape: androidx.compose.ui.graphics.Shape = Shapes.card) {
    val c = Griip.colors
    val t = rememberInfiniteTransition(label = "shimmer")
    val x by t.animateFloat(-1f, 2f, infiniteRepeatable(tween(1300, easing = LinearEasing), RepeatMode.Restart), label = "x")
    Box(
        modifier.clip(shape).background(c.glass).drawWithContent {
            drawContent()
            val w = size.width
            drawRect(
                Brush.linearGradient(
                    listOf(Color.Transparent, Color.White.copy(alpha = if (c.isDark) 0.06f else 0.55f), Color.Transparent),
                    start = Offset(w * x - w * 0.4f, 0f),
                    end = Offset(w * x + w * 0.4f, size.height),
                ),
            )
        },
    )
}

@Composable
fun ShimmerList(count: Int = 5, modifier: Modifier = Modifier) {
    Column(modifier.padding(horizontal = Space.gutter), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        repeat(count) { ShimmerBlock(Modifier.fillMaxWidth().height(76.dp)) }
    }
}

@Composable
fun PageDots(count: Int, selected: Int, modifier: Modifier = Modifier) {
    val c = Griip.colors
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(count) { i ->
            Box(Modifier.height(6.dp).width(if (i == selected) 18.dp else 6.dp).clip(Shapes.pill).background(if (i == selected) c.primary else c.textTertiary.copy(alpha = 0.4f)))
        }
    }
}
