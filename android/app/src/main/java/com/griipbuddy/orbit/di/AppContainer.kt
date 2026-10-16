package com.griipbuddy.orbit.di

import android.app.Application
import com.griipbuddy.orbit.call.CallManager
import com.griipbuddy.orbit.crypto.EnvelopeV1
import com.griipbuddy.orbit.crypto.IdentityStore
import com.griipbuddy.orbit.data.api.ApiClient
import com.griipbuddy.orbit.data.api.RealtimeClient
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
    val api = ApiClient({ session.serverUrl }, { session.token }, { session.reauth() }).also { session.api = it }
    val db: GriipDatabase = GriipDatabase.open(app, identity.databasePassphrase(), profile.dbName)
    val realtime = RealtimeClient(app, api.http, scope, { session.serverUrl }, { session.token }, { session.reauth() })
    val users = UserRepository(api, db)
    val directory = KeyDirectory(api, db)
    val conversations = ConversationRepository(api, db, users)
    val appState = AppState()
    val notifier = MessageNotifier(app)
    val crypto = EnvelopeV1 { session.keys ?: error("no keys on this device") }
    val messages = MessageRepository(app, db, api, realtime, session, crypto, directory, conversations, users, settings, appState, scope, java.io.File(app.filesDir, profile.attachmentsDir)) { notifier.show(it) }
    val orgs = OrgRepository(api, db, conversations, users)
    val federation = com.griipbuddy.orbit.data.repo.FederationRepository(api)
    val calls = CallManager(app, api, db, session, messages, conversations, settings, scope)
    val voicePlayer = com.griipbuddy.orbit.voice.VoicePlayer(app)

    init {
        messages.start()
        calls.start()
        scope.launch { orgs.loadSelection() }
        scope.launch {
            combine(session.state, appState.foreground, settings.state, calls.state) { s, fg, st, call ->
                s is SessionState.SignedIn && (fg || st.backgroundConnection || call.phase != com.griipbuddy.orbit.call.CallPhase.Idle)
            }.collect { want -> if (want) realtime.start() else realtime.stop() }
        }
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
