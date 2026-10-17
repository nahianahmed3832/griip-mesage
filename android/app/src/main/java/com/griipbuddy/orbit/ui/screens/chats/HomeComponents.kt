package com.griipbuddy.orbit.ui.screens.chats

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.griipbuddy.orbit.R
import com.griipbuddy.orbit.data.db.ConversationEntity
import com.griipbuddy.orbit.data.db.UserEntity
import com.griipbuddy.orbit.data.repo.ConversationRepository
import com.griipbuddy.orbit.ui.components.CircleIconButton
import com.griipbuddy.orbit.ui.components.UserAvatar
import com.griipbuddy.orbit.ui.components.pressable
import com.griipbuddy.orbit.ui.theme.Griip
import com.griipbuddy.orbit.ui.theme.Shapes
import com.griipbuddy.orbit.ui.theme.Space

/** A person in the home screen's quick-contacts strip: someone you have a direct chat with. */
@Immutable
data class QuickContact(
    val conversationId: String,
    val userId: String,
    val name: String,
    val colorIndex: Int,
    val photoRev: Long?,
    /** Known presence (null: not known to this phone). */
    val online: Boolean?,
    val unread: Int,
)

/**
 * Who the quick-contacts strip shows (unit-tested in `HomeRulesTest`). Built only from direct chats already on this
 * phone, most recent first (the chat list's own order): no stories, no address book, nobody invented.
 */
object HomeRules {
    const val MAX_QUICK_CONTACTS = 12

    fun quickContacts(
        convs: List<ConversationEntity>,
        users: Map<String, UserEntity>,
        me: String?,
        limit: Int = MAX_QUICK_CONTACTS,
    ): List<QuickContact> = convs.asSequence()
        .filter { it.type == "DM" && !it.removed && !ChatListRules.isArchived(it) }
        .mapNotNull { conv ->
            val peer = ConversationRepository.peer(conv, me) ?: return@mapNotNull null
            val user = users[peer.id]
            val name = user?.displayName?.takeIf { it.isNotBlank() } ?: peer.displayName.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            QuickContact(
                conversationId = conv.id,
                userId = peer.id,
                name = name,
                colorIndex = user?.avatarColor ?: peer.avatarColor,
                photoRev = user?.photoRev ?: peer.photoRev,
                online = user?.online ?: peer.online,
                unread = conv.unreadCount,
            )
        }
        .distinctBy { it.userId }
        .take(limit)
        .toList()

    /** The short name under an avatar: the first word of the display name. */
    fun shortName(name: String): String = name.trim().split(Regex("\\s+")).firstOrNull().orEmpty()
}

/** One entry of the home screen's overflow menu. */
data class HomeMenuItem(val label: String, val icon: ImageVector, val onClick: () -> Unit)

/**
 * The home top bar: a large "Chats" title, search (the app's search screen), and an overflow menu of what the app
 * already does (new group, start a call, join with an invite). Sits under the status bar.
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

private val QuickAvatar = 58.dp
private val QuickItemWidth = 72.dp

/**
 * The quick-contacts strip: a "New" shortcut, then the people you chat with directly. An accent ring marks a chat with
 * unread messages and a dot marks someone online; both only when this phone knows it.
 */
@Composable
fun QuickContactsStrip(contacts: List<QuickContact>, onNew: () -> Unit, onOpen: (QuickContact) -> Unit, modifier: Modifier = Modifier) {
    val c = Griip.colors
    val newLabel = stringResource(R.string.home_new)
    val newDescription = stringResource(R.string.chats_new_chat)
    LazyRow(
        modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = Space.gutter),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item(key = "new") {
            QuickItem(newLabel, newDescription, onNew) {
                Box(
                    Modifier.size(QuickAvatar).clip(Shapes.hero).background(c.surface).border(1.dp, c.border, Shapes.hero),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.Add, null, tint = c.textPrimary, modifier = Modifier.size(24.dp)) }
            }
        }
        items(contacts, key = { it.conversationId }) { person ->
            val description = buildList {
                add(stringResource(R.string.home_chat_with, person.name))
                if (person.unread > 0) add(stringResource(R.string.home_unread_count, person.unread))
                if (person.online == true) add(stringResource(R.string.home_online))
            }.joinToString(", ")
            QuickItem(HomeRules.shortName(person.name), description, { onOpen(person) }) {
                val ring = person.unread > 0
                Box(
                    Modifier.size(QuickAvatar)
                        .then(if (ring) Modifier.border(2.dp, Brush.linearGradient(listOf(c.accentStart, c.accentEnd)), CircleShape) else Modifier)
                        .padding(if (ring) 4.dp else 0.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    UserAvatar(person.userId, person.name, person.colorIndex, person.photoRev, size = if (ring) QuickAvatar - 8.dp else QuickAvatar, online = person.online)
                }
            }
        }
    }
}

/** One strip entry: read by TalkBack as a single button with [description] (the avatar's own labels are replaced). */
@Composable
private fun QuickItem(label: String, description: String, onTap: () -> Unit, avatar: @Composable () -> Unit) {
    val c = Griip.colors
    Column(
        Modifier.width(QuickItemWidth).clip(Shapes.card).pressable(onTap)
            .clearAndSetSemantics {
                contentDescription = description
                role = Role.Button
                onClick {
                    onTap()
                    true
                }
            }
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        avatar()
        Spacer(Modifier.height(6.dp))
        Text(
            label, style = Griip.type.caption.copy(fontWeight = FontWeight.Medium), color = c.textSecondary,
            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
        )
    }
}
