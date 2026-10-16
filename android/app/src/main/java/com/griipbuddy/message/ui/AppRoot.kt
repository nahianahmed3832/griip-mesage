package com.griipbuddy.message.ui

import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavBackStackEntry
import androidx.compose.animation.scaleIn
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Workspaces
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Workspaces
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.griipbuddy.message.call.CallPhase
import com.griipbuddy.message.data.repo.SessionState
import com.griipbuddy.message.di.AppContainer
import com.griipbuddy.message.ui.components.AppBackground
import com.griipbuddy.message.ui.components.GriipNavigationBar
import com.griipbuddy.message.ui.components.NavItem
import com.griipbuddy.message.ui.screens.calls.CallOverlay
import com.griipbuddy.message.ui.screens.calls.CallsScreen
import com.griipbuddy.message.ui.screens.chats.ChatsScreen
import com.griipbuddy.message.ui.screens.chats.NewChatScreen
import com.griipbuddy.message.ui.screens.conversation.ConversationInfoScreen
import com.griipbuddy.message.ui.screens.conversation.ConversationScreen
import com.griipbuddy.message.ui.screens.home.SearchScreen
import com.griipbuddy.message.ui.screens.onboarding.JoinWorkspaceScreen
import com.griipbuddy.message.ui.screens.onboarding.OnboardingFlow
import com.griipbuddy.message.ui.screens.onboarding.RevokedScreen
import com.griipbuddy.message.ui.screens.profile.DevicesScreen
import com.griipbuddy.message.ui.screens.profile.ProfileScreen
import com.griipbuddy.message.ui.screens.profile.SecurityScreen
import com.griipbuddy.message.ui.screens.profile.ServersScreen
import com.griipbuddy.message.ui.screens.profile.SettingsScreen
import com.griipbuddy.message.ui.screens.profile.UserProfileScreen
import com.griipbuddy.message.ui.screens.workspace.ActivityScreen
import com.griipbuddy.message.ui.screens.workspace.InvitesScreen
import com.griipbuddy.message.ui.screens.workspace.MembersScreen
import com.griipbuddy.message.ui.screens.workspace.TeamScreen
import com.griipbuddy.message.ui.screens.workspace.WorkspaceScreen

val LocalContainer = staticCompositionLocalOf<AppContainer> { error("no container") }

object Routes {
    /** Kept for old deep links and back stacks: it now opens the chat list. */
    const val HOME = "home"
    const val CHATS = "chats"
    const val CALLS = "calls"
    const val WORKSPACE = "workspace"
    /** The Settings tab (profile, account, privacy, appearance, notifications, calls, about). */
    const val PROFILE = "profile"
    const val JOIN = "join"
    const val NEW_CHAT = "newchat"
    const val SEARCH = "search"
    const val DEVICES = "devices"
    const val SECURITY = "security"
    /** Servers on this phone: one profile per server, switched without deleting anything. */
    const val SERVERS = "servers"
    /** Kept for older links; the settings now live on the Settings tab. */
    const val SETTINGS = "settings"
    fun conversation(id: String, thread: String? = null) = "conversation/$id" + (thread?.let { "?thread=$it" } ?: "")
    fun info(id: String) = "conversation/$id/info"
    fun team(orgId: String, teamId: String) = "team/$orgId/$teamId"
    fun members(orgId: String) = "members/$orgId"
    fun invites(orgId: String) = "invites/$orgId"
    fun activity(orgId: String) = "activity/$orgId"
    fun user(id: String) = "user/$id"
    val tabs = setOf(CHATS, CALLS, WORKSPACE, PROFILE)
}

@Composable
fun AppRoot(container: AppContainer, pendingConversation: String?, pendingInvite: String?, onPendingHandled: () -> Unit) {
    CompositionLocalProvider(LocalContainer provides container) {
        val session by container.session.state.collectAsStateWithLifecycle()
        val call by container.calls.state.collectAsStateWithLifecycle()
        AppBackground {
            when (val s = session) {
                is SessionState.SignedOut -> OnboardingFlow(pendingInvite)
                is SessionState.Revoked -> RevokedScreen(s.reason)
                is SessionState.SignedIn -> MainNav(container, pendingConversation, pendingInvite, onPendingHandled)
            }
            AnimatedVisibility(
                visible = call.phase != CallPhase.Idle,
                enter = slideInVertically(tween(260)) { it / 3 } + fadeIn(tween(200)),
                exit = slideOutVertically(tween(220)) { it / 3 } + fadeOut(tween(200)),
            ) { CallOverlay() }
        }
    }
}

