package com.meldiq.android.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalDensity
import com.meldiq.android.ui.theme.Griip
import com.meldiq.android.ui.theme.Motion
import com.meldiq.android.ui.theme.Shapes
import com.meldiq.android.ui.theme.Space

/** Filter pill: neutral glass, filled teal when selected (reference: All / General / Cosmetic). */
/** Filter chip: selected = soft emerald with emerald text; unselected = outlined surface. */
@Composable
fun PillChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    count: Int? = null,
) {
    val c = Griip.colors
    val bg by animateColorAsState(if (selected) c.navIndicator else c.surface, Motion.quick(), label = "chipBg")
    val fg by animateColorAsState(if (selected) c.primaryDeep else c.textSecondary, Motion.quick(), label = "chipFg")
    Row(
        modifier
            .heightIn(min = 36.dp)
            .clip(Shapes.pill)
            .background(bg)
            .border(1.dp, if (selected) Color.Transparent else c.border, Shapes.pill)
            .pressable(onClick, role = Role.Tab)
            .semantics { this.selected = selected }
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = fg, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, style = Griip.type.label.copy(fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium), color = fg, maxLines = 1)
        if (count != null && count > 0) {
            Spacer(Modifier.width(6.dp))
            Box(
                Modifier.clip(Shapes.pill).background(if (selected) c.primary else c.surfaceVariant).padding(horizontal = 6.dp, vertical = 1.dp),
            ) { Text(if (count > 99) "99+" else "$count", style = Griip.type.caption, color = if (selected) c.onPrimary else c.textSecondary) }
        }
    }
}

/**
 * Filter chip in the Meldiq direction (home, UI-002): the selected chip is filled with the palette's accent gradient,
 * the others are a quiet raised fill without a border. A tab for TalkBack, with its selected state.
 */
@Composable
fun AccentChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    count: Int? = null,
) {
    val c = Griip.colors
    val fg by animateColorAsState(if (selected) c.onAccent else c.textSecondary, Motion.quick(), label = "accentChipFg")
    Row(
        modifier
            .heightIn(min = 40.dp)
            .clip(Shapes.pill)
            .background(if (selected) Brush.horizontalGradient(listOf(c.accentStart, c.accentEnd)) else SolidColor(c.surface))
            .pressable(onClick, role = Role.Tab)
            .semantics { this.selected = selected }
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = Griip.type.label.copy(fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium), color = fg, maxLines = 1)
        if (count != null && count > 0) {
            Spacer(Modifier.width(6.dp))
            Text(if (count > 99) "99+" else "$count", style = Griip.type.caption.copy(fontWeight = FontWeight.Bold), color = fg)
        }
    }
}

@Composable
fun ChipRow(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = Space.gutter, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}

