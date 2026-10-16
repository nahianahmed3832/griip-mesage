package com.griipbuddy.message.data.repo

import android.content.Context
import com.griipbuddy.message.BuildConfig
import com.griipbuddy.message.ui.theme.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class Settings(
    val serverUrl: String,
    val theme: ThemeMode,
    val notifications: Boolean,
    val backgroundConnection: Boolean,
    val readReceipts: Boolean,
    val typingIndicators: Boolean,
    val mediaE2ee: Boolean,
)

/**
 * Non-secret user preferences. Appearance and behaviour are shared by every server profile on the phone;
 * the server address is the profile's own ([serverKey]), starting at [serverDefault].
 */
class SettingsStore(
    context: Context,
    private val serverKey: String = "serverUrl",
    private val serverDefault: String = BuildConfig.DEFAULT_SERVER_URL,
) {
    private val prefs = context.getSharedPreferences("griip_settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(read())
    val state: StateFlow<Settings> = _state
    val current: Settings get() = _state.value

    private fun read() = Settings(
        serverUrl = prefs.getString(serverKey, null) ?: serverDefault,
        theme = runCatching { ThemeMode.valueOf(prefs.getString("theme", "SYSTEM")!!) }.getOrDefault(ThemeMode.SYSTEM),
        notifications = prefs.getBoolean("notifications", true),
        backgroundConnection = prefs.getBoolean("backgroundConnection", true),
        readReceipts = prefs.getBoolean("readReceipts", true),
        typingIndicators = prefs.getBoolean("typingIndicators", true),
        mediaE2ee = prefs.getBoolean("mediaE2ee", true),
    )

    fun update(f: (Settings) -> Settings) {
        val n = f(_state.value)
        prefs.edit()
            .putString(serverKey, n.serverUrl.trim().trimEnd('/'))
            .putString("theme", n.theme.name)
            .putBoolean("notifications", n.notifications)
            .putBoolean("backgroundConnection", n.backgroundConnection)
            .putBoolean("readReceipts", n.readReceipts)
            .putBoolean("typingIndicators", n.typingIndicators)
            .putBoolean("mediaE2ee", n.mediaE2ee)
            .apply()
        _state.value = read()
    }
}