@Composable
private fun MainNav(container: AppContainer, pendingConversation: String?, pendingInvite: String?, onPendingHandled: () -> Unit) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val unread by container.conversations.totalUnread().collectAsStateWithLifecycle(0)

    LaunchedEffect(pendingConversation, pendingInvite) {
        if (pendingConversation != null) {
            nav.navigate(Routes.conversation(pendingConversation))
            onPendingHandled()
        } else if (pendingInvite != null) {
            nav.navigate(Routes.JOIN + "?code=$pendingInvite")
            onPendingHandled()
        }
    }

    Column(Modifier.fillMaxSize()) {
        val motion = rememberNavMotion()
        NavHost(
            nav,
            startDestination = Routes.CHATS,
            modifier = Modifier.weight(1f),
            enterTransition = { motion.enter(this, forward = true) },
            exitTransition = { motion.exit(this, forward = true) },
            popEnterTransition = { motion.enter(this, forward = false) },
            popExitTransition = { motion.exit(this, forward = false) },
        ) {
            composable(Routes.CHATS) { ChatsScreen(nav) }
            composable(Routes.HOME) { ChatsScreen(nav) }
            composable(Routes.CALLS) { CallsScreen(nav) }
            composable(Routes.WORKSPACE) { WorkspaceScreen(nav) }
            composable(Routes.PROFILE) { ProfileScreen(nav) }
            composable(Routes.JOIN + "?code={code}") { JoinWorkspaceScreen(nav, it.arguments?.getString("code")) }
            composable(Routes.NEW_CHAT + "?mode={mode}") { NewChatScreen(nav, it.arguments?.getString("mode")) }
            composable(Routes.SEARCH) { SearchScreen(nav) }
            composable("conversation/{id}?thread={thread}") { ConversationScreen(nav, it.arguments!!.getString("id")!!, it.arguments?.getString("thread")) }
            composable("conversation/{id}/info") { ConversationInfoScreen(nav, it.arguments!!.getString("id")!!) }
            composable("team/{orgId}/{teamId}") { TeamScreen(nav, it.arguments!!.getString("orgId")!!, it.arguments!!.getString("teamId")!!) }
            composable("members/{orgId}") { MembersScreen(nav, it.arguments!!.getString("orgId")!!) }
            composable("invites/{orgId}") { InvitesScreen(nav, it.arguments!!.getString("orgId")!!) }
            composable("activity/{orgId}") { ActivityScreen(nav, it.arguments!!.getString("orgId")!!) }
            composable("user/{id}") { UserProfileScreen(nav, it.arguments!!.getString("id")!!) }
            composable(Routes.DEVICES) { DevicesScreen(nav) }
            composable(Routes.SECURITY) { SecurityScreen(nav) }
            composable(Routes.SERVERS) { ServersScreen(nav) }
            composable(Routes.SETTINGS) { SettingsScreen(nav) }
        }
        if (route in Routes.tabs || route == Routes.HOME) {
            GriipNavigationBar(
                items = listOf(
                    NavItem(Routes.CHATS, "Chats", Icons.AutoMirrored.Outlined.Chat, Icons.AutoMirrored.Rounded.Chat, badge = unread),
                    NavItem(Routes.CALLS, "Calls", Icons.Outlined.Call, Icons.Rounded.Call),
                    NavItem(Routes.WORKSPACE, "Workspace", Icons.Outlined.Workspaces, Icons.Rounded.Workspaces),
                    NavItem(Routes.PROFILE, "Settings", Icons.Outlined.Settings, Icons.Rounded.Settings),
                ),
                selectedRoute = if (route == Routes.HOME) Routes.CHATS else route,
                onSelect = { item -> nav.switchTab(item.route) },
            )
        }
    }
}

fun NavHostController.switchTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Suppress("unused")
private val noTransition: EnterTransition = EnterTransition.None
@Suppress("unused")
private val noExit: ExitTransition = ExitTransition.None

/**
 * Screen transitions. Tab to tab: a short fade-through (the tabs keep their state and scroll position, so nothing
 * slides). Opening a screen: it slides in from the end edge while the previous one shifts slightly back; Back reverses
 * that. Directions follow the layout direction (right-to-left languages mirror them). With the system's animations
 * turned off (accessibility, or "Remove animations"), screens switch instantly.
 */
class NavMotion(private val animate: Boolean) {
    private fun tabs(scope: AnimatedContentTransitionScope<NavBackStackEntry>) =
        NavMotionRules.isTabSwitch(scope.initialState.destination.route, scope.targetState.destination.route)

    fun enter(scope: AnimatedContentTransitionScope<NavBackStackEntry>, forward: Boolean): EnterTransition = when {
        !animate -> EnterTransition.None
        tabs(scope) -> fadeIn(tween(180, delayMillis = 60)) + scaleIn(tween(240, delayMillis = 60), initialScale = 0.985f)
        forward -> scope.slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Start, tween(260)) { it / 4 } + fadeIn(tween(200))
        else -> scope.slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.End, tween(260)) { it / 10 } + fadeIn(tween(220))
    }

    fun exit(scope: AnimatedContentTransitionScope<NavBackStackEntry>, forward: Boolean): ExitTransition = when {
        !animate -> ExitTransition.None
        tabs(scope) -> fadeOut(tween(90))
        forward -> scope.slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Start, tween(260)) { it / 10 } + fadeOut(tween(200))
        else -> scope.slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.End, tween(240)) { it / 4 } + fadeOut(tween(180))
    }
}

/** Pure navigation-motion rules (unit-tested in `NavMotionTest`). */
object NavMotionRules {
    /** Switching between the bottom-bar tabs (Chats and its alias Home count as one tab). */
    fun isTabSwitch(from: String?, to: String?): Boolean {
        fun tab(r: String?) = if (r == Routes.HOME) Routes.CHATS else r
        return tab(from) in Routes.tabs && tab(to) in Routes.tabs
    }

    /** Animations play unless the system animator scale is 0 ("Remove animations" / animations off). */
    fun animationsEnabled(animatorScale: Float): Boolean = animatorScale > 0f
}

@Composable
private fun rememberNavMotion(): NavMotion {
    val ctx = LocalContext.current
    return remember {
        val scale = runCatching { android.provider.Settings.Global.getFloat(ctx.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) }.getOrDefault(1f)
        NavMotion(NavMotionRules.animationsEnabled(scale))
    }
}
