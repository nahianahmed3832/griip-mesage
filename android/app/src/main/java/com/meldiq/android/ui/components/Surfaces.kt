package com.meldiq.android.ui.components

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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.vector.ImageVector
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
import com.meldiq.android.ui.theme.Griip
import com.meldiq.android.ui.theme.Shapes
import com.meldiq.android.ui.theme.Space

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

/**
 * A large tappable option: icon tile, title, one-line explanation and a chevron, on a card. For short lists of
 * choices such as how to sign in. TalkBack reads it as one button.
 */
@Composable
fun ChoiceCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Griip.colors
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .clip(Shapes.panel)
            .background(c.surface)
            .border(1.dp, c.border, Shapes.panel)
            .pressable(onClick)
            .padding(horizontal = Space.lg, vertical = Space.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile(icon, c.primary, c.primarySoft, size = 44.dp)
        Spacer(Modifier.width(Space.md))
        Column(Modifier.weight(1f)) {
            Text(title, style = Griip.type.titleSmall, color = c.textPrimary)
            Spacer(Modifier.height(2.dp))
            Text(subtitle, style = Griip.type.bodySmall, color = c.textSecondary)
        }
        Spacer(Modifier.width(Space.sm))
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = c.textTertiary)
    }
}
