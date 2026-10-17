package com.griipbuddy.orbit.ui.screens.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.griipbuddy.orbit.BuildConfig
import com.griipbuddy.orbit.GriipApp
import com.griipbuddy.orbit.R
import com.griipbuddy.orbit.data.repo.ServerAddress
import com.griipbuddy.orbit.ui.LocalContainer
import com.griipbuddy.orbit.ui.components.BannerKind
import com.griipbuddy.orbit.ui.components.ButtonTone
import com.griipbuddy.orbit.ui.components.EmptyState
import com.griipbuddy.orbit.ui.components.GlassSheet
import com.griipbuddy.orbit.ui.components.GriipTextField
import com.griipbuddy.orbit.ui.components.MenuRow
import com.griipbuddy.orbit.ui.components.PillButton
import com.griipbuddy.orbit.ui.components.StatusBanner
import com.griipbuddy.orbit.ui.containerViewModel
import com.griipbuddy.orbit.ui.screens.profile.ServerCheck
import com.griipbuddy.orbit.ui.screens.profile.checkServer
import com.griipbuddy.orbit.ui.screens.profile.hostOf
import com.griipbuddy.orbit.ui.theme.Griip
import com.griipbuddy.orbit.ui.theme.IvoryColors
import com.griipbuddy.orbit.ui.theme.Motion
import com.griipbuddy.orbit.ui.theme.ProvideGriipPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The steps of signing up or in. [depth] orders them for the slide direction. */
internal enum class AuthStep(val depth: Int) {
    Welcome(0), SignUp(1), LogIn(1), Restore(2), Link(2);

    /** Where back goes (unit-tested in `AuthFlowTest`); null on the first screen, where back leaves the app. */
    val back: AuthStep?
        get() = when (this) {
            Welcome -> null
            SignUp, LogIn -> Welcome
            Restore, Link -> LogIn
        }
}

/** How many taps on the Orbit lockup turn developer options on or off. */
private const val DEVELOPER_TAPS = 7

/**
 * Signed out: welcome, sign-up and the two ways to sign in, in the ivory palette whatever the app theme. The steps
 * share their state while the flow is shown, so going back and forth keeps what was typed. [gateway] stands in for
 * the account calls in tests; the app passes none and uses the session's own.
 */
@Composable
fun OnboardingFlow(pendingInvite: String?, gateway: AuthGateway? = null) {
    val container = LocalContainer.current
    val settings by container.settings.state.collectAsStateWithLifecycle()
    val auth = remember(container, gateway) { gateway ?: SessionAuthGateway(container.session) }
    val signUp = containerViewModel("onboarding.signup") { SignUpViewModel(auth, it.settings.current.serverUrl) }
    val restore = containerViewModel("onboarding.restore") { RestoreViewModel(auth) }
    val link = containerViewModel("onboarding.link") { LinkDeviceViewModel(auth) }
    val signingUp by signUp.state.collectAsStateWithLifecycle()
    val restoring by restore.state.collectAsStateWithLifecycle()
    var step by rememberSaveable { mutableStateOf(AuthStep.Welcome) }
    var showServer by remember { mutableStateOf(false) }
    var brandTaps by remember { mutableIntStateOf(0) }
    // People join the Orbit network without choosing a server. The server setup is for testing and self-hosting:
    // shown in test builds, with developer options on, or when this phone already uses another server.
    val serverSetup = OnboardingRules.showServerSetup(settings.developerMode, settings.serverUrl)
    val busy = signingUp.submitting || restoring.submitting

    // Back walks the steps; while keys are being made or an account is being restored it waits (leaving mid-way
    // could leave a half-registered account behind).
    BackHandler(enabled = step.back != null) { if (!busy) step.back?.let { step = it } }

    ProvideGriipPalette(IvoryColors) {
        Box(Modifier.fillMaxSize().background(Griip.colors.background)) {
            AnimatedContent(step, transitionSpec = { stepTransition(initialState, targetState) }, label = "onboarding") { s ->
                val back = { s.back?.let { step = it } ?: Unit }
                when (s) {
                    AuthStep.Welcome -> WelcomeScreen(
                        showServer = serverSetup,
                        onBrandTap = {
                            if (++brandTaps >= DEVELOPER_TAPS) {
                                brandTaps = 0
                                container.settings.update { it.copy(developerMode = !it.developerMode) }
                            }
                        },
                        onSignUp = { step = AuthStep.SignUp },
                        onLogIn = { step = AuthStep.LogIn },
                        onServer = { showServer = true },
                    )
                    AuthStep.SignUp -> SignUpScreen(signUp, settings.serverUrl, serverSetup, onBack = back, onServer = { showServer = true }, onLogIn = { step = AuthStep.LogIn })
                    AuthStep.LogIn -> LogInScreen(
                        serverSetup, onBack = back, onServer = { showServer = true },
                        onLink = { step = AuthStep.Link }, onRestore = { step = AuthStep.Restore }, onSignUp = { step = AuthStep.SignUp },
                    )
                    AuthStep.Restore -> RestoreScreen(restore, settings.serverUrl, onBack = back)
                    AuthStep.Link -> LinkDeviceScreen(link, settings.serverUrl, onBack = back)
                }
            }
        }
        if (showServer) ServerSheet { showServer = false }
    }
}

