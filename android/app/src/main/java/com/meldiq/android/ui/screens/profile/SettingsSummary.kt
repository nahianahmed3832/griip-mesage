package com.meldiq.android.ui.screens.profile

import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * The current value a settings row shows before its chevron (2026-10-23, the reference's grouped settings: "Appearance ·
 * Dark", "Notifications · Paused"), so people see where things stand without opening each page.
 */
object SettingsSummary {
    enum class Notifications { ON, OFF, PAUSED, BLOCKED }

    /** What notifications do now: Android's block wins over Meldiq's switch, which wins over a pause. */
    fun notifications(enabledInApp: Boolean, allowedBySystem: Boolean, pausedUntil: Long?, now: Long): Notifications = when {
        !allowedBySystem -> Notifications.BLOCKED
        !enabledInApp -> Notifications.OFF
        pausedUntil != null && pausedUntil > now -> Notifications.PAUSED
        else -> Notifications.ON
    }
}

/** Notifications' current state as a row value. */
@androidx.compose.runtime.Composable
fun notificationsValue(): String {
    val container = com.meldiq.android.ui.LocalContainer.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val settings by container.settings.state.collectAsStateWithLifecycle()
    val mine by container.profiles.mine.collectAsStateWithLifecycle()
    val allowed = androidx.core.app.NotificationManagerCompat.from(ctx).areNotificationsEnabled()
    val state = SettingsSummary.notifications(settings.notifications, allowed, mine?.notificationsPausedUntil, System.currentTimeMillis())
    return androidx.compose.ui.res.stringResource(
        when (state) {
            SettingsSummary.Notifications.ON -> com.meldiq.android.R.string.ux_on
            SettingsSummary.Notifications.OFF -> com.meldiq.android.R.string.ux_off
            SettingsSummary.Notifications.PAUSED -> com.meldiq.android.R.string.ux_paused
            SettingsSummary.Notifications.BLOCKED -> com.meldiq.android.R.string.ux_blocked
        },
    )
}

/** Theme and colour preset as a row value ("Dark · Graphite"). */
@androidx.compose.runtime.Composable
fun appearanceValue(): String {
    val container = com.meldiq.android.ui.LocalContainer.current
    val settings by container.settings.state.collectAsStateWithLifecycle()
    val theme = androidx.compose.ui.res.stringResource(
        when (settings.theme) {
            com.meldiq.android.ui.theme.ThemeMode.SYSTEM -> com.meldiq.android.R.string.appearance_theme_system
            com.meldiq.android.ui.theme.ThemeMode.LIGHT -> com.meldiq.android.R.string.appearance_theme_light
            com.meldiq.android.ui.theme.ThemeMode.DARK -> com.meldiq.android.R.string.appearance_theme_dark
        },
    )
    return theme + " · " + paletteName(com.meldiq.android.ui.theme.MeldiqPalettes.of(settings.palette).id)
}

/** The app's language as a row value ("System default", "বাংলা"). */
@androidx.compose.runtime.Composable
fun languageValue(): String =
    com.meldiq.android.ui.AppLanguage.current(androidx.compose.ui.platform.LocalContext.current).let {
        if (it == "system") androidx.compose.ui.res.stringResource(com.meldiq.android.R.string.lang_system) else com.meldiq.android.ui.AppLanguage.nativeName(it)
    }
