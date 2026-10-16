package com.griipbuddy.message.data.repo

import com.griipbuddy.message.data.db.PendingControlEntity

/** Storage for [PendingControls]; Room in the app, a map in unit tests. */
interface PendingControlStore {
    /** Returns false if a control with this id is already held (duplicate delivery). */
    suspend fun insertIfAbsent(e: PendingControlEntity): Boolean
    suspend fun forTarget(conversationId: String, targetId: String): List<PendingControlEntity>
    suspend fun delete(ids: List<String>)
    suspend fun countIn(conversationId: String): Int
    suspend fun deleteOldestIn(conversationId: String, keep: Int)
    suspend fun deleteExpired(now: Long)
}

/**
 * Edits, deletions, reactions and pins can arrive before the message they refer to: a different
 * sender's message can be later in the relay's queue, or still in another backlog batch. They used to
 * be dropped. Now they're held here, bounded per conversation and for the relay's 7-day retention,
 * and applied in their original order once the target arrives in the *same* conversation (T2.1).
 */
class PendingControls(
    private val store: PendingControlStore,
    private val now: () -> Long = System::currentTimeMillis,
    private val maxPerConversation: Int = 200,
    private val ttlMs: Long = 7 * 24 * 3600_000L,
) {
    /** Holds a control until its target arrives. Returns false for a duplicate. */
    suspend fun hold(controlId: String, conversationId: String, targetId: String, senderUserId: String, payloadJson: String, serverTs: Long): Boolean {
        val t = now()
        val held = store.insertIfAbsent(PendingControlEntity(controlId, conversationId, targetId, senderUserId, payloadJson, serverTs, t, t + ttlMs))
        if (held && store.countIn(conversationId) > maxPerConversation) store.deleteOldestIn(conversationId, maxPerConversation)
        return held
    }

    /** Removes and returns the controls waiting for [targetId] in [conversationId], oldest first. */
    suspend fun takeFor(conversationId: String, targetId: String): List<PendingControlEntity> {
        val all = store.forTarget(conversationId, targetId)
        store.delete(all.map { it.id })
        return all.filter { it.expiresAt > now() }.sortedWith(compareBy({ it.serverTs }, { it.id }))
    }

    suspend fun purgeExpired() = store.deleteExpired(now())
}

/** Room-backed [PendingControlStore]. */
class RoomPendingControlStore(private val db: com.griipbuddy.message.data.db.GriipDatabase) : PendingControlStore {
    private val dao get() = db.pendingControls()
    override suspend fun insertIfAbsent(e: PendingControlEntity) = dao.insertIgnore(e) != -1L
    override suspend fun forTarget(conversationId: String, targetId: String) = dao.forTarget(conversationId, targetId)
    override suspend fun delete(ids: List<String>) {
        if (ids.isNotEmpty()) dao.delete(ids)
    }
    override suspend fun countIn(conversationId: String) = dao.countIn(conversationId)
    override suspend fun deleteOldestIn(conversationId: String, keep: Int) = dao.deleteOldestIn(conversationId, keep)
    override suspend fun deleteExpired(now: Long) = dao.deleteExpired(now)
}