/** Deeper steps slide in from the end, going back slides the other way; both cross-fade. */
private fun stepTransition(from: AuthStep, to: AuthStep): ContentTransform {
    val forward = to.depth >= from.depth
    val shift = { full: Int -> (full / 8) * (if (forward) 1 else -1) }
    return (fadeIn(tween(Motion.STANDARD)) + slideInHorizontally(tween(Motion.STANDARD)) { shift(it) }) togetherWith
        (fadeOut(tween(Motion.QUICK)) + slideOutHorizontally(tween(Motion.STANDARD)) { -shift(it) })
}

@Composable
fun ServerSheet(onDismiss: () -> Unit) {
    val c = Griip.colors
    val container = LocalContainer.current
    val app = LocalContext.current.applicationContext as GriipApp
    val scope = rememberCoroutineScope()
    val settings by container.settings.state.collectAsStateWithLifecycle()
    var url by remember { mutableStateOf(settings.serverUrl) }
    var status by remember { mutableStateOf<ServerCheck?>(null) }
    var busy by remember { mutableStateOf(false) }
    val parsed = ServerAddress.parse(url, allowCleartext = BuildConfig.DEBUG).getOrNull()
    var others by remember { mutableStateOf<List<GriipApp.ProfileSummary>>(emptyList()) }
    LaunchedEffect(Unit) { others = withContext(Dispatchers.IO) { app.profileSummaries().filterNot { it.active } } }
    GlassSheet(onDismiss, title = stringResource(R.string.server_title)) {
        Text(stringResource(R.string.server_body), style = Griip.type.bodySmall, color = c.textSecondary)
        Spacer(Modifier.height(16.dp))
        GriipTextField(url, { url = it.trim(); status = null }, stringResource(R.string.server_address), placeholder = "https://msg.example.com", leadingIcon = Icons.Outlined.Dns)
        status?.let {
            Spacer(Modifier.height(10.dp))
            StatusBanner(it.message, if (it.ok) BannerKind.Success else BannerKind.Error)
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PillButton(stringResource(R.string.server_test), {
                busy = true
                scope.launch {
                    status = checkServer(container.session, url)
                    busy = false
                }
            }, Modifier.weight(1f), tone = ButtonTone.Glass, loading = busy, height = 50.dp)
            PillButton(stringResource(R.string.server_save), { parsed?.let { p -> container.settings.update { it.copy(serverUrl = p.url) } }; onDismiss() }, Modifier.weight(1f), showKnob = false, height = 50.dp,
                enabled = parsed != null)
        }
        if (others.isNotEmpty()) {
            Spacer(Modifier.height(18.dp))
            Text(stringResource(R.string.server_others), style = Griip.type.label, color = c.textSecondary)
            Spacer(Modifier.height(4.dp))
            others.forEach { p ->
                MenuRow(
                    Icons.Outlined.Dns, hostOf(p.serverUrl),
                    subtitle = p.username?.let { stringResource(R.string.server_switch_back, it) } ?: stringResource(R.string.server_not_signed_in),
                    tint = c.textSecondary, tintBg = c.surfaceVariant, onClick = { onDismiss(); app.switchProfile(p.id) },
                )
            }
        }
    }
}

@Composable
fun RevokedScreen(reason: String) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(), verticalArrangement = Arrangement.Center) {
        EmptyState(
            Icons.Outlined.Badge, "This device was signed out", "$reason. For your security, local data on this phone should be erased before you continue.",
            action = "Erase & start over", onAction = { (ctx.applicationContext as GriipApp).eraseEverything() }, tint = Griip.colors.danger,
        )
    }
}

/** Onboarding rules (unit-tested in `OnboardingRulesTest`). */
object OnboardingRules {
    /** The server choice is hidden from ordinary sign-up; test builds, developer options or another server show it. */
    fun showServerSetup(developerMode: Boolean, serverUrl: String, debugBuild: Boolean = com.griipbuddy.orbit.BuildConfig.DEBUG): Boolean =
        debugBuild || developerMode || !ServerAddress.same(serverUrl, com.griipbuddy.orbit.BuildConfig.DEFAULT_SERVER_URL)
}
