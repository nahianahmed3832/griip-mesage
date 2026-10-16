package com.griipbuddy.orbit.ui.components

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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.griipbuddy.orbit.ui.theme.Griip
import com.griipbuddy.orbit.ui.theme.Shapes
import com.griipbuddy.orbit.ui.theme.Space

/** The flat app background every screen sits on. */
@Composable
fun AppBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(modifier.fillMaxSize().background(Griip.colors.background), content = content)
}

/** Press feedback: a ripple, plus a slight scale for tappable surfaces. Never delays the click. */
@Composable
fun Modifier.pressable(
    onClick: (() -> Unit)?,
    enabled: Boolean = true,
    role: Role = Role.Button,
    onClickLabel: String? = null,
    scaleOnPress: Boolean = false,
): Modifier {
    if (onClick == null) return this
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed && enabled && scaleOnPress) 0.98f else 1f, spring(stiffness = 700f), label = "press")
    return this
        .scale(s)
        .clickable(interactionSource = source, indication = androidx.compose.material3.ripple(), enabled = enabled, role = role, onClickLabel = onClickLabel, onClick = onClick)
}

/**
 * A flat card: surface fill, 1 dp border, no shadow. (The name is historical: the redesign replaced the
 * glass look.) [elevation] and [strong] are accepted for older call sites and no longer change the look.
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = Shapes.card,
    onClick: (() -> Unit)? = null,
    @Suppress("UNUSED_PARAMETER") strong: Boolean = false,
    @Suppress("UNUSED_PARAMETER") elevation: Dp = 0.dp,
    contentPadding: PaddingValues = PaddingValues(Space.lg),
    selected: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = Griip.colors
    Column(
        modifier
            .clip(shape)
            .background(if (selected) c.primarySoft else c.surface)
            .border(1.dp, if (selected) c.primary.copy(alpha = 0.45f) else c.border, shape)
            .pressable(onClick)
            .padding(contentPadding),
        content = content,
    )
}