/** Segmented control: neutral track, surface thumb, emerald label on the selected segment. */
@Composable
fun SegmentedPills(options: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = Griip.colors
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(Shapes.pill)
            .background(c.surfaceVariant)
            .padding(3.dp),
    ) {
        val segment = maxWidth / options.size
        val offset by animateDpAsState(segment * selectedIndex, Motion.standard(), label = "thumb")
        Box(
            Modifier.offset { androidx.compose.ui.unit.IntOffset(offset.roundToPx(), 0) }.width(segment).fillMaxHeight()
                .clip(Shapes.pill)
                .background(c.surface)
                .border(1.dp, c.border, Shapes.pill),
        )
        Row(Modifier.fillMaxWidth().fillMaxHeight()) {
            options.forEachIndexed { i, label ->
                val sel = i == selectedIndex
                val fg by animateColorAsState(if (sel) c.primaryDeep else c.textSecondary, Motion.quick(), label = "segFg")
                Box(
                    Modifier.weight(1f).fillMaxHeight().clip(Shapes.pill)
                        .pressable({ onSelect(i) }, role = Role.Tab)
                        .semantics { selected = sel },
                    contentAlignment = Alignment.Center,
                ) { Text(label, style = Griip.type.label.copy(fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Medium), color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
}

/** Filled search field with clear button and optional trailing action. */
@Composable
fun SearchPill(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    val c = Griip.colors
    Row(
        modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(Shapes.pill)
            .background(c.surfaceVariant)
            .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Search, null, tint = c.textSecondary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(placeholder, style = Griip.type.bodyMedium.copy(fontWeight = FontWeight.Normal), color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = Griip.type.bodyMedium.copy(color = c.textPrimary, fontWeight = FontWeight.Normal),
                cursorBrush = SolidColor(c.primary),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = placeholder },
            )
        }
        if (value.isNotEmpty()) {
            Box(Modifier.size(44.dp).clip(CircleShape).pressable({ onValueChange("") }, onClickLabel = "Clear search"), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Close, "Clear search", tint = c.textSecondary, modifier = Modifier.size(18.dp))
            }
        } else if (trailing != null) trailing()
    }
}

/**
 * Outlined text field with label above, helper/error text below and optional leading icon. The outline darkens to the
 * primary colour while focused and turns red with an error; the error is also announced (TalkBack) as the field's error.
 */
@Composable
fun GriipTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    leadingIcon: ImageVector? = null,
    prefix: String? = null,
    helper: String? = null,
    error: String? = null,
    singleLine: Boolean = true,
    minLines: Int = 1,
    enabled: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    shape: Shape = Shapes.tile,
    textStyle: TextStyle = Griip.type.body,
    trailing: (@Composable () -> Unit)? = null,
) {
    val c = Griip.colors
    var focused by remember { mutableStateOf(false) }
    // Compose scrolls only the cursor line into view; this keeps the whole field and its helper text above the
    // keyboard and any bar pinned over it, also while the keyboard is still opening.
    val bringIntoView = remember { BringIntoViewRequester() }
    val imeBottom = if (focused) WindowInsets.ime.getBottom(LocalDensity.current) else 0
    LaunchedEffect(focused, imeBottom) { if (focused) bringIntoView.bringIntoView() }
    val outline by animateColorAsState(
        when {
            error != null -> c.danger
            focused -> c.primary
            else -> c.outline
        },
        Motion.quick(), label = "fieldOutline",
    )
    Column(modifier.fillMaxWidth().bringIntoViewRequester(bringIntoView)) {
        Text(label, style = Griip.type.label, color = c.textSecondary, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .clip(shape)
                .background(c.surface)
                .border(if (error != null || focused) 1.5.dp else 1.dp, outline, shape)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = if (singleLine) Alignment.CenterVertically else Alignment.Top,
        ) {
            if (leadingIcon != null) {
                Icon(leadingIcon, null, tint = if (focused) c.primary else c.textSecondary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
            }
            if (prefix != null) Text(prefix, style = textStyle, color = c.textTertiary)
            Box(Modifier.weight(1f)) {
                if (value.isEmpty() && placeholder.isNotEmpty()) Text(placeholder, style = textStyle, color = c.textTertiary, maxLines = if (singleLine) 1 else minLines)
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    enabled = enabled,
                    singleLine = singleLine,
                    minLines = minLines,
                    textStyle = textStyle.copy(color = c.textPrimary),
                    cursorBrush = SolidColor(c.primary),
                    keyboardOptions = keyboardOptions,
                    keyboardActions = keyboardActions,
                    visualTransformation = visualTransformation,
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { focused = it.isFocused }
                        .semantics {
                            contentDescription = label
                            if (error != null) error(error)
                        },
                )
            }
            if (trailing != null) {
                Spacer(Modifier.width(8.dp))
                trailing()
            }
        }
        val support = error ?: helper
        if (support != null) {
            Text(
                support, style = Griip.type.caption, color = if (error != null) c.danger else c.textSecondary,
                modifier = Modifier.padding(start = 4.dp, top = 6.dp)
                    .then(if (error != null) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier),
            )
        }
    }
}

/** Material switch in the Griip colours (accessible toggle semantics). */
@Composable
fun GriipSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = Griip.colors
    androidx.compose.material3.Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        enabled = enabled,
        colors = androidx.compose.material3.SwitchDefaults.colors(
            checkedThumbColor = c.onAction, checkedTrackColor = c.actionStart, checkedBorderColor = c.actionStart,
            uncheckedThumbColor = c.textSecondary, uncheckedTrackColor = c.surfaceVariant, uncheckedBorderColor = c.border,
        ),
    )
}

val FieldPadding = PaddingValues(horizontal = Space.gutter)
