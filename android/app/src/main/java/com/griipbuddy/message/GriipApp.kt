package com.griipbuddy.message

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.griipbuddy.message.data.repo.SessionState
import com.griipbuddy.message.di.AppContainer
import com.griipbuddy.message.notify.Channels
import com.griipbuddy.message.service.RelayConnectionService
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

class GriipApp : Application() {
    @Volatile var container: AppContainer? = null
        private set
    /** Bumped when the container is recreated so the UI rebinds. */
    val generation = MutableStateFlow(0)

    override fun onCreate() {
        super.onCreate()
        Channels.create(this)
        container = AppContainer(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                val c = container ?: return
                c.appState.foreground.value = true
                // FGS may only be started while visible: (re)start the optional background connection here.
                if (c.session.state.value is SessionState.SignedIn && c.settings.current.backgroundConnection) RelayConnectionService.start(this@GriipApp)
                else RelayConnectionService.stop(this@GriipApp)
            }

            override fun onStop(owner: LifecycleOwner) {
                container?.appState?.foreground?.value = false
            }
        })
    }

    /** "Sign out & erase": deletes keys, the encrypted database and downloaded attachments. */
    fun eraseEverything() {
        RelayConnectionService.stop(this)
        container?.let {
            it.identity.clearAll()
            it.close()
        }
        deleteDatabase(AppContainer.DB_NAME)
        File(filesDir, "attachments").deleteRecursively()
        getSharedPreferences("griip_settings", MODE_PRIVATE).edit().remove("serverUrl").apply()
        container = AppContainer(this)
        generation.value++
    }
}
