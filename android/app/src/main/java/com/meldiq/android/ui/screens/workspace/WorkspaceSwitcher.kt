package com.meldiq.android.ui.screens.workspace

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.meldiq.android.data.api.OrgDto
import com.meldiq.android.ui.TimeFmt
import com.meldiq.android.ui.components.ListCard
import com.meldiq.android.ui.components.MarkTile
import com.meldiq.android.ui.components.RoleBadge
import com.meldiq.android.ui.components.StatusBanner
import com.meldiq.android.ui.components.BannerKind
import com.meldiq.android.ui.components.pressable
import com.meldiq.android.ui.theme.Griip
import com.meldiq.android.ui.theme.Shapes

/**
 * The workspaces an account belongs to, as the switcher shows them (unit-tested in `WorkspaceListTest`).
 * One entry per organisation id, in a stable order; workspaces that share a name are reported, never merged or hidden:
 * each is a separate organisation with its own members (physical-device report: five "GriipBuddy" cards).
 */
object WorkspaceList {
    fun normalize(orgs: List<OrgDto>): List<OrgDto> =
        orgs.distinctBy { it.id }.sortedWith(compareBy<OrgDto>({ it.name.trim().lowercase() }, { it.createdAt }, { it.id }))

    /** Names used by more than one workspace in [orgs] (case and surrounding spaces ignored). */
    fun sharedNames(orgs: List<OrgDto>): Set<String> =
        orgs.groupBy { it.name.trim().lowercase() }.filterValues { it.size > 1 }.keys

    /** A colour seed per organisation, not per name, so same-named workspaces don't look identical. */
    fun colorSeed(org: OrgDto): Int = org.id.hashCode()

    /** The line under a workspace's name: what tells same-named workspaces apart (handle, then creation date). */
    fun subtitle(org: OrgDto, sameNameAsAnother: Boolean, date: (Long) -> String = TimeFmt::dayHeader): String = buildList {
        if (org.isCommunity) add("Community")
        add("${org.memberCount} ${if (org.memberCount == 1) "member" else "members"}")
        if (sameNameAsAnother) {
            if (org.slug.isNotEmpty()) add("@${org.slug}")
            if (org.createdAt > 0) add("created ${date(org.createdAt)}")
        } else org.description?.takeIf { it.isNotBlank() }?.let { add(it) }
    }.joinToString(" · ")
}

/**
 * Discord-style quick switcher: one tile per workspace across the top of the Workspace tab, the selected one marked
 * with a bar and a ring, then "+" to join or create. Shown only when there is more than one workspace.
 */
@Composable
fun WorkspaceRail(
    orgs: List<OrgDto>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
    /** Channels with unread messages, by organisation id: a dot on the tile (Home, Meldiq 2.0). */
    unread: Map<String, Int> = emptyMap(),
) {
    val c = Griip.colors
    val addLabel = androidx.compose.ui.res.stringResource(com.meldiq.android.R.string.m2_rail_add)
    LazyRow(modifier, contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(orgs, key = { it.id }) { o ->
            val selected = o.id == selectedId
            val ring by animateColorAsState(if (selected) c.primary else c.border, label = "ring")
            val bar by animateDpAsState(if (selected) 18.dp else 0.dp, label = "bar")
            val unreadHere = unread[o.id] ?: 0
            val switchLabel = androidx.compose.ui.res.stringResource(com.meldiq.android.R.string.m2_rail_switch, o.name)
            val described = if (unreadHere > 0) androidx.compose.ui.res.stringResource(com.meldiq.android.R.string.m2_rail_unread, o.name, unreadHere) else o.name
            Column(
                Modifier.width(64.dp).clip(Shapes.tile).pressable({ onSelect(o.id) }, onClickLabel = switchLabel)
                    .semantics { this.selected = selected; contentDescription = described },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box {
                    Box(Modifier.padding(top = 4.dp).border(2.dp, ring, Shapes.iconTile).padding(3.dp)) {
                        com.meldiq.android.ui.components.OrgMark(o.id, o.name, WorkspaceList.colorSeed(o), o.photoRev, size = 46.dp)
                    }
                    // Unread marker: a dot in the brand's badge colour, ringed with the background so it reads on any photo.
                    if (unreadHere > 0 && !selected) {
                        Box(
                            Modifier.align(Alignment.TopEnd).size(14.dp).clip(Shapes.pill).background(c.background).padding(2.dp)
                                .clip(Shapes.pill).background(c.badge),
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Box(Modifier.height(3.dp).width(bar).clip(Shapes.pill).background(c.primary))
                Text(
                    o.name, style = Griip.type.caption, color = if (selected) c.textPrimary else c.textSecondary, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        item(key = "add") {
            Column(Modifier.width(64.dp).clip(Shapes.tile).pressable(onAdd, onClickLabel = addLabel), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.padding(top = 4.dp).size(52.dp).clip(Shapes.iconTile).background(c.surfaceVariant).semantics { contentDescription = addLabel },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.Add, null, tint = c.primary) }
                Spacer(Modifier.height(7.dp))
                Text("Add", style = Griip.type.caption, color = c.textSecondary)
            }
        }
    }
}

/** The switcher sheet's list: one keyed card per workspace, the selected one ticked. */
@Composable
fun WorkspaceSwitcherList(orgs: List<OrgDto>, selectedId: String?, onSelect: (String) -> Unit) {
    val c = Griip.colors
    val shared = WorkspaceList.sharedNames(orgs)
    if (shared.isNotEmpty()) {
        StatusBanner(
            "Some of your workspaces have the same name. Each is a separate workspace with its own members; the handle and creation date tell them apart.",
            BannerKind.Info, Modifier.padding(bottom = 6.dp),
        )
    }
    orgs.forEach { o ->
        androidx.compose.runtime.key(o.id) {
            val selected = o.id == selectedId
            ListCard(
                o.name, Modifier.padding(vertical = 4.dp), subtitle = WorkspaceList.subtitle(o, o.name.trim().lowercase() in shared),
                leading = { com.meldiq.android.ui.components.OrgMark(o.id, o.name, WorkspaceList.colorSeed(o), o.photoRev, size = 42.dp) },
                trailing = {
                    Column(horizontalAlignment = Alignment.End) {
                        RoleBadge(o.myRole)
                        if (selected) Icon(Icons.Rounded.CheckCircle, "Current workspace", tint = c.primary, modifier = Modifier.padding(top = 4.dp).size(18.dp))
                    }
                },
                selected = selected, onClick = { onSelect(o.id) },
            )
        }
    }
}
