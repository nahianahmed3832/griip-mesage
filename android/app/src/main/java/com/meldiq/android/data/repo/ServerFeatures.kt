package com.meldiq.android.data.repo

import com.meldiq.android.data.api.ApiClient
import com.meldiq.android.data.api.ServerInfo
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * What this account's server supports, from /v1/server-info (2026-10-17). Servers from before then list nothing, so
 * the app can say "this server needs an update" instead of showing a bare "Not found" (the profile-photo report).
 * Cached for a few minutes; null while unknown (no connection yet).
 */
class ServerFeatures(private val api: ApiClient, private val clock: () -> Long = System::currentTimeMillis) {
    private val lock = Mutex()
    private var cached: ServerInfo? = null
    private var at = 0L
    /** Which server [cached] describes: a new account's address is set after its profile exists (2026-10-19 fix). */
    private var cachedFor: String? = null

    private suspend fun info(): ServerInfo? = lock.withLock {
        val address = api.serverAddress()
        if (cachedFor != address) {
            cached = null
            cachedFor = address
        }
        if (cached != null && clock() - at < TTL_MS) return cached
        val fresh = runCatching { api.get<ServerInfo>("/v1/server-info") }.getOrNull()
        // Only if the address didn't change while asking: an answer from the old server never describes the new one.
        if (fresh != null && api.serverAddress() == address) {
            cached = fresh
            at = clock()
        }
        cached
    }

    suspend fun get(): Set<String>? = info()?.let { it.features.orEmpty().toSet() }

    /** true / false once known; null when the server couldn't be asked. */
    suspend fun has(feature: String): Boolean? = get()?.contains(feature)

    /** The push servers this server sends wake-ups to (empty: none; null: unknown for now). */
    suspend fun pushHosts(): List<String>? = info()?.let { it.push?.unifiedPushHosts.orEmpty() }

    fun forget() {
        cached = null
    }

    companion object {
        const val PROFILES = "profiles"
        const val PROFILE_PHOTOS = "profile-photos"
        const val HISTORY = "history"
        const val ATTACHMENT_KEEP = "attachment-keep"
        const val ORG_DELETE = "org-delete"
        /** Deleting your own account, `DELETE /v2/account` (docs/ACCOUNT_DELETION.md). */
        const val ACCOUNT_DELETE = "account-delete"
        /** Meldiq 2.0: communities as a kind of workspace (docs/product/MELDIQ_2_DECISIONS.md). */
        const val COMMUNITIES = "communities"
        /** Meldiq 2.0: experiments and content-free analytics counts (docs/product/EXPERIMENTS.md). */
        const val EXPERIMENTS = "experiments"
        const val ANALYTICS_EVENTS = "analytics-events"
        const val DIRECT_UPLOAD = "direct-upload"
        const val PUSH_UNIFIEDPUSH = "push-unifiedpush"
        /** Firebase Cloud Messaging wake-ups (docs/PUSH_FCM.md). */
        const val PUSH_FCM = "push-fcm"
        /** Channel access modes, invitation states, archived branches and teams (docs/WORKSPACE_ADMINISTRATION.md). */
        const val WORKSPACE_ADMIN = "workspace-admin"
        /** Personal navigation preferences kept on the server: starred, sections, notification levels (2026-10-19). */
        const val NAV_PREFERENCES = "nav-preferences"
        /** Messages saved for later in the personal preferences document (2026-10-19, Home › Later). */
        const val SAVED_ITEMS = "saved-items"
        /** Universal search over server-readable content (round 7, docs/SEARCH.md). */
        const val SEARCH = "search"
        /** Organisation avatars (2026-10-19). */
        const val ORG_PHOTOS = "org-photos"
        /** Custom workspace roles and delegated permissions (2026-10-19, docs/WORKSPACE_ROLES.md). */
        const val CUSTOM_ROLES = "custom-roles"
        /** The app directory, connected accounts and app notifications in channels (2026-10-19, docs/INTEGRATIONS.md). */
        const val INTEGRATIONS = "integrations"
        private const val TTL_MS = 5 * 60_000L
    }
}
