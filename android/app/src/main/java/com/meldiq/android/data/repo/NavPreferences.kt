package com.meldiq.android.data.repo

import android.util.Log
import com.meldiq.android.data.api.ApiClient
import com.meldiq.android.data.api.ApiException
import com.meldiq.android.data.api.GriipJson
import com.meldiq.android.data.api.NavPrefsDto
import com.meldiq.android.data.api.NavPrefsRes
import com.meldiq.android.data.api.PutNavPrefsReq
import com.meldiq.android.data.db.ConversationEntity
import com.meldiq.android.data.db.GriipDatabase
import com.meldiq.android.data.db.KvEntity
import com.meldiq.android.data.model.NavOp
import com.meldiq.android.data.model.NavPrefsRules
import com.meldiq.android.data.model.NotifyLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

/**
 * Personal navigation preferences (docs/WORKSPACE_ADMINISTRATION.md › Personal sections): starred conversations, custom
 * sections, collapsed Home groups and notification levels, one document per workspace ("personal" or an organisation
 * id). The server keeps them (servers with `nav-preferences`), so every device of the person shows the same Home; this
 * profile's encrypted database keeps a copy for offline use, plus the changes the server doesn't have yet. Those are
 * replayed onto the server's latest copy, so two devices editing at once both keep their change.
 *
 * Mute is the existing per-conversation switch: "mentions" (and "none" from another client) means muted on this phone.
 */
