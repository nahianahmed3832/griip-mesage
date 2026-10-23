package com.meldiq.android.data.repo

import com.meldiq.android.data.model.AttachmentPolicy
import android.content.Context
import com.meldiq.android.BuildConfig
import com.meldiq.android.ui.theme.MeldiqPalettes
import com.meldiq.android.ui.theme.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class Settings(
    val serverUrl: String,
    val theme: ThemeMode,
    val notifications: Boolean,
    val backgroundConnection: Boolean,
    /**
     * Wake-ups through a UnifiedPush distributor such as ntfy (docs/BACKGROUND_DELIVERY.md). Off by default: it needs the
     * distributor app and a server that offers it. "Stay connected" stays available beside it as the fallback.
     */
    val pushWakeups: Boolean = false,
    val readReceipts: Boolean,
    val typingIndicators: Boolean,
    val mediaE2ee: Boolean,
    /**
     * Calls: use the phone maker's built-in echo canceller instead of WebRTC's own (AEC3). Off by default: LiveKit
     * otherwise switches WebRTC's canceller off on Android 10+ and relies on the hardware one, whose quality varies
     * by phone (physical-device report 2026-10-16: the other person heard their own voice).
     */
    val hardwareEchoCanceller: Boolean = false,
    /** Batch B10: calls reduce background noise (on); off keeps music and quiet sounds as they are. */
    val noiseReduction: Boolean = true,
    /** Batch B11: the last screen-share choice ("TEXT" or "GAME") and whether app sound went with it. */
    val shareMode: String = "TEXT",
    val shareAppSound: Boolean = false,
    // Chats (2026-10-17, docs/SETTINGS.md).
    /** Animated emoji move in chats (still when off, or when the system's animations are off). */
    val animatedEmoji: Boolean = true,
    /** Where tapped web links open. */
    val linkOpening: LinkOpening = LinkOpening.IN_APP,
    /** Fetch a title and description for links, on this phone. Off by default: fetching tells the site your IP address. */
    val linkPreviews: Boolean = false,
    /** Music and video links (YouTube, Spotify, …) as cards that open the official app; recognised from the address, nothing fetched. */
    val mediaCards: Boolean = true,
    /** Message text size: 0.9 to 1.3. */
    val textScale: Float = 1f,
    val wallpaper: String = "none",
    /** The colour preset (MeldiqPalettes: graphite, aurora, sunset or meadow). */
    val palette: String = MeldiqPalettes.DEFAULT,
    /** How conversations are drawn: a left-aligned stream with names and avatars (default), or bubbles. */
    val messageLayout: MessageLayout = MessageLayout.STREAM,
    /** The "Get started" checklist on Home was dismissed (or finished). */
    val setupDismissed: Boolean = false,
    /** The first-run introduction pages were seen or skipped (replayable from Settings › Introduction). */
    val introSeen: Boolean = false,
    /** Download received photos automatically: never, on Wi-Fi, or always. Files and videos are always tapped first. */
    val autoDownload: AutoDownload = AutoDownload.WIFI,
    /** The quality a photo or video send starts at (Settings › Chats); each send can still change it. */
    val photoQuality: AttachmentPolicy.Quality = AttachmentPolicy.Quality.STANDARD,
    val videoQuality: AttachmentPolicy.Quality = AttachmentPolicy.Quality.ORIGINAL,
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
enum class MessageLayout { STREAM, BUBBLES }
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
    private val prefs = context.getSharedPreferences("griip_settings", Context.MODE_PRIVATE).also(::migrateDesign)
    private val _state = MutableStateFlow(read())
    val state: StateFlow<Settings> = _state
    val current: Settings get() = _state.value

    private fun read() = Settings(
        serverUrl = prefs.getString(serverKey, null) ?: serverDefault,
        theme = runCatching { ThemeMode.valueOf(prefs.getString("theme", null)!!) }.getOrDefault(DEFAULT_THEME),
        notifications = prefs.getBoolean("notifications", true),
        backgroundConnection = prefs.getBoolean("backgroundConnection", true),
        pushWakeups = prefs.getBoolean("pushWakeups", false),
        readReceipts = prefs.getBoolean("readReceipts", true),
        typingIndicators = prefs.getBoolean("typingIndicators", true),
        mediaE2ee = prefs.getBoolean("mediaE2ee", true),
        hardwareEchoCanceller = prefs.getBoolean("hardwareEchoCanceller", false),
        noiseReduction = prefs.getBoolean("noiseReduction", true),
        shareMode = prefs.getString("shareMode", null) ?: "TEXT",
        shareAppSound = prefs.getBoolean("shareAppSound", false),
        animatedEmoji = prefs.getBoolean("animatedEmoji", true),
        linkOpening = runCatching { LinkOpening.valueOf(prefs.getString("linkOpening", null)!!) }.getOrDefault(LinkOpening.IN_APP),
        linkPreviews = prefs.getBoolean("linkPreviews", false),
        mediaCards = prefs.getBoolean("mediaCards", true),
        textScale = prefs.getFloat("textScale", 1f).coerceIn(0.9f, 1.3f),
        wallpaper = prefs.getString("wallpaper", null) ?: "none",
        palette = MeldiqPalettes.of(prefs.getString("palette", null)).id,
        messageLayout = runCatching { MessageLayout.valueOf(prefs.getString("messageLayout", null)!!) }.getOrDefault(MessageLayout.STREAM),
        setupDismissed = prefs.getBoolean("setupDismissed", false),
        introSeen = prefs.getBoolean("introSeen", false),
        autoDownload = runCatching { AutoDownload.valueOf(prefs.getString("autoDownload", null)!!) }.getOrDefault(AutoDownload.WIFI),
        photoQuality = runCatching { AttachmentPolicy.Quality.valueOf(prefs.getString("photoQuality", null)!!) }.getOrDefault(AttachmentPolicy.Quality.STANDARD),
        videoQuality = runCatching { AttachmentPolicy.Quality.valueOf(prefs.getString("videoQuality", null)!!) }.getOrDefault(AttachmentPolicy.Quality.ORIGINAL),
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
            .putBoolean("pushWakeups", n.pushWakeups)
            .putBoolean("readReceipts", n.readReceipts)
            .putBoolean("typingIndicators", n.typingIndicators)
            .putBoolean("mediaE2ee", n.mediaE2ee)
            .putBoolean("hardwareEchoCanceller", n.hardwareEchoCanceller)
            .putBoolean("noiseReduction", n.noiseReduction)
            .putString("shareMode", n.shareMode)
            .putBoolean("shareAppSound", n.shareAppSound)
            .putBoolean("animatedEmoji", n.animatedEmoji)
            .putString("linkOpening", n.linkOpening.name)
            .putBoolean("linkPreviews", n.linkPreviews)
            .putBoolean("mediaCards", n.mediaCards)
            .putFloat("textScale", n.textScale.coerceIn(0.9f, 1.3f))
            .putString("wallpaper", n.wallpaper)
            .putString("palette", n.palette)
            .putString("messageLayout", n.messageLayout.name)
            .putBoolean("setupDismissed", n.setupDismissed)
            .putBoolean("introSeen", n.introSeen)
            .putString("autoDownload", n.autoDownload.name)
            .putString("photoQuality", n.photoQuality.name)
            .putString("videoQuality", n.videoQuality.name)
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

    companion object {
        /** Dark is the default look since the round-6 redesign (docs/DESIGN_SYSTEM.md). */
        val DEFAULT_THEME = ThemeMode.DARK

        /**
         * The one-time move to the round-6 look for installs that predate it: the preset becomes Graphite, and "follow
         * the system" (the old default) becomes dark. A light or dark mode someone chose is kept. Returns the new
         * (theme, palette) pair; null theme means nothing was stored.
         */
        fun migratedDesign(theme: String?): Pair<String, String> {
            val nextTheme = if (theme == null || theme == ThemeMode.SYSTEM.name) DEFAULT_THEME.name else theme
            return nextTheme to MeldiqPalettes.DEFAULT
        }

        private fun migrateDesign(prefs: android.content.SharedPreferences) {
            if (prefs.getInt("designVersion", 0) >= MeldiqPalettes.DESIGN_VERSION) return
            val edit = prefs.edit().putInt("designVersion", MeldiqPalettes.DESIGN_VERSION)
            // A fresh install has nothing stored yet and simply reads the defaults.
            if (prefs.contains("theme") || prefs.contains("palette")) {
                val (theme, palette) = migratedDesign(prefs.getString("theme", null))
                edit.putString("theme", theme).putString("palette", palette)
            }
            edit.apply()
        }
    }
}
