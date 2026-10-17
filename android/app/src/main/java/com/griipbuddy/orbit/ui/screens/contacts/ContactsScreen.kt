package com.griipbuddy.orbit.ui.screens.contacts

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.GroupAdd
import androidx.compose.material.icons.outlined.PersonSearch
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Workspaces
import androidx.compose.material.icons.rounded.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.griipbuddy.orbit.R
import com.griipbuddy.orbit.data.db.ConversationEntity
import com.griipbuddy.orbit.data.db.UserEntity
import com.griipbuddy.orbit.data.repo.ConversationRepository
import com.griipbuddy.orbit.ui.LocalContainer
import com.griipbuddy.orbit.ui.Routes
import com.griipbuddy.orbit.ui.components.CircleIconButton
import com.griipbuddy.orbit.ui.components.EmptyState
import com.griipbuddy.orbit.ui.components.GlassCard
import com.griipbuddy.orbit.ui.components.LargeHeader
import com.griipbuddy.orbit.ui.components.ListCard
import com.griipbuddy.orbit.ui.components.MenuRow
import com.griipbuddy.orbit.ui.components.SearchPill
import com.griipbuddy.orbit.ui.components.SectionHeader
import com.griipbuddy.orbit.ui.components.UserAvatar
import com.griipbuddy.orbit.ui.theme.Griip
import com.griipbuddy.orbit.ui.theme.Space

/** Who appears on the Contacts tab (unit-tested in `ContactsRulesTest`). */
object ContactsRules {
    /**
     * People you talk to: everyone you have a direct chat with, then members of your groups, without you or anyone
     * unnamed, sorted by name. Built only from conversations on this phone; no address book is read or uploaded.
     */
    fun people(convs: List<ConversationEntity>, users: Map<String, UserEntity>, me: String?, query: String = ""): List<UserEntity> {
        val ids = LinkedHashSet<String>()
        convs.filter { it.type == "DM" && !it.removed }.forEach { c -> ConversationRepository.peer(c, me)?.id?.let(ids::add) }
        convs.filter { it.type == "GROUP" && !it.removed }.forEach { c -> ConversationRepository.members(c).forEach { m -> ids += m.id } }
        val q = query.trim().removePrefix("@")
        return ids.filter { it != me }.mapNotNull { users[it] }.filter { it.displayName.isNotBlank() }
            .filter { q.isEmpty() || it.displayName.contains(q, ignoreCase = true) || it.username.contains(q, ignoreCase = true) }
            .sortedBy { it.displayName.lowercase() }
    }
}

/**
 * Contacts (2026-10-17): find people (name, @username, @name:server or an exact phone number they allow), the people
 * you already talk to, and the way into workspaces and invitations. No address book upload.
 */
@Composable
fun ContactsScreen(nav: NavHostController) {
    val container = LocalContainer.current
    val c = Griip.colors
    val me = container.session.session?.userId
    val convs by container.conversations.observeAll().collectAsStateWithLifecycle(emptyList())
    val users by container.db.users().observeAll().collectAsStateWithLifecycle(emptyList())
    var query by rememberSaveable { mutableStateOf("") }
    val byId = users.associateBy { it.id }
    val people = ContactsRules.people(convs, byId, me, query)

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp + com.griipbuddy.orbit.ui.LocalTabBarPadding.current)) {
        item {
            LargeHeader(stringResource(R.string.contacts_title)) {
                CircleIconButton(Icons.Rounded.Search, stringResource(R.string.chats_search), { nav.navigate(Routes.SEARCH) })
                CircleIconButton(Icons.Outlined.GroupAdd, stringResource(R.string.chats_new_group), { nav.navigate(Routes.NEW_CHAT + "?mode=group") })
            }
        }
        item { SearchPill(query, { query = it }, stringResource(R.string.chats_filter_hint), Modifier.padding(horizontal = Space.gutter, vertical = 6.dp)) }
        item {
            GlassCard(Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = 6.dp), contentPadding = PaddingValues(4.dp)) {
                MenuRow(Icons.Outlined.PersonSearch, stringResource(R.string.contacts_find), subtitle = stringResource(R.string.contacts_find_sub), onClick = { nav.navigate(Routes.NEW_CHAT + "?mode=dm") })
                MenuRow(Icons.Outlined.Workspaces, stringResource(R.string.contacts_workspace), subtitle = stringResource(R.string.contacts_workspace_sub), tint = c.lavender, tintBg = c.lavenderSoft, onClick = { nav.navigate(Routes.WORKSPACE) })
                MenuRow(Icons.Outlined.QrCodeScanner, stringResource(R.string.contacts_invite), tint = c.peach, tintBg = c.peachSoft, onClick = { nav.navigate(Routes.JOIN + "?code=") })
            }
        }
        item { SectionHeader(stringResource(R.string.contacts_people), Modifier.padding(top = 10.dp, bottom = 6.dp)) }
        if (people.isEmpty()) item { EmptyState(Icons.Outlined.PersonSearch, stringResource(R.string.contacts_empty), stringResource(R.string.contacts_empty_body)) }
        items(people, key = { it.id }) { u ->
            ListCard(
                u.displayName, Modifier.padding(horizontal = 8.dp, vertical = 1.dp), subtitle = "@${u.username}",
                leading = { UserAvatar(u.id, u.displayName, u.avatarColor, u.photoRev, size = 44.dp, online = u.online) },
                onClick = { nav.navigate(Routes.user(u.id)) },
            )
        }
    }
}
