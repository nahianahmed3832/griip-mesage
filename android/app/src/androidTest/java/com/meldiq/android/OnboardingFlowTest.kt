package com.meldiq.android

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.meldiq.android.crypto.LocalKeys
import com.meldiq.android.data.api.ApiException
import com.meldiq.android.data.api.UsernameAvailableRes
import com.meldiq.android.data.repo.PendingPairing
import com.meldiq.android.data.repo.ProfileStorage
import com.meldiq.android.di.AppContainer
import com.meldiq.android.ui.LocalContainer
import com.meldiq.android.ui.screens.onboarding.AuthGateway
import com.meldiq.android.ui.screens.onboarding.OnboardingFlow
import com.meldiq.android.ui.theme.GriipTheme
import kotlinx.coroutines.CompletableDeferred
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The signed-out screens (UI-001): welcome, sign-up, log in, restore and link, driven like a person would. The account
 * calls go to a fake ([Gateway]) so the screens' validation, loading, errors and navigation can be checked without a
 * server; everything else is the real app (an isolated account container, "itestonboarding").
 *
 * Instrumented: needs a device. Never on the owner's own Meldiq: run against the side-by-side debug build
 * (-Pmeldiq.sideBySide=true) or an emulator (docs/TESTING.md).
 */
@RunWith(AndroidJUnit4::class)
class OnboardingFlowTest {
    @get:Rule val compose = createComposeRule()

    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val gateway = Gateway()

    private fun s(id: Int, vararg args: Any) = app.getString(id, *args)

    private fun field(label: Int) = compose.onNode(hasContentDescription(s(label)) and hasSetTextAction())

    @Before
    fun setUp() {
        val container = shared(app)
        compose.setContent {
            CompositionLocalProvider(LocalContainer provides container) {
                GriipTheme { OnboardingFlow(pendingInvite = null, gateway = gateway) }
            }
        }
        compose.waitForIdle()
    }

    companion object {
        /**
         * One isolated container for the whole class. Opening and closing one per test raced its own background
         * database work (SQLCipher "connection pool has been closed" / "database is locked" on the phone).
         */
        private var container: AppContainer? = null

        private fun shared(app: Application): AppContainer =
            container ?: AppContainer(app, ProfileStorage.of("itestonboarding"), "http://127.0.0.1:9").also { container = it }

        @JvmStatic
        @AfterClass
        fun closeContainer() {
            container?.close()
            container = null
        }
    }

    @Test
    fun welcomeOffersSignUpAndLogIn() {
        compose.onNodeWithText(s(R.string.welcome_sign_up)).assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText(s(R.string.welcome_log_in)).assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText(s(R.string.welcome_title_1), substring = true).assertIsDisplayed()
        compose.onNode(hasContentDescription(s(R.string.welcome_trust))).assertIsDisplayed() // the line with the inline lock
    }

    @Test
    fun signUpChecksTheFormShowsLoadingThenRegisters() {
        compose.onNodeWithText(s(R.string.welcome_sign_up)).performClick()
        val create = compose.onNodeWithText(s(R.string.signup_submit))
        create.assertIsNotEnabled()

        field(R.string.signup_name).performTextInput("Nusrat Jahan")
        field(R.string.signup_username).assertTextContains("nusrat.jahan")
        compose.waitUntil(5_000) { gateway.usernameChecks.isNotEmpty() }
        compose.onNodeWithText(s(R.string.signup_username_available, "nusrat.jahan")).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(s(R.string.signup_submit)).assertIsEnabled()

        gateway.gate = CompletableDeferred()
        compose.onNodeWithText(s(R.string.signup_submit)).performClick()
        compose.onNodeWithText(s(R.string.signup_submitting)).assertIsDisplayed().assertIsNotEnabled()
        compose.onNode(hasContentDescription(s(R.string.common_back))).assertIsNotEnabled() // back waits for the keys
        gateway.gate?.complete(Unit)
        compose.waitForIdle()
        assertEquals(listOf("nusrat.jahan" to "Nusrat Jahan"), gateway.registrations)
    }

