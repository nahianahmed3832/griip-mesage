package com.griipbuddy.message.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.griipbuddy.message.ui.theme.Griip
import com.griipbuddy.message.ui.theme.Shapes
import com.griipbuddy.message.ui.theme.Space

/**
 * The reference's diagonal mint → white → peach canvas with two soft glows. Every screen sits on it
 * (docs/DESIGN_SYSTEM.md §4 rule 1).
 */
@Composable
fun GradientCanvas(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val c = Griip.colors
    Box(
        modifier
            .fillMaxSize()
            .background(Brush.linearGradient(listOf(c.canvasTop, c.canvasMid, c.canvasBottom), start = Offset.Zero, end = Offset.Infinite))
            .drawBehind {
                drawCircle(
                    Brush.radialGradient(listOf(c.primaryBright.copy(alpha = if (c.isDark) 0.10f else 0.16f), Color.Transparent), center = Offset(size.width * 0.05f, size.height * 0.08f), radius = size.width * 0.75f),
                    radius = size.width * 0.75f, center = Offset(size.width * 0.05f, size.height * 0.08f),
                )
                drawCircle(
                    Brush.radialGradient(listOf(c.peach.copy(alpha = if (c.isDark) 0.06f else 0.14f), Color.Transparent), center = Offset(size.width * 0.95f, size.height * 0.92f), radius = size.width * 0.8f),
                    radius = size.width * 0.8f, center = Offset(size.width * 0.95f, size.height * 0.92f),
                )
            },
        content = content,
    )
}

/** Spring "press" feedback: pressable surfaces shrink to 0.97 (§3.6). */
@Composable
fun Modifier.pressable(
    onClick: (() -> Unit)?,
    enabled: Boolean = true,
    role: Role = Role.Button,
    onClickLabel: String? = null,
): Modifier {
    if (onClick == null) return this
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed && enabled) 0.97f else 1f, spring(stiffness = 600f), label = "press")
    return this
        .scale(s)
        .clickable(interactionSource = source, indication = androidx.compose.material3.ripple(), enabled = enabled, role = role, onClickLabel = onClickLabel, onClick = onClick)
}

/** Frosted glass surface: translucent fill, 1 dp frost edge, soft teal-tinted shadow. */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = Shapes.card,
    onClick: (() -> Unit)? = null,
    strong: Boolean = false,
    elevation: Dp = 10.dp,
    contentPadding: PaddingValues = PaddingValues(Space.lg),
    selected: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = Griip.colors
    Column(
        modifier
            .shadow(elevation, shape, ambientColor = c.shadow, spotColor = c.shadow, clip = false)
            .clip(shape)
            .background(if (strong) c.glassStrong else c.glass)
            .then(if (selected) Modifier.background(c.primarySoft.copy(alpha = 0.6f)) else Modifier)
            .border(1.dp, if (selected) c.primary.copy(alpha = 0.5f) else c.glassBorder, shape)
            .pressable(onClick)
            .padding(contentPadding),
        content = content,
    )
}

/** Teal hero gradient surface (the reference's "next appointment" card). */
@Composable
fun HeroCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val c = Griip.colors
    Box(
        modifier
            .shadow(16.dp, Shapes.hero, ambientColor = c.primary.copy(alpha = 0.35f), spotColor = c.primary.copy(alpha = 0.35f))
            .clip(Shapes.hero)
            .background(Brush.linearGradient(listOf(c.heroStart, c.heroMid, c.heroEnd)))
            .drawBehind {
                drawCircle(Color.White.copy(alpha = 0.10f), radius = size.height * 0.9f, center = Offset(size.width * 0.95f, -size.height * 0.1f))
                drawCircle(Color.White.copy(alpha = 0.07f), radius = size.height * 0.55f, center = Offset(size.width * 0.1f, size.height * 1.2f))
            }
            .pressable(onClick)
            .padding(Space.xl),
        content = content,
    )
}
