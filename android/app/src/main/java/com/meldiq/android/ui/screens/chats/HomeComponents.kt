package com.meldiq.android.ui.screens.chats

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.meldiq.android.R
import com.meldiq.android.ui.components.CircleIconButton
import com.meldiq.android.ui.theme.Griip
import com.meldiq.android.ui.theme.Shapes
import com.meldiq.android.ui.theme.Space

/** One entry of a tab's overflow menu (the DMs tab). */
data class HomeMenuItem(val label: String, val icon: ImageVector, val onClick: () -> Unit)

/**
 * A tab's top bar (the DMs tab): a large title, search (the app's search screen), and an overflow menu of what the app
 * already does (new group, start a call, call history, contacts, join with an invite). Sits under the status bar.
 */
@Composable
fun HomeTopBar(title: String, onSearch: () -> Unit, menu: List<HomeMenuItem>, modifier: Modifier = Modifier) {
    val c = Griip.colors
    var open by remember { mutableStateOf(false) }
    Row(
        modifier.fillMaxWidth().statusBarsPadding().padding(start = Space.gutter + 4.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title, style = Griip.type.display, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        CircleIconButton(Icons.Rounded.Search, stringResource(R.string.chats_search), onSearch, background = c.surface, size = 44.dp)
        Spacer(Modifier.width(4.dp))
        Box {
            CircleIconButton(Icons.Rounded.MoreVert, stringResource(R.string.home_more), { open = true }, background = c.surface, size = 44.dp)
            DropdownMenu(open, { open = false }, containerColor = c.glassStrong, shape = Shapes.card) {
                menu.forEach { item ->
                    DropdownMenuItem(
                        text = { Text(item.label, style = Griip.type.bodyMedium, color = c.textPrimary) },
                        leadingIcon = { Icon(item.icon, null, tint = c.textSecondary) },
                        onClick = {
                            open = false
                            item.onClick()
                        },
                    )
                }
            }
        }
    }
}
