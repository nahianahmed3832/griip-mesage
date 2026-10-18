package com.griipbuddy.orbit.di

import android.app.Application
import com.griipbuddy.orbit.call.CallManager
import com.griipbuddy.orbit.call.CallPhase
import com.griipbuddy.orbit.crypto.EnvelopeV1
import com.griipbuddy.orbit.crypto.IdentityStore
import com.griipbuddy.orbit.data.api.ApiClient
import com.griipbuddy.orbit.data.api.ConnState
import com.griipbuddy.orbit.data.api.RealtimeClient
import com.griipbuddy.orbit.data.repo.ApiPushServer
import com.griipbuddy.orbit.data.repo.PrefsPushStore
import com.griipbuddy.orbit.data.repo.PushRegistrar
import com.griipbuddy.orbit.data.repo.WakeFetch
import com.griipbuddy.orbit.service.ConnectorDistributor
import com.griipbuddy.orbit.data.db.GriipDatabase
import com.griipbuddy.orbit.data.repo.AppState
import com.griipbuddy.orbit.data.repo.ConversationRepository
import com.griipbuddy.orbit.data.repo.KeyDirectory
import com.griipbuddy.orbit.data.repo.MessageRepository
import com.griipbuddy.orbit.BuildConfig
import com.griipbuddy.orbit.data.repo.OrgRepository
import com.griipbuddy.orbit.data.repo.ProfileStorage
import com.griipbuddy.orbit.data.repo.SessionRepository
import com.griipbuddy.orbit.data.repo.SessionState
import com.griipbuddy.orbit.data.repo.SettingsStore
import com.griipbuddy.orbit.data.repo.UserRepository
import com.griipbuddy.orbit.notify.MessageNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Manual dependency injection (docs/DECISIONS.md D-07). One container per active server profile;
 * recreated after "erase this device" and when the person switches profiles. [profile] decides where
 * this container's keys, database and files live; the default is the original single-account layout.
 */
class AppContainer(
    val app: Application,
    val profile: ProfileStorage = ProfileStorage.of(ProfileStorage.DEFAULT),
    serverDefault: String = BuildConfig.DEFAULT_SERVER_URL,
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settings = SettingsStore(app, profile.serverKey, serverDefault)
    val identity = IdentityStore(app, profile.identityPrefs, profile.keystoreAlias)
    val session = SessionRepository(identity, settings)
    // Before this phone has encryption keys (device setup), requests carry the account's access token.
    val api = ApiClient({ session.serverUrl }, { session.token ?: identity.account()?.tokens?.accessToken }, { session.reauth() }).also { session.api = it }
    /** Orbit 2.0 accounts (docs/ORBIT_2_ARCHITECTURE_2026-10-18.md): sign-in, tokens, device setup, legacy move, workspaces. */
    private val accountApi = com.griipbuddy.orbit.data.account.HttpAccountApi(api)
    val accounts = com.griipbuddy.orbit.data.account.AccountRepository(accountApi, identity, session, session::deviceName)
    val legacyMigration = com.griipbuddy.orbit.data.account.LegacyMigration(accountApi, identity, session, accounts)
    val workspaces = com.griipbuddy.orbit.data.account.WorkspaceRepository(accountApi)
    /** What this account's server supports (server-info features, 2026-10-17). */
    val features = com.griipbuddy.orbit.data.repo.ServerFeatures(api)
    val db: GriipDatabase = GriipDatabase.open(app, identity.databasePassphrase(), profile.dbName)
    val realtime = RealtimeClient(app, api.http, scope, { session.serverUrl }, { session.token }, { session.reauth() })
    val users = UserRepository(api, db)
    val directory = KeyDirectory(api, db)
    val conversations = ConversationRepository(api, db, users)
    val appState = AppState()
    val notifier = MessageNotifier(app)
    val crypto = EnvelopeV1 { session.keys ?: error("no keys on this device") }
    val messages = MessageRepository(
        app, db, api, realtime, session, crypto, directory, conversations, users, settings, appState, scope,
        java.io.File(app.filesDir, profile.attachmentsDir), com.griipbuddy.orbit.data.repo.AttachmentUploader(api, features),
    ) { notifier.show(it) }
    val orgs = OrgRepository(api, db, conversations, users)
    val federation = com.griipbuddy.orbit.data.repo.FederationRepository(api)
    /** Encrypted message history on the server (docs/MESSAGE_HISTORY.md). */
    val history = com.griipbuddy.orbit.data.repo.HistorySync(api, db, session, features, conversations, scope)
    val profiles = com.griipbuddy.orbit.data.repo.ProfileRepository(app, api, users, session, profile.id)
    val translator by lazy { com.griipbuddy.orbit.data.repo.MessageTranslator(app) }
    val backups = com.griipbuddy.orbit.data.repo.BackupManager(app, db, session, java.io.File(app.filesDir, profile.attachmentsDir), profile.id)
    val calls = CallManager(app, api, db, session, messages, conversations, settings, scope)
    val voicePlayer = com.griipbuddy.orbit.voice.VoicePlayer(app)
    /** Push wake-ups for this profile (docs/BACKGROUND_DELIVERY.md); the profile id is the UnifiedPush instance. */
    val push = PushRegistrar(
        profile.id, PrefsPushStore(app), ApiPushServer(api, features),
        ConnectorDistributor(app) { "Orbit · " + (runCatching { java.net.URI(session.serverUrl).host }.getOrNull() ?: session.serverUrl) },
    )
    /** What a wake-up does: hold the relay connection until the backlog drained and ringing calls were looked up. */
    val wakeFetch = WakeFetch(scope, appState.wakeHolds, messages.backlogDrained, calls.ringChecks, calls.state.map { it.phase == CallPhase.Incoming })

    init {
        session.accountRenewal = { if (accounts.record?.tokens != null) accounts.refreshTokens() else null }
        messages.start()
        calls.start()
        history.start()
        scope.launch { orgs.loadSelection() }
        scope.launch { messages.orgDeleted.collect { runCatching { orgs.forget(it) } } }
        scope.launch {
            combine(session.state, appState.foreground, settings.state, calls.state, appState.wakeHolds) { s, fg, st, call, wake ->
                s is SessionState.SignedIn && (fg || st.backgroundConnection || call.phase != CallPhase.Idle || wake > 0)
            }.collect { want -> if (want) realtime.start() else realtime.stop() }
        }
        // An endpoint the server couldn't be told about (it was unreachable) goes as soon as the app connects again.
        scope.launch { realtime.state.collect { if (it == ConnState.Connected) push.retryPending() } }
    }

    fun close() {
        voicePlayer.stop()
        realtime.stop()
        scope.cancel()
        db.close()
    }

    companion object {
        const val DB_NAME = "griip.db"
    }
}
