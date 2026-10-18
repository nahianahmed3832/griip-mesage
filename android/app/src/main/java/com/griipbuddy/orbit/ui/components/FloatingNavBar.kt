package com.griipbuddy.orbit.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.griipbuddy.orbit.ui.theme.Griip
import com.griipbuddy.orbit.ui.theme.Motion

private val BarHeight = 64.dp
private val BarShape = RoundedCornerShape(32.dp)
private val ItemShape = RoundedCornerShape(24.dp)

/**
 * The floating bottom navigation (UI-002): a rounded bar raised above the gesture area with the tabs (Home, DMs,
 * Activity). The selected tab is filled with the palette's accent gradient; the others are quiet. No action button:
 * each screen offers its own New action. It floats over the screens: tab screens keep
 * [com.griipbuddy.orbit.ui.LocalTabBarPadding] below their content.
 */
@Composable
fun FloatingNavBar(
    items: List<NavItem>,
    selectedRoute: String?,
    onSelect: (NavItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Griip.colors
    Row(
        modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Row(
            Modifier
                .weight(1f, fill = false)
                .widthIn(max = 440.dp)
                .heightIn(min = BarHeight)
                .shadow(8.dp, BarShape, ambientColor = c.shadow, spotColor = c.shadow)
                .clip(BarShape)
                .background(c.glassStrong)
                .border(1.dp, c.hairline, BarShape)
                .padding(6.dp)
                .selectableGroup(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEach { item -> NavTab(item, item.route == selectedRoute, Modifier.weight(1f)) { onSelect(item) } }
        }
    }
}

@Composable
private fun NavTab(item: NavItem, selected: Boolean, modifier: Modifier, onTap: () -> Unit) {
    val c = Griip.colors
    val fg by animateColorAsState(if (selected) c.onAccent else c.textSecondary, Motion.quick(), label = "navTabFg")
    val description = if (item.badge > 0) "${item.label}, ${item.badge}" else item.label
    Column(
        modifier
            .heightIn(min = 52.dp)
            .clip(ItemShape)
            .background(if (selected) Brush.horizontalGradient(listOf(c.accentStart, c.accentEnd)) else SolidColor(Color.Transparent))
            .selectable(selected = selected, onClick = onTap, role = Role.Tab)
            // One node for TalkBack: "DMs, 3, tab, selected". The cleared node needs its own click action.
            .clearAndSetSemantics {
                contentDescription = description
                this.selected = selected
                role = Role.Tab
                onClick { onTap(); true }
            }
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box {
            Icon(if (selected) item.selectedIcon else item.icon, null, tint = fg, modifier = Modifier.size(22.dp))
            if (item.badge > 0) {
                Box(
                    Modifier.align(Alignment.TopEnd).offset(x = 12.dp, y = (-6).dp).heightIn(min = 16.dp).widthIn(min = 16.dp)
                        .clip(CircleShape).background(c.danger).padding(horizontal = 4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(if (item.badge > 99) "99+" else "${item.badge}", style = Griip.type.caption.copy(fontWeight = FontWeight.Bold), color = c.onAccent, maxLines = 1)
                }
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(
            item.label, style = Griip.type.caption.copy(fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium),
            color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}
