package com.meldiq.android.data.repo

import com.meldiq.android.data.api.GriipJson
import com.meldiq.android.data.db.GriipDatabase
import com.meldiq.android.data.db.KvEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

/**
 * Batch B8 (Discord reference FORUM-CTRL-005 "Follow"): threads and forum posts you follow on this phone, so their
 * replies show in Activity even when you haven't written in them. Kept in this profile's encrypted database, newest
 * first, at most [MAX].
 */
class FollowedThreads(private val db: GriipDatabase) {
    private val _followed = MutableStateFlow<Set<String>>(emptySet())
    val followed: StateFlow<Set<String>> = _followed.asStateFlow()
    private val lock = Mutex()

    suspend fun load() {
        _followed.value = db.kv().get(KEY)?.let { runCatching { GriipJson.decodeFromString(serializer, it) }.getOrNull() }.orEmpty().toSet()
    }

    suspend fun set(rootId: String, follow: Boolean) = lock.withLock {
        val next = FollowRules.toggle(_followed.value.toList(), rootId, follow)
        _followed.value = next.toSet()
        db.kv().put(KvEntity(KEY, GriipJson.encodeToString(serializer, next)))
    }

    companion object {
        private const val KEY = "threads.followed"
        const val MAX = 200
        private val serializer = ListSerializer(String.serializer())
    }
}

/** Following and unfollowing, newest first and bounded (unit-tested). */
object FollowRules {
    fun toggle(current: List<String>, rootId: String, follow: Boolean, max: Int = FollowedThreads.MAX): List<String> =
        if (follow) (listOf(rootId) + current.filter { it != rootId }).take(max) else current.filter { it != rootId }
}
