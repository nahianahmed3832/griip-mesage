package com.meldiq.android.data.repo

import com.meldiq.android.data.api.GriipJson
import com.meldiq.android.data.db.GriipDatabase
import com.meldiq.android.data.db.KvEntity
import com.meldiq.android.data.model.ThreadActivityRules
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/**
 * When you last opened each thread, on this phone (its newest reply's sort time then), so Activity can show replies
 * that arrived since. Kept in this profile's encrypted database, like the history it's derived from.
 */
class ThreadSeen(private val db: GriipDatabase) {
    private val _seen = MutableStateFlow<Map<String, Long>>(emptyMap())
    val seen: StateFlow<Map<String, Long>> = _seen.asStateFlow()
    private val lock = Mutex()

    suspend fun load() {
        _seen.value = db.kv().get(KEY)?.let { runCatching { GriipJson.decodeFromString(serializer, it) }.getOrNull() }.orEmpty()
    }

    suspend fun markSeen(rootId: String, upTo: Long) = lock.withLock {
        val next = ThreadActivityRules.markSeen(_seen.value, rootId, upTo)
        if (next == _seen.value) return@withLock
        _seen.value = next
        db.kv().put(KvEntity(KEY, GriipJson.encodeToString(serializer, next)))
    }

    companion object {
        private const val KEY = "threads.seen"
        private val serializer = MapSerializer(String.serializer(), Long.serializer())
    }
}
