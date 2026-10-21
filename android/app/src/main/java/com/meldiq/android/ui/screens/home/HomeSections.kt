package com.meldiq.android.ui.screens.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import com.meldiq.android.ui.theme.headerBrush
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Apartment
import androidx.compose.material.icons.outlined.Business
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material.icons.outlined.Workspaces
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.meldiq.android.R
import com.meldiq.android.data.api.OrgDto
import com.meldiq.android.ui.components.IconTile
import com.meldiq.android.ui.components.MarkTile
import com.meldiq.android.ui.components.pressable
import com.meldiq.android.ui.screens.workspace.WorkspaceList
import com.meldiq.android.ui.theme.Griip
import com.meldiq.android.ui.theme.Motion
import com.meldiq.android.ui.theme.Shapes
import com.meldiq.android.ui.theme.Space

private val RowMinHeight = 52.dp
private val LeadingSize = 36.dp
private val HeaderControl = 48.dp

private fun accent(c: com.meldiq.android.ui.theme.GriipColors) = Brush.horizontalGradient(listOf(c.accentStart, c.accentEnd))

/**
 * The Home header: the workspace (its mark and name; tapping opens the switcher) and your avatar (profile and
 * settings) on one row, then search and the filter/options button. [avatar] draws the avatar, [filter] the filter
 * button with its menu.
 */
@Composable
fun WorkspaceHeader(
    org: OrgDto?,
    /** Whether the workspace list has loaded (from the server or the offline copy); until then no name is shown. */
    known: Boolean,
    onSwitcher: () -> Unit,
    onProfile: () -> Unit,
    onSearch: () -> Unit,
    avatar: @Composable () -> Unit,
    filter: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Griip.colors
    val switchLabel = stringResource(R.string.home_switch_workspace)
    val name = org?.name ?: if (known) stringResource(R.string.home_no_workspace) else ""
    val orgDescription = if (org != null) stringResource(R.string.home_workspace_named, org.name) else name.ifEmpty { switchLabel }
    val profileLabel = stringResource(R.string.home_profile_settings)
    val searchLabel = stringResource(R.string.chats_search)
    Column(
        modifier.fillMaxWidth()
            .background(c.headerBrush())
            .statusBarsPadding()
            .padding(start = Space.gutter, end = Space.gutter - 4.dp, top = 8.dp, bottom = 22.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.weight(1f).heightIn(min = HeaderControl).clip(Shapes.field).pressable(onSwitcher)
                    .clearAndSetSemantics {
                        contentDescription = orgDescription
                        role = Role.Button
                        onClick(label = switchLabel) { onSwitcher(); true }
                    }
                    .padding(end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (org != null) com.meldiq.android.ui.components.OrgMark(org.id, org.name, WorkspaceList.colorSeed(org), org.photoRev, size = 40.dp)
                else IconTile(Icons.Outlined.Workspaces, c.peach, c.peachSoft, size = 40.dp)
                Spacer(Modifier.width(10.dp))
                Text(
                    name, style = Griip.type.headline, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Icon(Icons.Rounded.KeyboardArrowDown, null, tint = c.textSecondary, modifier = Modifier.padding(start = 2.dp).size(22.dp))
            }
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier.size(HeaderControl).clip(CircleShape).pressable(onProfile)
                    .clearAndSetSemantics {
                        contentDescription = profileLabel
                        role = Role.Button
                        onClick { onProfile(); true }
                    },
                contentAlignment = Alignment.Center,
            ) { avatar() }
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.weight(1f).height(HeaderControl).clip(Shapes.field).background(c.headerField)
                    .pressable(onSearch)
                    .clearAndSetSemantics {
                        contentDescription = searchLabel
                        role = Role.Button
                        onClick { onSearch(); true }
                    }
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Search, null, tint = c.textSecondary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.home_search), style = Griip.type.bodyMedium, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(4.dp))
            filter()
        }
    }
}

/**
 * The rounded top edge of Home's content, drawn over the end of the header gradient (the reference's card look). The
 * header gradient runs left to right, so this strip continues it exactly.
 */
@Composable
fun HomeSheetTop(modifier: Modifier = Modifier) {
    val c = Griip.colors
    Box(modifier.fillMaxWidth().height(SheetTopHeight).background(c.headerBrush())) {
        Box(Modifier.fillMaxWidth().height(SheetTopHeight).clip(androidx.compose.foundation.shape.RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)).background(c.background))
    }
}

private val SheetTopHeight = 20.dp

/** A quick-access card. [selected] non-null makes it an on/off switch (the Unreads card); null, a button. */
@Immutable
data class QuickCard(
    val icon: ImageVector,
    val title: String,
    val subtitle: String,
    /** Something waiting here (unread, a draft, a missed call): the icon takes the accent. */
    val active: Boolean,
    val selected: Boolean? = null,
    val onClick: () -> Unit,
)

