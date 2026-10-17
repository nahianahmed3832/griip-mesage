package com.griipbuddy.orbit.data.repo

import com.griipbuddy.orbit.data.api.ApiClient
import com.griipbuddy.orbit.data.api.ServerInfo
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * What this account's server supports, from /v1/server-info (2026-10-17). Servers from before then list nothing, so
 * the app can say "this server needs an update" instead of showing a bare "Not found" (the profile-photo report).
 * Cached for a few minutes; null while unknown (no connection yet).
 */
class ServerFeatures(private val api: ApiClient, private val clock: () -> Long = System::currentTimeMillis) {
    private val lock = Mutex()
    private var cached: Set<String>? = null
    private var at = 0L

    suspend fun get(): Set<String>? = lock.withLock {
        if (cached != null && clock() - at < TTL_MS) return cached
        val fresh = runCatching { api.get<ServerInfo>("/v1/server-info").features.orEmpty().toSet() }.getOrNull()
        if (fresh != null) {
            cached = fresh
            at = clock()
        }
        cached
    }

    /** true / false once known; null when the server couldn't be asked. */
    suspend fun has(feature: String): Boolean? = get()?.contains(feature)

    fun forget() {
        cached = null
    }

    companion object {
        const val PROFILES = "profiles"
        const val PROFILE_PHOTOS = "profile-photos"
        const val HISTORY = "history"
        const val ATTACHMENT_KEEP = "attachment-keep"
        const val ORG_DELETE = "org-delete"
        const val DIRECT_UPLOAD = "direct-upload"
        private const val TTL_MS = 5 * 60_000L
    }
}