class NavPreferences(
    private val api: ApiClient,
    private val db: GriipDatabase,
    private val features: ServerFeatures,
    private val scope: CoroutineScope,
) {
    @Serializable
    data class LocalDoc(
        val rev: Int = 0,
        val server: NavPrefsDto = NavPrefsDto(),
        val pending: List<NavOp> = emptyList(),
        /** This phone's mutes from before syncing were added once (so updating the app never unmutes anything). */
        val mutesMerged: Boolean = false,
    ) {
        val effective: NavPrefsDto get() = NavPrefsRules.replay(server, pending)
    }

    private val docs = MutableStateFlow<Map<String, LocalDoc>>(emptyMap())
    private val lock = Mutex()

    /** Effective preferences per loaded workspace (server copy plus changes not sent yet). */
    val prefs: StateFlow<Map<String, NavPrefsDto>> =
        docs.map { m -> m.mapValues { it.value.effective } }.stateIn(scope, SharingStarted.Eagerly, emptyMap())

    /** Ids starred in any loaded workspace (Home shows its organisation's channels and everyone's DMs and groups). */
    val starred: StateFlow<Set<String>> =
        prefs.map { m -> m.values.flatMap { it.starred }.toSet() }.stateIn(scope, SharingStarted.Eagerly, emptySet())

    /** Messages saved for later, from the personal document (Home › Later). */
    val saved: StateFlow<List<com.meldiq.android.data.api.SavedItemDto>> =
        prefs.map { m -> m[PERSONAL]?.saved.orEmpty() }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Whether this server keeps saved messages (`saved-items`); without it the app offers no Later actions. */
    suspend fun savingSupported(): Boolean = features.has(ServerFeatures.SAVED_ITEMS) == true

    /** Saves [messageId] (in [conversationId]) for later, optionally with a reminder at [remindAt]. */
    fun saveForLater(conversationId: String, messageId: String, remindAt: Long? = null, now: Long = System.currentTimeMillis()) =
        change(PERSONAL, NavOp.Save(conversationId, messageId, now, remindAt))

    fun removeSaved(messageId: String) = change(PERSONAL, NavOp.Unsave(messageId))

    fun setSavedDone(messageId: String, done: Boolean) = change(PERSONAL, NavOp.SavedDone(messageId, done))

    fun remind(messageId: String, remindAt: Long?) = change(PERSONAL, NavOp.Remind(messageId, remindAt))

    private val _syncError = MutableStateFlow<String?>(null)
    /** Last sync problem, if any (offline is not a problem: changes wait). */
    val syncError: StateFlow<String?> = _syncError.asStateFlow()

    /** Reads the stored copy of [workspaceId] at once, then brings it up to date with the server. */
    suspend fun load(workspaceId: String) {
        if (workspaceId !in docs.value) {
            val stored = db.kv().get(key(workspaceId))?.let { runCatching { GriipJson.decodeFromString(LocalDoc.serializer(), it) }.getOrNull() }
            docs.update { it + (workspaceId to (stored ?: LocalDoc())) }
        }
        sync(workspaceId)
    }

    /** Applies [op] here at once and sends it when the server can take it. */
    fun change(workspaceId: String, op: NavOp) {
        scope.launch {
            lock.withLock {
                val doc = docs.value[workspaceId] ?: LocalDoc()
                save(workspaceId, doc.copy(pending = doc.pending + op))
            }
            sync(workspaceId)
        }
    }

    /** Stars or unstars [conv] in its workspace. */
    fun toggleStar(conv: ConversationEntity) {
        val ws = workspaceOf(conv)
        val isStarred = conv.id in (prefs.value[ws]?.starred ?: emptyList())
        change(ws, NavOp.Place(conv.id, sectionId = null, starred = !isStarred))
    }

    /** Mutes or unmutes [conv] on this phone at once and on the person's other devices through the server. */
    suspend fun setMuted(conv: ConversationEntity, muted: Boolean) {
        db.conversations().setMuted(conv.id, muted)
        change(workspaceOf(conv), NavOp.Notify(conv.id, if (muted) NotifyLevel.MENTIONS else NotifyLevel.ALL))
    }

    /**
     * Sends waiting changes and fetches the latest copy. On a server without `nav-preferences` (or offline) the changes
     * stay on this phone and are sent later.
     */
    suspend fun sync(workspaceId: String) {
        val supported = features.has(ServerFeatures.NAV_PREFERENCES)
        if (supported != true) {
            Log.i(TAG, "sync $workspaceId skipped: server support $supported")
            return
        }
        lock.withLock {
            repeat(MAX_ATTEMPTS) {
                val doc = docs.value[workspaceId] ?: LocalDoc()
                val remote = runCatching { api.get<NavPrefsRes>("/v1/me/preferences/$workspaceId") }.getOrElse { e ->
                    Log.w(TAG, "sync $workspaceId: fetch failed (${e.javaClass.simpleName} ${(e as? ApiException)?.status})")
                    _syncError.value = (e as? ApiException)?.takeUnless { it.isNetwork }?.message
                    return
                }
                val merge = if (doc.mutesMerged) emptyList() else localMutes(workspaceId, NavPrefsRules.replay(remote.prefs, doc.pending))
                val ops = doc.pending + merge
                if (ops.isEmpty()) {
                    save(workspaceId, LocalDoc(remote.rev, remote.prefs, emptyList(), mutesMerged = true))
                    applyMutes(workspaceId, remote.prefs)
                    _syncError.value = null
                    return
                }
                val wanted = NavPrefsRules.replay(remote.prefs, ops)
                val put = runCatching { api.put<PutNavPrefsReq, NavPrefsRes>("/v1/me/preferences/$workspaceId", PutNavPrefsReq(remote.rev, wanted)) }
                put.onSuccess { r ->
                    Log.i(TAG, "sync $workspaceId: saved rev ${r.rev} (${ops.size} change(s))")
                    // Changes made while this request was out stay waiting for the next round.
                    val latest = docs.value[workspaceId] ?: doc
                    save(workspaceId, LocalDoc(r.rev, r.prefs, latest.pending.drop(doc.pending.size), mutesMerged = true))
                    applyMutes(workspaceId, docs.value[workspaceId]?.effective ?: r.prefs)
                    _syncError.value = null
                    return
                }
                val e = put.exceptionOrNull()
                Log.w(TAG, "sync $workspaceId: save failed (${e?.javaClass?.simpleName} ${(e as? ApiException)?.status})")
                // Another device wrote first: fetch its copy and replay again. Anything else waits for the next sync.
                if (e !is ApiException || e.status != 409) {
                    _syncError.value = (e as? ApiException)?.takeUnless { it.isNetwork }?.message
                    return
                }
            }
        }
    }

    /**
     * Applies the synced mute levels to conversations that arrived since (a new channel, a refreshed list). Only for
     * workspaces whose copy came from the server at least once, so this phone's own mutes are never cleared before they
     * were merged up.
     */
    suspend fun reapplyMutes() {
        for ((ws, doc) in docs.value) if (doc.mutesMerged) applyMutes(ws, doc.effective)
    }

    /** Forgets a workspace the person left or that was deleted (the server removed its copy). */
    suspend fun forget(workspaceId: String) = lock.withLock {
        docs.update { it - workspaceId }
        db.kv().delete(key(workspaceId))
    }

    private suspend fun save(workspaceId: String, doc: LocalDoc) {
        docs.update { it + (workspaceId to doc) }
        db.kv().put(KvEntity(key(workspaceId), GriipJson.encodeToString(LocalDoc.serializer(), doc)))
    }

    private suspend fun conversationsOf(workspaceId: String): List<ConversationEntity> =
        db.conversations().all().filter { workspaceOf(it) == workspaceId }

    /** This phone's muted conversations the server copy doesn't have, added the first time a workspace syncs. */
    private suspend fun localMutes(workspaceId: String, prefs: NavPrefsDto): List<NavOp> =
        conversationsOf(workspaceId).filter { it.muted && it.id !in prefs.notify }.map { NavOp.Notify(it.id, NotifyLevel.MENTIONS) }

    /** The server's levels on this phone's mute switches, for the workspace's conversations. */
    private suspend fun applyMutes(workspaceId: String, prefs: NavPrefsDto) {
        for (conv in conversationsOf(workspaceId)) {
            val muted = NavPrefsRules.level(prefs, conv.id) != NotifyLevel.ALL
            if (conv.muted != muted) db.conversations().setMuted(conv.id, muted)
        }
    }

    companion object {
        private const val TAG = "NavPrefs"
        const val PERSONAL = "personal"
        private const val MAX_ATTEMPTS = 3
        private fun key(workspaceId: String) = "nav.prefs.$workspaceId"

        /** Channels belong to their organisation; DMs and groups to the personal workspace. */
        fun workspaceOf(conv: ConversationEntity): String = conv.orgId ?: PERSONAL
    }
}
