@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.meldiq.android.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meldiq.android.data.api.OrgDetailRes
import com.meldiq.android.data.api.OrgDto
import com.meldiq.android.data.db.CallLogEntity
import com.meldiq.android.data.db.ConversationEntity
import com.meldiq.android.data.db.UserEntity
import com.meldiq.android.data.model.ThreadActivity
import com.meldiq.android.data.model.ThreadActivityRules
import com.meldiq.android.di.AppContainer
import com.meldiq.android.ui.friendly
import com.meldiq.android.ui.screens.workspace.WorkspaceList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The Home tab's data. Conversations, people and the call log come from this phone's database; the workspaces from
 * the server (cached for offline starts) through the existing [com.meldiq.android.data.repo.OrgRepository], which also
 * keeps the selected one. It outlives the screen while a chat is open on top, so coming back draws at once.
 */
class HomeViewModel(private val c: AppContainer) : ViewModel() {
    private companion object {
        const val THREAD_REPLIES_SCANNED = 300
        const val RECENT_SENT = 30
    }

    /** Null until the database first answers (the loading state). */
    val conversations: StateFlow<List<ConversationEntity>?> = c.conversations.observeAll().stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val users: StateFlow<List<UserEntity>> = c.db.users().observeAll().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val callLog: StateFlow<List<CallLogEntity>> = c.db.calls().observeLog().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val me: StateFlow<UserEntity?> = c.db.users().observe(c.session.session?.userId.orEmpty()).stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Unseen replies in threads you're part of (Home's Threads card; the Activity tab lists them). */
    val threadUnseen: StateFlow<Int> = run {
        val me = c.session.session?.userId
        if (me == null) MutableStateFlow(0)
        else combine(
            // Batch B8: followed threads count too.
            c.followedThreads.followed.flatMapLatest { f -> c.messages.observeThreadReplies(me, System.currentTimeMillis() - ThreadActivityRules.WINDOW_MS, THREAD_REPLIES_SCANNED, f) },
            c.threadSeen.seen,
        ) { replies, seen -> ThreadActivityRules.summarize(replies, seen).sumOf { it.unseen } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, 0)
    }

    /** Your latest sent messages (Home › Drafts & sent). */
    val recentSent: StateFlow<List<com.meldiq.android.data.db.MessageEntity>> =
        c.db.messages().observeRecentSent(RECENT_SENT).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Messages saved for later (Home's Later card). */
    val saved = c.navPrefs.saved

    /** Whether the system lets Meldiq post notifications (Get started). */
    fun notificationsAllowed(): Boolean = androidx.core.app.NotificationManagerCompat.from(c.app).areNotificationsEnabled()
    val selectedOrg: StateFlow<String?> = c.orgs.selectedOrg

    private val _orgs = MutableStateFlow<List<OrgDto>?>(null)
    /** Your workspaces (null: not known yet; empty: none). */
    val orgs: StateFlow<List<OrgDto>?> = _orgs.asStateFlow()
    private val _orgError = MutableStateFlow<String?>(null)
    val orgError: StateFlow<String?> = _orgError.asStateFlow()
    private val _refreshing = MutableStateFlow(true)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()
    private val _detail = MutableStateFlow<OrgDetailRes?>(null)
    /** The selected workspace's structure and your permissions in it (null: none selected, or not loaded yet). */
    val detail: StateFlow<OrgDetailRes?> = _detail.asStateFlow()
    private val _serverChannels = MutableStateFlow<List<com.meldiq.android.data.api.ConversationDto>>(emptyList())
    /** The selected community's channels as the server lists them for you (to show the ones you haven't joined). */
    val serverChannels: StateFlow<List<com.meldiq.android.data.api.ConversationDto>> = _serverChannels.asStateFlow()

    init {
        refresh()
        // The selected workspace's details (cached first, then the server's), never another workspace's: the value is
        // cleared before each switch so nothing from the previous one shows while the next loads.
        viewModelScope.launch {
            selectedOrg.collectLatest { id ->
                _detail.value = null
                _serverChannels.value = emptyList()
                if (id == null) return@collectLatest
                c.orgs.cachedDetail(id)?.let { _detail.value = it }
                runCatching { c.orgs.detail(id) }.onSuccess { if (selectedOrg.value == id) _detail.value = it }
                loadChannels(id)
            }
        }
        viewModelScope.launch { c.messages.orgEvents.collect { id -> if (id == selectedOrg.value) reloadDetail() } }
        // Membership changed somewhere (joined, removed, a workspace deleted): reload the list.
        viewModelScope.launch { c.messages.orgEvents.collect { loadOrgs() } }
        // A workspace made or joined on another screen is selected there before this list knows it: reload.
        viewModelScope.launch { selectedOrg.collect { if (WorkspaceHomeRules.selectionUnknown(_orgs.value, it)) loadOrgs() } }
    }

    fun refresh() = viewModelScope.launch {
        _refreshing.value = true
        loadOrgs()
        runCatching { c.conversations.refresh() }
        _refreshing.value = false
    }

    private suspend fun loadOrgs() {
        if (_orgs.value == null) c.orgs.cachedOrgs()?.let { _orgs.value = WorkspaceList.normalize(it) }
        runCatching { c.orgs.orgs() }
            .onSuccess { fetched ->
                val list = WorkspaceList.normalize(fetched)
                _orgs.value = list
                _orgError.value = null
                val sel = WorkspaceHomeRules.resolveOrg(list, selectedOrg.value)
                if (sel != selectedOrg.value) c.orgs.select(sel)
            }
            .onFailure { _orgError.value = it.friendly() }
    }

    /** Reloads the selected workspace's details (after its settings changed here or elsewhere). */
    fun reloadDetail() = viewModelScope.launch {
        val id = selectedOrg.value ?: return@launch
        runCatching { c.orgs.detail(id) }.onSuccess { if (selectedOrg.value == id) _detail.value = it }
        loadChannels(id)
    }

    private suspend fun loadChannels(orgId: String) {
        runCatching { c.orgs.channels(orgId) }.onSuccess { if (selectedOrg.value == orgId) _serverChannels.value = it }
    }

    /** Joins a channel you may see but aren't in, then [then] (opens it); [onError] says why it failed. */
    fun join(id: String, then: (String) -> Unit, onError: (String) -> Unit) = viewModelScope.launch {
        runCatching { c.conversations.join(id) }
            .onSuccess { then(id); selectedOrg.value?.let { loadChannels(it) } }
            .onFailure { onError(it.friendly()) }
    }

    /** Switches the workspace Home shows, through the same saved selection the Workspace screen uses. */
    fun switchOrg(id: String) = viewModelScope.launch { c.orgs.select(id) }
}

