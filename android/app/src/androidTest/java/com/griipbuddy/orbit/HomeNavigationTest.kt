package com.griipbuddy.orbit

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.MarkChatUnread
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.griipbuddy.orbit.data.db.ConversationEntity
import com.griipbuddy.orbit.ui.components.FloatingNavBar
import com.griipbuddy.orbit.ui.components.NavItem
import com.griipbuddy.orbit.ui.screens.home.ChannelGroup
import com.griipbuddy.orbit.ui.screens.home.ChannelGroupHeader
import com.griipbuddy.orbit.ui.screens.home.HomeSectionHeader
import com.griipbuddy.orbit.ui.screens.home.QuickAccessRow
import com.griipbuddy.orbit.ui.screens.home.QuickCard
import com.griipbuddy.orbit.ui.theme.CocoaColors
import com.griipbuddy.orbit.ui.theme.GriipTheme
import com.griipbuddy.orbit.ui.theme.ProvideGriipPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The UI-002 navigation and Home controls on a real phone: the three tabs, the Unreads switch, and collapsible
 * sections, including what TalkBack is told. Stateless pieces only; no account or server is needed.
 */
@RunWith(AndroidJUnit4::class)
class HomeNavigationTest {
    @get:Rule val compose = createComposeRule()

    private fun show(content: @Composable () -> Unit) = compose.setContent { GriipTheme { ProvideGriipPalette(CocoaColors) { content() } } }

    private val clickable = SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick)
    private fun state(text: String) = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, text)

    @Test
    fun theTabBarHasExactlyHomeDmsAndActivity() {
        var picked: String? = null
        show {
            FloatingNavBar(
                listOf(
                    NavItem("home", "Home", Icons.Outlined.Home, Icons.Rounded.Home),
                    NavItem("chats", "DMs", Icons.AutoMirrored.Outlined.Chat, Icons.AutoMirrored.Rounded.Chat, badge = 3),
                    NavItem("activity", "Activity", Icons.Outlined.Notifications, Icons.Rounded.Notifications),
                ),
                selectedRoute = "home", onSelect = { picked = it.route },
            )
        }
        compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)).assertCountEquals(3)
        compose.onNodeWithContentDescription("Home").assertIsSelected().assert(clickable)
        compose.onNodeWithContentDescription("Activity").assertIsNotSelected().assert(clickable)
        compose.onNodeWithContentDescription("DMs, 3").assertIsNotSelected().assert(clickable).performClick()
        assertEquals("chats", picked)
        compose.onAllNodesWithContentDescription("Calls").assertCountEquals(0) // Calls is not a tab any more
    }

    @Test
    fun theUnreadsCardIsASwitchAndTheOthersAreButtons() {
        var on by mutableStateOf(false)
        var calls = 0
        show {
            QuickAccessRow(
                listOf(
                    QuickCard(Icons.Outlined.MarkChatUnread, "Unreads", "2 unread", active = true, selected = on, onClick = { on = !on }),
                    QuickCard(Icons.Outlined.Call, "Calls", "History", active = false, onClick = { calls++ }),
                ),
            )
        }
        compose.onNodeWithText("Unreads").assertIsOff().performClick()
        compose.onNodeWithText("Unreads").assertIsOn()
        compose.onNodeWithText("Calls").assert(clickable).performClick()
        assertEquals(1, calls)
    }

    @Test
    fun collapsibleSectionsSayWhetherTheyAreOpen() {
        var open by mutableStateOf(true)
        show { HomeSectionHeader("Mentions", expanded = open, onToggle = { open = !open }) }
        compose.onNodeWithContentDescription("Mentions").assert(state("Expanded")).assert(clickable).performClick()
        assertFalse(open)
        compose.onNodeWithContentDescription("Mentions").assert(state("Collapsed"))
    }

    @Test
    fun aCollapsedChannelGroupCountsItsUnreadChannels() {
        val group = ChannelGroup("TEAM:t1", "Mobile", "TEAM", listOf(channel("alpha", unread = 2), channel("zeta")))
        var open by mutableStateOf(false)
        show { ChannelGroupHeader(group, open, { open = !open }) }
        compose.onNodeWithContentDescription("Mobile, Unread: 1").assert(state("Collapsed")).assert(clickable).performClick()
        compose.onNodeWithContentDescription("Mobile").assert(state("Expanded"))
    }

    private fun channel(id: String, unread: Int = 0) = ConversationEntity(
        id = id, type = "CHANNEL", orgId = "o1", scope = "TEAM", scopeId = "t1", scopeName = "Mobile", kind = "PUBLIC", name = id,
        topic = null, archived = false, memberCount = 3, membersJson = "[]", joined = true, myRole = null, canPost = true,
        canManage = false, activeCallId = null, activeCallVideo = false, serverUpdatedAt = 1, unreadCount = unread,
    )
}
