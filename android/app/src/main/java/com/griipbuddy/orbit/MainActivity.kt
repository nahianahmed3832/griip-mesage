package com.griipbuddy.orbit

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.griipbuddy.orbit.call.CallPhase
import com.griipbuddy.orbit.data.repo.SessionState
import com.griipbuddy.orbit.ui.AppRoot
import com.griipbuddy.orbit.ui.scan.InviteCodes
import com.griipbuddy.orbit.ui.theme.GriipTheme
import com.griipbuddy.orbit.ui.theme.LocalSystemBarRequests
import com.griipbuddy.orbit.ui.theme.SystemBarRequests
import com.griipbuddy.orbit.ui.theme.ThemeMode
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MainActivity : ComponentActivity() {
    // Picture-in-picture during a call (CallPip): entered when leaving Orbit; Android 12+ enters it automatically.
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val s = (application as GriipApp).container?.calls?.state?.value ?: return
        if (Build.VERSION.SDK_INT in 26..30 && com.griipbuddy.orbit.call.CallPip.eligible(s.phase) && com.griipbuddy.orbit.call.CallPip.supported(this)) {
            com.griipbuddy.orbit.call.CallPip.params(this, s)?.let { runCatching { enterPictureInPictureMode(it) } }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        com.griipbuddy.orbit.call.CallPip.active.value = isInPictureInPictureMode
    }

    // The app's own language on Android 12 and older (Android 13+ handles it itself). docs/LANGUAGES.md
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(com.griipbuddy.orbit.ui.AppLanguage.wrap(newBase))
    }

    private var pendingConversation by mutableStateOf<String?>(null)
    private var pendingInvite by mutableStateOf<String?>(null)

    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen() // Orbit launch screen until the first frame (Android 12+ system splash, same look on 8–11)
        super.onCreate(savedInstanceState)
        val app = application as GriipApp
        handleIntent(intent)
        setContent {
            val gen by app.generation.collectAsStateWithLifecycle()
            // Read here, keyed on the generation, so this whole scope recomposes with the new container after
            // an erase or a profile switch (a read only inside a nested lambda kept the old one).
            val container = androidx.compose.runtime.remember(gen) { app.container!! }
            val settings by container.settings.state.collectAsStateWithLifecycle()
            val dark = when (settings.theme) {
                ThemeMode.DARK -> true
                ThemeMode.LIGHT -> false
                ThemeMode.SYSTEM -> (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
            }
            // Signed out, the onboarding is always ivory (docs/DESIGN_SYSTEM.md): dark status and navigation icons.
            // Signed in, a Cocoa screen (the chat list) asks for light icons in either theme.
            val session by container.session.state.collectAsStateWithLifecycle()
            val barRequests = androidx.compose.runtime.remember { SystemBarRequests() }
            val darkBars = session !is SessionState.SignedOut && (dark || barRequests.darkSurfaces > 0)
            androidx.compose.runtime.LaunchedEffect(darkBars) {
                enableEdgeToEdge(
                    statusBarStyle = if (darkBars) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT) else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
                    navigationBarStyle = if (darkBars) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT) else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
                )
            }
            androidx.compose.runtime.CompositionLocalProvider(LocalSystemBarRequests provides barRequests) {
            GriipTheme(settings.theme, settings.accent) {
                // A new container (erase, or a switch to another server profile) gets a fresh UI tree:
                // navigation, view models and remembered state never carry over between profiles.
                androidx.compose.runtime.key(gen, container) {
                    AppRoot(container, pendingConversation, pendingInvite) {
                        pendingConversation = null
                        pendingInvite = null
                    }
                }
            }
            }
        }
        // Keep the picture-in-picture settings in step with the call (auto-enter only while one is on).
        lifecycleScope.launch {
            app.generation.flatMapLatest { app.container!!.calls.state }.collect { s ->
                if (com.griipbuddy.orbit.call.CallPip.supported(this@MainActivity)) {
                    com.griipbuddy.orbit.call.CallPip.params(this@MainActivity, s)?.let { runCatching { setPictureInPictureParams(it) } }
                }
            }
        }
        lifecycleScope.launch {
            app.generation.flatMapLatest { app.container!!.session.state }.collect { s ->
                if (s is SessionState.SignedIn && Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        lifecycleScope.launch {
            app.generation.flatMapLatest { app.container!!.calls.state }.collect { c ->
                // Let an incoming call show over the lock screen; revert afterwards.
                val ringing = c.phase == CallPhase.Incoming || c.phase == CallPhase.Connected || c.phase == CallPhase.Connecting
                if (Build.VERSION.SDK_INT >= 27) {
                    setShowWhenLocked(ringing)
                    setTurnScreenOn(c.phase == CallPhase.Incoming)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        intent.getStringExtra(EXTRA_CONVERSATION)?.let { pendingConversation = it }
        val data = intent.data
        if (data != null && data.scheme == "griip" && data.host == "invite") {
            pendingInvite = InviteCodes.fromScan(data.toString()) // same rule as the in-app scanner
        }
    }

    companion object {
        const val EXTRA_CONVERSATION = "conversation"
        const val EXTRA_OPEN_CALL = "open_call"
    }
}