/**
 * The Activity tab's data: conversations (for @mentions), the call log (missed calls), and whether you may read the
 * selected workspace's change log (the server's permission; the screen only offers it when allowed).
 */
class ActivityViewModel(private val c: AppContainer) : ViewModel() {
    private companion object {
        const val THREAD_REPLIES_SCANNED = 300
        const val THREADS_SHOWN = 20
    }

    val conversations: StateFlow<List<ConversationEntity>?> = c.conversations.observeAll().stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val users: StateFlow<List<UserEntity>> = c.db.users().observeAll().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val callLog: StateFlow<List<CallLogEntity>> = c.db.calls().observeLog().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Threads you're part of with replies by others in the last two weeks, unseen ones first (this phone's history). */
    val threads: StateFlow<List<ThreadActivity>> = run {
        val me = c.session.session?.userId
        if (me == null) MutableStateFlow(emptyList())
        else combine(
            // Batch B8: followed threads count too.
            c.followedThreads.followed.flatMapLatest { f -> c.messages.observeThreadReplies(me, System.currentTimeMillis() - ThreadActivityRules.WINDOW_MS, THREAD_REPLIES_SCANNED, f) },
            c.threadSeen.seen,
        ) { replies, seen -> ThreadActivityRules.summarize(replies, seen).take(THREADS_SHOWN) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    }

    /** The selected workspace and its name, when you may view its change log (null otherwise). */
    data class OrgLog(val orgId: String, val name: String)

    private val _orgLog = MutableStateFlow<OrgLog?>(null)
    val orgLog: StateFlow<OrgLog?> = _orgLog.asStateFlow()

    init {
        viewModelScope.launch {
            c.orgs.selectedOrg.collectLatest { id ->
                _orgLog.value = null
                if (id == null) return@collectLatest
                c.orgs.cachedDetail(id)?.let { d -> if (d.permissions.canViewDirectory) _orgLog.value = OrgLog(id, d.org.name) }
                runCatching { c.orgs.detail(id) }.onSuccess { d -> _orgLog.value = if (d.permissions.canViewDirectory) OrgLog(id, d.org.name) else null }
            }
        }
    }
}