/**
 * The row of quick-access cards under the header: equal cards that scroll sideways when they don't all fit (more than
 * three, or large text), with the next card peeking in as a cue.
 */
@Composable
fun QuickAccessRow(cards: List<QuickCard>, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().height(IntrinsicSize.Min).horizontalScroll(rememberScrollState()).padding(horizontal = Space.gutter),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) { cards.forEach { QuickCardView(it, Modifier.widthIn(min = QuickCardWidth)) } }
}

private val QuickCardWidth = 116.dp

@Composable
private fun QuickCardView(card: QuickCard, modifier: Modifier) {
    val c = Griip.colors
    val on = card.selected == true
    val shape = Shapes.hero
    val click = if (card.selected != null) {
        Modifier.toggleable(value = on, role = Role.Switch, onValueChange = { card.onClick() })
    } else Modifier.pressable(card.onClick)
    Column(
        modifier.fillMaxHeight().heightIn(min = 92.dp).clip(shape)
            .background(if (on) c.primarySoft else c.surface)
            .border(if (on) 1.5.dp else 1.dp, if (on) SolidColor(c.primary) else SolidColor(c.border), shape)
            .then(click)
            .padding(12.dp),
    ) {
        Box {
            Icon(card.icon, null, tint = c.primary, modifier = Modifier.size(22.dp))
            // Something waiting here: a dot in the badge colour (the subtitle says what, so colour isn't the only cue).
            if (card.active) Box(Modifier.align(Alignment.TopEnd).padding(start = 18.dp).size(8.dp).clip(CircleShape).background(c.badge))
        }
        Spacer(Modifier.height(8.dp))
        Text(card.title, style = Griip.type.label.copy(fontWeight = FontWeight.SemiBold), color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(card.subtitle, style = Griip.type.caption, color = c.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * A Home section title. With [expanded] it is a collapsible header (the whole row toggles, and says whether it is open);
 * otherwise it may carry an [action] such as "See all".
 */
@Composable
fun HomeSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    action: String? = null,
    onAction: (() -> Unit)? = null,
    expanded: Boolean? = null,
    onToggle: (() -> Unit)? = null,
) {
    val c = Griip.colors
    val stateText = if (expanded == true) stringResource(R.string.home_expanded) else stringResource(R.string.home_collapsed)
    val toggle = if (expanded != null && onToggle != null) {
        Modifier.pressable(onToggle).clearAndSetSemantics {
            contentDescription = title
            heading()
            role = Role.Button
            stateDescription = stateText
            onClick { onToggle(); true }
        }
    } else Modifier
    val actionButton: @Composable () -> Unit = {
        if (action != null && onAction != null) {
            Text(
                action, style = Griip.type.label.copy(fontWeight = FontWeight.SemiBold), color = c.primary,
                modifier = Modifier.heightIn(min = HeaderControl).clip(Shapes.pill).pressable(onAction).padding(horizontal = 10.dp, vertical = 14.dp),
            )
        }
    }
    if (expanded != null && action != null && onAction != null) {
        // Collapsible with an action (a custom section's "Edit"): the title and chevron toggle, the action is its own
        // button, so both stay reachable by touch and by screen readers.
        Row(modifier.fillMaxWidth().padding(top = 12.dp).heightIn(min = HeaderControl), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.weight(1f).heightIn(min = HeaderControl).then(toggle).padding(start = Space.gutter + 4.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    title, style = Griip.type.label.copy(fontWeight = FontWeight.SemiBold), color = c.textSecondary,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                Chevron(expanded)
            }
            Box(Modifier.padding(end = Space.gutter - 4.dp)) { actionButton() }
        }
        return
    }
    Row(
        modifier.fillMaxWidth().padding(top = 12.dp).heightIn(min = HeaderControl).then(toggle).padding(start = Space.gutter + 4.dp, end = Space.gutter - 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title, style = Griip.type.label.copy(fontWeight = FontWeight.SemiBold), color = c.textSecondary,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        if (expanded != null) Chevron(expanded) else actionButton()
    }
}

@Composable
private fun Chevron(expanded: Boolean) {
    val turn by animateFloatAsState(if (expanded) 0f else -90f, Motion.quick(), label = "chevron")
    Icon(Icons.Rounded.KeyboardArrowDown, null, tint = Griip.colors.textSecondary, modifier = Modifier.size(22.dp).rotate(turn))
}


/** A channel group's collapsible header; collapsed, it shows how many of its channels have unread messages. */
@Composable
fun ChannelGroupHeader(group: ChannelGroup, expanded: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val c = Griip.colors
    val stateText = if (expanded) stringResource(R.string.home_expanded) else stringResource(R.string.home_collapsed)
    val unreadText = stringResource(R.string.home_unread_messages, group.unreadChannels)
    Row(
        modifier.fillMaxWidth().padding(top = 8.dp).heightIn(min = HeaderControl).pressable(onToggle)
            .clearAndSetSemantics {
                contentDescription = if (!expanded && group.unreadChannels > 0) "${group.title}, $unreadText" else group.title
                heading()
                role = Role.Button
                stateDescription = stateText
                onClick { onToggle(); true }
            }
            .padding(start = Space.gutter + 4.dp, end = Space.gutter - 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (group.categoryId == null) androidx.compose.material.icons.Icons.Outlined.Tag else androidx.compose.material.icons.Icons.Outlined.FolderOpen, null, tint = c.peach, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(
            group.title, style = Griip.type.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = c.textPrimary,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        if (!expanded && group.unreadChannels > 0) {
            HomeCount(group.unreadChannels, accent = false, description = unreadText)
            Spacer(Modifier.width(6.dp))
        }
        Chevron(expanded)
    }
}

/** A compact Home list row (a DM, a mention, a channel). Long-press opens the chat's actions when given. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeListRow(
    title: String,
    leading: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    bold: Boolean = false,
    dim: Boolean = false,
    indent: Dp = 0.dp,
    onLongClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val c = Griip.colors
    Row(
        modifier.fillMaxWidth().heightIn(min = RowMinHeight)
            .combinedClickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = ripple(),
                onClick = onClick,
                onLongClick = onLongClick,
                onLongClickLabel = if (onLongClick != null) "More actions" else null,
            )
            .padding(start = Space.gutter + 4.dp + indent, end = Space.gutter, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(LeadingSize), contentAlignment = Alignment.Center) { leading() }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title, style = Griip.type.bodyMedium.copy(fontWeight = if (bold) FontWeight.Bold else FontWeight.Medium),
                color = if (dim) c.textTertiary else c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) Text(subtitle, style = Griip.type.caption, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
    }
}

/** A count pill: the accent gradient for what needs you (unread DMs, mentions), a quiet fill otherwise. */
@Composable
fun HomeCount(count: Int, accent: Boolean, description: String, modifier: Modifier = Modifier, prefix: String = "") {
    if (count <= 0) return
    val c = Griip.colors
    Box(
        modifier.heightIn(min = 22.dp).widthIn(min = 22.dp).clip(Shapes.pill)
            .background(if (accent) c.badge else c.surfaceVariant)
            .padding(horizontal = 7.dp)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            prefix + if (count > 99) "99+" else "$count", style = Griip.type.caption.copy(fontWeight = FontWeight.Bold),
            color = if (accent) c.onBadge else c.textPrimary, maxLines = 1,
        )
    }
}

/** A short, quiet line in place of an empty section (nothing invented to fill it). */
@Composable
fun QuietLine(text: String, modifier: Modifier = Modifier) {
    Text(
        text, style = Griip.type.bodySmall, color = Griip.colors.textSecondary,
        modifier = modifier.fillMaxWidth().padding(horizontal = Space.gutter + 4.dp, vertical = 10.dp),
    )
}

/**
 * Whether a list's New button shows: hidden while the list is scrolled down, so it never rests on a row's counts
 * (physical-device check), and back when scrolling up or at the end, where the list's bottom padding clears it.
 */
@Composable
fun rememberFabVisible(list: LazyListState): State<Boolean> =
    remember(list) { derivedStateOf { !list.lastScrolledForward || !list.canScrollForward } }

/** The contextual New action (a new message): a raised accent square above the tab bar, shown while [visible]. */
@Composable
fun ComposeFab(label: String, onCompose: () -> Unit, modifier: Modifier = Modifier, visible: Boolean = true) {
    val c = Griip.colors
    AnimatedVisibility(
        visible, modifier,
        enter = fadeIn(Motion.quick()) + scaleIn(Motion.quick(), initialScale = 0.8f),
        exit = fadeOut(Motion.quick()) + scaleOut(Motion.quick(), targetScale = 0.8f),
    ) {
        Box(
            Modifier.size(56.dp).shadow(8.dp, Shapes.hero, ambientColor = c.shadow, spotColor = c.shadow).clip(Shapes.hero)
                .background(c.actionStart)
                .pressable(onCompose)
                .clearAndSetSemantics {
                    contentDescription = label
                    role = Role.Button
                    onClick { onCompose(); true }
                },
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Rounded.Edit, null, tint = c.onAction, modifier = Modifier.size(24.dp)) }
    }
}
