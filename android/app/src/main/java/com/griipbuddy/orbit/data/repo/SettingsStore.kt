package com.griipbuddy.orbit.data.repo

import android.content.Context
import com.griipbuddy.orbit.BuildConfig
import com.griipbuddy.orbit.ui.theme.ThemeMode
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
    /**
     * Calls: use the phone maker's built-in echo canceller instead of WebRTC's own (AEC3). Off by default: LiveKit
     * otherwise switches WebRTC's canceller off on Android 10+ and relies on the hardware one, whose quality varies
     * by phone (physical-device report 2026-10-16: the other person heard their own voice).
     */
    val hardwareEchoCanceller: Boolean = false,
    // Chats (2026-10-17, docs/SETTINGS.md).
    /** Animated emoji move in chats (still when off, or when the system's animations are off). */
    val animatedEmoji: Boolean = true,
    /** Where tapped web links open. */
    val linkOpening: LinkOpening = LinkOpening.IN_APP,
    /** Fetch a title and description for links, on this phone. Off by default: fetching tells the site your IP address. */
    val linkPreviews: Boolean = false,
    /** Message text size: 0.9 to 1.3. */
    val textScale: Float = 1f,
    val wallpaper: String = "none",
    val accent: String = "orbit",
    /** Download received photos automatically: never, on Wi-Fi, or always. Files and videos are always tapped first. */
    val autoDownload: AutoDownload = AutoDownload.WIFI,
    // Notifications & sounds.
    val messageSound: Boolean = true,
    val groupNotifications: Boolean = true,
    val callRingtone: Boolean = true,
    val vibrate: Boolean = true,
    /** Message previews on the lock screen and in notifications ("New message" when off). */
    val notificationPreviews: Boolean = true,
    /** "system", "en", "zh-CN" or "bn" (the app's language, independent of the phone's). */
    val language: String = "system",
    /** Shows the server setup (Settings › About, tapped seven times). Hidden from ordinary use. */
    val developerMode: Boolean = false,
)

enum class LinkOpening { IN_APP, EXTERNAL, ASK }
enum class AutoDownload { NEVER, WIFI, ALWAYS }

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
        hardwareEchoCanceller = prefs.getBoolean("hardwareEchoCanceller", false),
        animatedEmoji = prefs.getBoolean("animatedEmoji", true),
        linkOpening = runCatching { LinkOpening.valueOf(prefs.getString("linkOpening", null)!!) }.getOrDefault(LinkOpening.IN_APP),
        linkPreviews = prefs.getBoolean("linkPreviews", false),
        textScale = prefs.getFloat("textScale", 1f).coerceIn(0.9f, 1.3f),
        wallpaper = prefs.getString("wallpaper", null) ?: "none",
        accent = prefs.getString("accent", null) ?: "orbit",
        autoDownload = runCatching { AutoDownload.valueOf(prefs.getString("autoDownload", null)!!) }.getOrDefault(AutoDownload.WIFI),
        messageSound = prefs.getBoolean("messageSound", true),
        groupNotifications = prefs.getBoolean("groupNotifications", true),
        callRingtone = prefs.getBoolean("callRingtone", true),
        vibrate = prefs.getBoolean("vibrate", true),
        notificationPreviews = prefs.getBoolean("notificationPreviews", true),
        language = prefs.getString("language", null) ?: "system",
        developerMode = prefs.getBoolean("developerMode", false),
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
            .putBoolean("hardwareEchoCanceller", n.hardwareEchoCanceller)
            .putBoolean("animatedEmoji", n.animatedEmoji)
            .putString("linkOpening", n.linkOpening.name)
            .putBoolean("linkPreviews", n.linkPreviews)
            .putFloat("textScale", n.textScale.coerceIn(0.9f, 1.3f))
            .putString("wallpaper", n.wallpaper)
            .putString("accent", n.accent)
            .putString("autoDownload", n.autoDownload.name)
            .putBoolean("messageSound", n.messageSound)
            .putBoolean("groupNotifications", n.groupNotifications)
            .putBoolean("callRingtone", n.callRingtone)
            .putBoolean("vibrate", n.vibrate)
            .putBoolean("notificationPreviews", n.notificationPreviews)
            .putString("language", n.language)
            .putBoolean("developerMode", n.developerMode)
            .apply()
        _state.value = read()
    }
}
