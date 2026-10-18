package com.griipbuddy.orbit.ui

import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Notifications
import com.griipbuddy.orbit.ui.theme.CocoaColors
import com.griipbuddy.orbit.ui.theme.DarkSurfaceSystemBars
import com.griipbuddy.orbit.ui.theme.ProvideGriipPalette
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.Alignment
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.griipbuddy.orbit.call.CallPhase
import com.griipbuddy.orbit.data.repo.SessionState
import com.griipbuddy.orbit.di.AppContainer
import com.griipbuddy.orbit.ui.components.AppBackground
import com.griipbuddy.orbit.ui.components.FloatingNavBar
import com.griipbuddy.orbit.ui.components.NavItem
import com.griipbuddy.orbit.ui.screens.calls.CallOverlay
import com.griipbuddy.orbit.ui.screens.calls.CallsScreen
import com.griipbuddy.orbit.ui.screens.chats.ChatListRules
import com.griipbuddy.orbit.ui.screens.chats.ChatsScreen
import com.griipbuddy.orbit.ui.screens.chats.DmListRequest
import com.griipbuddy.orbit.ui.screens.home.ActivityFeedScreen
import com.griipbuddy.orbit.ui.screens.home.WorkspaceHomeRules
import com.griipbuddy.orbit.ui.screens.home.WorkspaceHomeScreen
import com.griipbuddy.orbit.ui.screens.chats.NewChatScreen
import com.griipbuddy.orbit.ui.screens.conversation.ConversationInfoScreen
import com.griipbuddy.orbit.ui.screens.conversation.ConversationScreen
import com.griipbuddy.orbit.ui.screens.home.SearchScreen
import com.griipbuddy.orbit.ui.screens.onboarding.JoinWorkspaceScreen
import com.griipbuddy.orbit.ui.screens.onboarding.OnboardingFlow
import com.griipbuddy.orbit.ui.screens.onboarding.RevokedScreen
import com.griipbuddy.orbit.ui.screens.profile.DevicesScreen
import com.griipbuddy.orbit.ui.screens.profile.SecurityScreen
import com.griipbuddy.orbit.ui.screens.profile.ServersScreen
import com.griipbuddy.orbit.ui.screens.profile.UserProfileScreen
import com.griipbuddy.orbit.ui.screens.workspace.ActivityScreen
import com.griipbuddy.orbit.ui.screens.workspace.InvitesScreen
import com.griipbuddy.orbit.ui.screens.workspace.MembersScreen
import com.griipbuddy.orbit.ui.screens.workspace.TeamScreen
import com.griipbuddy.orbit.ui.screens.workspace.WorkspaceScreen

val LocalContainer = staticCompositionLocalOf<AppContainer> { error("no container") }

/** Space the tab bar covers at the bottom of the tab screens (Home, DMs, Activity). Other screens don't use it. */
val LocalTabBarPadding = androidx.compose.runtime.compositionLocalOf { 0.dp }

object Routes {
    /** The Home tab: the selected workspace at a glance (UI-002). The start screen. */
    const val HOME = "home"
    /** The DMs tab: direct messages and groups (the chat list; the route keeps its old name for back stacks). */
    const val CHATS = "chats"
    /** The Activity tab: @mentions, missed calls, the workspace change log. Not [activity], one workspace's log. */
    const val ACTIVITY = "activity"
    /** Call history and new calls; opens from Home's Calls card, Activity, the DMs menu and the profile menu. */
    const val CALLS = "calls"
    const val WORKSPACE = "workspace"
    /** Settings (profile, account, privacy, appearance, notifications, calls, about), from your avatar on Home. */
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
    const val EDIT_PROFILE = "profile/edit"
    const val ACCOUNTS = "accounts"
    const val CHAT_SETTINGS = "settings/chats"
    const val NOTIFICATION_SETTINGS = "settings/notifications"
    const val CALL_SETTINGS = "settings/calls"
    const val LANGUAGE = "settings/language"
    const val STORAGE = "settings/storage"
    const val CONTACTS = "contacts"
    const val PRIVACY = "privacy"
    const val BLOCKED = "blocked"
    fun conversation(id: String, thread: String? = null) = "conversation/$id" + (thread?.let { "?thread=$it" } ?: "")
    fun info(id: String) = "conversation/$id/info"
    fun team(orgId: String, teamId: String) = "team/$orgId/$teamId"
    fun members(orgId: String) = "members/$orgId"
    fun invites(orgId: String) = "invites/$orgId"
    fun activity(orgId: String) = "activity/$orgId"
    fun user(id: String) = "user/$id"
    /**
     * The bottom bar (UI-002): exactly Home, DMs and Activity. Calls, Contacts, Settings and the Workspace screen open
     * from Home, its menus and the DMs menu, with Back.
     */
    val tabs = setOf(HOME, CHATS, ACTIVITY)
}