    @Test
    fun aTakenUsernameIsExplainedAndBlocksSignUp() {
        gateway.taken += "nusrat"
        compose.onNodeWithText(s(R.string.welcome_sign_up)).performClick()
        field(R.string.signup_name).performTextInput("Nusrat")
        compose.waitUntil(5_000) { gateway.usernameChecks.isNotEmpty() }
        compose.onNode(hasText(s(R.string.signup_username_taken)), useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        field(R.string.signup_username).assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Error))
        compose.onNodeWithText(s(R.string.signup_submit)).assertIsNotEnabled()
    }

    @Test
    fun aRefusedSignUpShowsTheServersReason() {
        gateway.failure = ApiException(429, "registration_limited", "Too many new accounts from this network. Try again later.")
        compose.onNodeWithText(s(R.string.welcome_sign_up)).performClick()
        field(R.string.signup_name).performTextInput("Nusrat")
        compose.waitUntil(5_000) { gateway.usernameChecks.isNotEmpty() }
        compose.onNodeWithText(s(R.string.signup_submit)).performClick()
        compose.onNodeWithText("Too many new accounts", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(s(R.string.signup_submit)).assertIsEnabled() // can try again
    }

    @Test
    fun backWalksBackThroughTheSteps() {
        compose.onNodeWithText(s(R.string.welcome_log_in)).performClick()
        compose.onNodeWithText(s(R.string.login_restore)).performClick()
        compose.onNodeWithText(s(R.string.restore_title)).assertIsDisplayed()
        Espresso.pressBack()
        compose.onNodeWithText(s(R.string.login_restore)).assertIsDisplayed()
        Espresso.pressBack()
        compose.onNodeWithText(s(R.string.welcome_sign_up)).assertIsDisplayed()
        compose.onNodeWithText(s(R.string.welcome_sign_up)).performClick()
        compose.onNode(hasContentDescription(s(R.string.common_back))).performClick()
        compose.onNodeWithText(s(R.string.welcome_log_in)).assertIsDisplayed()
    }

    @Test
    fun aWrongRecoveryKeyIsShownOnTheKeyField() {
        gateway.failure = ApiException(400, "bad_recovery", "That recovery key isn't valid. Check for typos.")
        compose.onNodeWithText(s(R.string.welcome_log_in)).performClick()
        compose.onNodeWithText(s(R.string.login_restore)).performClick()
        compose.onNodeWithText(s(R.string.restore_submit)).assertIsNotEnabled()
        field(R.string.signup_username).performTextInput("nusrat")
        field(R.string.restore_key).performTextInput("ABCD-EFGH-JKMN-PQRS-TUVW")
        compose.onNodeWithText(s(R.string.restore_submit)).performClick()
        compose.waitUntil(5_000) { gateway.restores.isNotEmpty() }
        field(R.string.restore_key).assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Error))
    }

    @Test
    fun linkingShowsTheCodeToEnterOnTheOtherPhone() {
        compose.onNodeWithText(s(R.string.welcome_log_in)).performClick()
        compose.onNodeWithText(s(R.string.login_link)).performClick()
        compose.waitUntil(5_000) { gateway.pairings > 0 }
        // Shown as "ABCDE 12345"; TalkBack gets it letter by letter instead of the visible text.
        compose.onNode(hasContentDescription(s(R.string.link_code_label) + ": A B C D E 1 2 3 4 5"), useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText(s(R.string.link_waiting)).assertIsDisplayed()
    }

    /** Answers like a server would, without one. */
    private class Gateway : AuthGateway {
        val taken = mutableSetOf<String>()
        val usernameChecks = mutableListOf<String>()
        @Volatile var gate: CompletableDeferred<Unit>? = null
        @Volatile var failure: Exception? = null
        val registrations = mutableListOf<Pair<String, String>>()
        val restores = mutableListOf<String>()
        @Volatile var pairings = 0

        override suspend fun usernameAvailable(serverUrl: String, username: String): UsernameAvailableRes {
            usernameChecks += username
            return UsernameAvailableRes(username !in taken, username)
        }

        override suspend fun register(serverUrl: String, username: String, displayName: String, avatarColor: Int, inviteCode: String?) {
            registrations += username to displayName
            gate?.await()
            failure?.let { throw it }
        }

        override suspend fun restore(serverUrl: String, username: String, recoveryKey: String) {
            restores += username
            failure?.let { throw it }
        }

        override suspend fun startPairing(serverUrl: String): PendingPairing {
            pairings++
            return PendingPairing(serverUrl, "pairing", "ABCDE12345", 0L, LocalKeys("", "", "", ""), "", "")
        }

        override suspend fun pollPairing(pairing: PendingPairing): Boolean = false
    }
}
