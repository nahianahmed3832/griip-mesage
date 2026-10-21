package com.meldiq.android.data.repo

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * How Home shows each community on this phone (docs/product/MELDIQ_COMMUNITY_CHAT_SPEC.md §2): the channel opened last
 * (highlighted in the panel), and the panel's personal switches, Hide muted channels and Unread only. Kept per profile,
 * on this phone only: nothing here leaves it. Collapsed categories and favourites are synced instead ([NavPreferences]).
 */
class CommunityViewPrefs(context: Context, profileId: String) {
    private val prefs = context.getSharedPreferences("meldiq_community_view_$profileId", Context.MODE_PRIVATE)

    /** Per community id. */
    data class View(val lastChannel: String? = null, val hideMuted: Boolean = false, val unreadOnly: Boolean = false)

    private val _views = MutableStateFlow(load())
    val views: StateFlow<Map<String, View>> = _views.asStateFlow()

    fun view(orgId: String?): View = orgId?.let { _views.value[it] } ?: View()

    fun opened(orgId: String, channelId: String) = change(orgId) { it.copy(lastChannel = channelId) }
    fun setHideMuted(orgId: String, on: Boolean) = change(orgId) { it.copy(hideMuted = on) }
    fun setUnreadOnly(orgId: String, on: Boolean) = change(orgId) { it.copy(unreadOnly = on) }

    private fun change(orgId: String, f: (View) -> View) {
        val next = f(view(orgId))
        _views.value = _views.value + (orgId to next)
        prefs.edit()
            .putString("$orgId.last", next.lastChannel)
            .putBoolean("$orgId.hideMuted", next.hideMuted)
            .putBoolean("$orgId.unreadOnly", next.unreadOnly)
            .apply()
    }

    private fun load(): Map<String, View> {
        val ids = prefs.all.keys.map { it.substringBeforeLast('.') }.toSet()
        return ids.associateWith { id ->
            View(prefs.getString("$id.last", null), prefs.getBoolean("$id.hideMuted", false), prefs.getBoolean("$id.unreadOnly", false))
        }
    }
}