@Composable
fun AppRoot(container: AppContainer, pendingConversation: String?, pendingInvite: String?, onPendingHandled: () -> Unit) {
    CompositionLocalProvider(LocalContainer provides container) {
        val session by container.session.state.collectAsStateWithLifecycle()
        val call by container.calls.state.collectAsStateWithLifecycle()
        val minimized by container.calls.minimized.collectAsStateWithLifecycle()
        // In picture-in-picture only the call is drawn (CallPip), on top of the app, which stays composed underneath:
        // replacing the app with the call view used to drop navigation and the minimised state on the way back (UI-003).
        val pip by com.griipbuddy.orbit.call.CallPip.active.collectAsStateWithLifecycle()
        val inCall = call.phase != CallPhase.Idle
        val showPanel = inCall && minimized && call.phase != CallPhase.Incoming && !pip
        var panelRoutes by remember { mutableStateOf(false) }
        // Passing call notices ("Screen sharing stopped"), whether the call is full screen or minimised.
        val appContext = LocalContext.current
        LaunchedEffect(call.notice) { call.notice?.let { android.widget.Toast.makeText(appContext, it, android.widget.Toast.LENGTH_SHORT).show(); container.calls.clearNotice() } }
        SignedInPalette(session is SessionState.SignedIn) {
            Box(Modifier.fillMaxSize()) {
                AppBackground {
                    Column(Modifier.fillMaxSize()) {
                        // The minimised call takes its own space above the app (UI-003): the app moves down instead of
                        // being covered, so no back arrow or header action ends up under the call.
                        if (showPanel) {
                            com.griipbuddy.orbit.ui.screens.calls.MiniCallPanel(
                                call,
                                onExpand = container.calls::expand,
                                onMic = { container.calls.toggleMic() },
                                onAudio = { if (com.griipbuddy.orbit.ui.screens.calls.CallControlsRules.opensPicker(call)) panelRoutes = true else container.calls.toggleSpeaker() },
                                onEnd = container.calls::hangup,
                            )
                        }
                        Box(Modifier.weight(1f).fillMaxWidth().then(if (showPanel) Modifier.consumeWindowInsets(WindowInsets.statusBars) else Modifier)) {
                            when (val s = session) {
                                is SessionState.SignedOut -> OnboardingFlow(pendingInvite)
                                is SessionState.Revoked -> RevokedScreen(s.reason)
                                // Orbit 2.0: a new recovery key, the start choice or a legacy account's move come before the app.
                                is SessionState.SignedIn -> com.griipbuddy.orbit.ui.screens.account.AccountGate(container) { join ->
                                    MainNav(container, pendingConversation, pendingInvite ?: join, onPendingHandled)
                                }
                            }
                        }
                    }
                    AnimatedVisibility(
                        visible = inCall && !pip && (!minimized || call.phase == CallPhase.Incoming),
                        enter = slideInVertically(tween(260)) { it / 3 } + fadeIn(tween(200)),
                        exit = slideOutVertically(tween(220)) { it / 3 } + fadeOut(tween(200)),
                    ) { CallOverlay() }
                }
                if (pip && inCall) com.griipbuddy.orbit.ui.screens.calls.CallPipView(call)
                if (panelRoutes && showPanel) {
                    com.griipbuddy.orbit.ui.screens.calls.AudioRouteSheet(
                        call, onPick = { container.calls.selectAudioRoute(it); panelRoutes = false },
                        onDismiss = { panelRoutes = false; container.calls.clearRouteError() },
                    )
                }
            }
        }
    }
}

/**
 * Orbit's signed-in identity (UI-002, owner's decision 2026-10-18): every signed-in screen, sheet, dialog and call screen
 * is drawn in the Cocoa palette, whatever the app theme, with light system-bar icons. Signed out, the screens keep
 * their own palette (Ivory, UI-001).
 */
@Composable
private fun SignedInPalette(signedIn: Boolean, content: @Composable () -> Unit) {
    if (!signedIn) {
        content()
        return
    }
    ProvideGriipPalette(CocoaColors) {
        DarkSurfaceSystemBars()
        content()
    }
}

