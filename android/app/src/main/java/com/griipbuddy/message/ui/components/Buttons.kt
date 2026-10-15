package com.griipbuddy.message.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.griipbuddy.message.ui.theme.Griip
import com.griipbuddy.message.ui.theme.Shapes

enum class ButtonTone { Primary, Glass, Danger }

/**
 * The reference's signature CTA: full-width teal gradient pill with a circular arrow "knob" at the
 * trailing edge. Glass/Danger tones reuse the geometry.
 */
@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tone: ButtonTone = ButtonTone.Primary,
    enabled: Boolean = true,
    loading: Boolean = false,
    showKnob: Boolean = tone == ButtonTone.Primary,
    leadingIcon: ImageVector? = null,
    height: Dp = 56.dp,
) {
    val c = Griip.colors
    val active = enabled && !loading
    val bg: Modifier = when (tone) {
        ButtonTone.Primary -> Modifier
            .shadow(if (active) 14.dp else 0.dp, Shapes.pill, ambientColor = c.primary.copy(alpha = 0.4f), spotColor = c.primary.copy(alpha = 0.4f))
            .clip(Shapes.pill)
            .background(Brush.horizontalGradient(listOf(c.actionStart, c.actionEnd)))
        ButtonTone.Danger -> Modifier
            .shadow(if (active) 12.dp else 0.dp, Shapes.pill, ambientColor = c.danger.copy(alpha = 0.35f), spotColor = c.danger.copy(alpha = 0.35f))
            .clip(Shapes.pill)
            .background(Brush.horizontalGradient(listOf(c.danger.copy(alpha = 0.9f), c.danger)))
        ButtonTone.Glass -> Modifier
            .shadow(6.dp, Shapes.pill, ambientColor = c.shadow, spotColor = c.shadow)
            .clip(Shapes.pill)
            .background(c.glassStrong)
            .border(1.dp, c.glassBorder, Shapes.pill)
    }
    val fg = if (tone == ButtonTone.Glass) c.primaryDeep else Color.White
    Box(
        modifier
            .heightIn(min = height)
            .height(height)
            .alpha(if (enabled) 1f else 0.45f)
            .then(bg)
            .pressable(if (active) onClick else null, enabled = active),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = if (showKnob) 8.dp else 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (showKnob) Spacer(Modifier.width(40.dp))
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                if (loading) {
                    CircularProgressIndicator(Modifier.size(20.dp), color = fg, strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                } else if (leadingIcon != null) {
                    Icon(leadingIcon, null, tint = fg, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                }
                Text(text, style = Griip.type.button, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (showKnob) {
                Box(
                    Modifier.size(40.dp).clip(CircleShape).background(if (tone == ButtonTone.Glass) c.primarySoft else Color.White.copy(alpha = 0.24f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, tint = fg, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

/** Small teal circle with a chevron — the trailing affordance on list cards. */
@Composable
fun ArrowKnob(modifier: Modifier = Modifier, size: Dp = 32.dp, onClick: (() -> Unit)? = null, contentDescription: String? = null) {
    val c = Griip.colors
    Box(
        modifier
            .size(size)
            .shadow(6.dp, CircleShape, ambientColor = c.primary.copy(alpha = 0.4f), spotColor = c.primary.copy(alpha = 0.4f))
            .clip(CircleShape)
            .background(Brush.linearGradient(listOf(c.actionStart, c.actionEnd)))
            .pressable(onClick)
            .then(if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = Color.White, modifier = Modifier.size(size * 0.62f))
    }
}

/** 44 dp frosted circle used for header back/actions. Hit target ≥ 48 dp via padding. */
@Composable
fun CircleIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Griip.colors.textPrimary,
    background: Color = Griip.colors.glassStrong,
    size: Dp = 44.dp,
    badge: Boolean = false,
    enabled: Boolean = true,
) {
    val c = Griip.colors
    Box(modifier.size(size + 4.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(size)
                .shadow(6.dp, CircleShape, ambientColor = c.shadow, spotColor = c.shadow)
                .clip(CircleShape)
                .background(background)
                .border(1.dp, c.glassBorder, CircleShape)
                .alpha(if (enabled) 1f else 0.4f)
                .pressable(if (enabled) onClick else null, onClickLabel = contentDescription)
                .semantics { this.contentDescription = contentDescription },
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(size * 0.48f))
        }
        if (badge) {
            Box(
                Modifier.align(Alignment.TopEnd).padding(top = 6.dp, end = 6.dp).size(10.dp).clip(CircleShape)
                    .background(c.danger).border(2.dp, c.glassStrong, CircleShape),
            )
        }
    }
}

/** Large round call control (mute, camera, share, end). */
@Composable
fun CallControlButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    danger: Boolean = false,
    enabled: Boolean = true,
) {
    val c = Griip.colors
    val bg = when {
        danger -> Brush.linearGradient(listOf(Color(0xFFFF8A73), c.danger))
        active -> Brush.linearGradient(listOf(Color.White, Color(0xFFE9FBF8)))
        else -> Brush.linearGradient(listOf(Color.White.copy(alpha = 0.18f), Color.White.copy(alpha = 0.12f)))
    }
    val fg = when {
        danger -> Color.White
        active -> c.primaryDeep
        else -> Color.White
    }
    androidx.compose.foundation.layout.Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(60.dp)
                .clip(CircleShape)
                .background(bg)
                .border(1.dp, Color.White.copy(alpha = 0.22f), CircleShape)
                .alpha(if (enabled) 1f else 0.4f)
                .pressable(if (enabled) onClick else null, onClickLabel = label)
                .semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = fg, modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(label, style = Griip.type.caption, color = Color.White.copy(alpha = 0.85f), maxLines = 1)
    }
}