@Composable
private fun MainNav(container: AppContainer, pendingConversation: String?, pendingInvite: String?, onPendingHandled: () -> Unit) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val conversations = remember(container) { container.conversations.observeAll() }
    val convs by conversations.collectAsStateWithLifecycle(emptyList())
    // Home's "See all" opens the DMs tab filtered to unread.
    val dmRequest = remember { DmListRequest() }

    LaunchedEffect(pendingConversation, pendingInvite) {
        if (pendingConversation != null) {
            nav.navigate(Routes.conversation(pendingConversation))
            onPendingHandled()
        } else if (pendingInvite != null) {
            nav.navigate(Routes.JOIN + "?code=$pendingInvite")
            onPendingHandled()
        }
    }

    // The tab bar is drawn over the screens, not beside them, so every screen keeps the same height whether or not the
    // bar shows. (Before 2026-10-17 the bar's space came and went with it: opening a chat made the chat list taller for
    // a moment, which clamped its scroll position near the end, and the list came back shifted.) Tab screens add
    // LocalTabBarPadding below their content instead.
    val density = androidx.compose.ui.platform.LocalDensity.current
    var barHeight by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
    Box(Modifier.fillMaxSize()) {
        val motion = rememberNavMotion()
        androidx.compose.runtime.CompositionLocalProvider(LocalTabBarPadding provides with(density) { barHeight.toDp() }) {
        NavHost(
            nav,
            startDestination = Routes.HOME,
            modifier = Modifier.fillMaxSize(),
            enterTransition = { motion.enter(this, forward = true) },
            exitTransition = { motion.exit(this, forward = true) },
            popEnterTransition = { motion.enter(this, forward = false) },
            popExitTransition = { motion.exit(this, forward = false) },
        ) {
            composable(Routes.HOME) {
                WorkspaceHomeScreen(nav, onSeeAllUnreadDms = {
                    dmRequest.open(ChatListRules.UNREAD)
                    nav.switchTab(Routes.CHATS)
                })
            }
            composable(Routes.CHATS) { ChatsScreen(nav, dmRequest) }
            composable(Routes.ACTIVITY) { ActivityFeedScreen(nav) }
            composable(Routes.CALLS) { CallsScreen(nav) }
            composable(Routes.WORKSPACE) { WorkspaceScreen(nav) }
            composable(Routes.CONTACTS) { com.griipbuddy.orbit.ui.screens.contacts.ContactsScreen(nav) }
            composable(Routes.PROFILE) { com.griipbuddy.orbit.ui.screens.profile.SettingsHub(nav) }
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
            composable(Routes.SETTINGS) { com.griipbuddy.orbit.ui.screens.profile.SettingsHub(nav) }
            composable(Routes.EDIT_PROFILE) { com.griipbuddy.orbit.ui.screens.profile.EditProfileScreen(nav) }
            composable(Routes.ACCOUNTS) { com.griipbuddy.orbit.ui.screens.profile.AccountsScreen(nav) }
            composable(Routes.CHAT_SETTINGS) { com.griipbuddy.orbit.ui.screens.profile.ChatSettingsScreen(nav) }
            composable(Routes.NOTIFICATION_SETTINGS) { com.griipbuddy.orbit.ui.screens.profile.NotificationSettingsScreen(nav) }
            composable(Routes.CALL_SETTINGS) { com.griipbuddy.orbit.ui.screens.profile.CallSettingsScreen(nav) }
            composable(Routes.LANGUAGE) { com.griipbuddy.orbit.ui.screens.profile.LanguageScreen(nav) }
            composable(Routes.STORAGE) { com.griipbuddy.orbit.ui.screens.profile.StorageScreen(nav) }
            composable(Routes.PRIVACY) { com.griipbuddy.orbit.ui.screens.profile.PrivacyScreen(nav) }
            composable(Routes.BLOCKED) { com.griipbuddy.orbit.ui.screens.profile.BlockedUsersScreen(nav) }
        }
        }
        if (route in Routes.tabs) {
            // The floating bar (UI-002). No action button: each tab offers its own New action where an existing flow
            // supports it.
            val homeBadge = remember(convs) { WorkspaceHomeRules.homeBadge(convs) }
            val dmBadge = remember(convs) { WorkspaceHomeRules.dmBadge(convs) }
            val activityBadge = remember(convs) { WorkspaceHomeRules.activityBadge(convs) }
            FloatingNavBar(
                items = listOf(
                    NavItem(Routes.HOME, androidx.compose.ui.res.stringResource(com.griipbuddy.orbit.R.string.nav_home), Icons.Outlined.Home, Icons.Rounded.Home, badge = homeBadge),
                    NavItem(Routes.CHATS, androidx.compose.ui.res.stringResource(com.griipbuddy.orbit.R.string.nav_dms), Icons.AutoMirrored.Outlined.Chat, Icons.AutoMirrored.Rounded.Chat, badge = dmBadge),
                    NavItem(Routes.ACTIVITY, androidx.compose.ui.res.stringResource(com.griipbuddy.orbit.R.string.nav_activity), Icons.Outlined.Notifications, Icons.Rounded.Notifications, badge = activityBadge),
                ),
                selectedRoute = route,
                onSelect = { item -> nav.switchTab(item.route) },
                modifier = Modifier.align(Alignment.BottomCenter).onSizeChanged { barHeight = it.height },
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
    /** Switching between the bottom-bar tabs (Home, DMs, Activity). */
    fun isTabSwitch(from: String?, to: String?): Boolean = from in Routes.tabs && to in Routes.tabs

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
