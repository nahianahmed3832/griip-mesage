package com.griipbuddy.orbit.data.repo

import com.griipbuddy.orbit.data.api.ApiClient
import com.griipbuddy.orbit.data.api.ConversationDto
import com.griipbuddy.orbit.data.api.ConversationRes
import com.griipbuddy.orbit.data.api.ConversationsRes
import com.griipbuddy.orbit.data.api.DmReq
import com.griipbuddy.orbit.data.api.GriipJson
import com.griipbuddy.orbit.data.api.GroupReq
import com.griipbuddy.orbit.data.api.OkRes
import com.griipbuddy.orbit.data.api.PatchConversationReq
import com.griipbuddy.orbit.data.api.RoleReq
import com.griipbuddy.orbit.data.api.UsersRes
import com.griipbuddy.orbit.data.db.ConversationEntity
import com.griipbuddy.orbit.data.db.GriipDatabase
import com.griipbuddy.orbit.data.model.MemberSummary
import com.griipbuddy.orbit.data.api.ConversationFederationDto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.builtins.ListSerializer

class ConversationRepository(
    private val api: ApiClient,
    private val db: GriipDatabase,
    private val users: UserRepository,
) {
    private val _federation = MutableStateFlow<Map<String, ConversationFederationDto>>(emptyMap())

    /** Latest server-reported state of other servers per conversation (in memory; refreshed with the conversation). */
    val federation: StateFlow<Map<String, ConversationFederationDto>> = _federation.asStateFlow()

    fun observeAll(): Flow<List<ConversationEntity>> = db.conversations().observeAll()
    fun observe(id: String): Flow<ConversationEntity?> = db.conversations().observe(id)
    fun totalUnread(): Flow<Int> = db.conversations().observeTotalUnread()

    /** Pull membership metadata from the server, merging it with local-only fields. */
    suspend fun refresh() {
        val list = api.get<ConversationsRes>("/v1/conversations").conversations
        val seen = list.map { it.id }.toSet()
        list.forEach { upsertFromServer(it) }
        db.conversations().all().filter { it.id !in seen && !it.removed && it.joined }.forEach { db.conversations().markRemoved(it.id) }
    }

    suspend fun refreshOne(id: String): ConversationEntity? = try {
        upsertFromServer(api.get<ConversationRes>("/v1/conversations/$id").conversation)
    } catch (e: com.griipbuddy.orbit.data.api.ApiException) {
        if (e.status == 404) db.conversations().markRemoved(id)
        db.conversations().get(id)
    }

    suspend fun upsertFromServer(d: ConversationDto): ConversationEntity {
        d.members?.forEach { users.remember(it) }
        val members = d.members.orEmpty().map { MemberSummary(it.id, it.username, it.displayName, it.avatarColor, it.role, it.online, it.homeServer) }
        _federation.update { m -> if (d.federation != null) m + (d.id to d.federation) else m - d.id }
        _mentionAll.update { m -> if (d.canMentionAll) m + (d.id to true) else m - d.id }
        val local = db.conversations().get(d.id)
        val e = ConversationEntity(
            id = d.id, type = d.type, orgId = d.orgId, scope = d.scope, scopeId = d.scopeId, scopeName = d.scopeName, kind = d.kind,
            name = d.name, topic = d.topic, archived = d.archived, memberCount = d.memberCount,
            membersJson = if (d.members != null) GriipJson.encodeToString(ListSerializer(MemberSummary.serializer()), members) else local?.membersJson ?: "[]",
            joined = d.joined, myRole = d.myRole, canPost = d.canPost, canManage = d.canManage,
            activeCallId = d.activeCall?.id, activeCallVideo = d.activeCall?.video ?: false, serverUpdatedAt = d.updatedAt,
            lastMessageId = local?.lastMessageId, lastMessagePreview = local?.lastMessagePreview, lastMessageAt = local?.lastMessageAt ?: 0,
            lastSenderName = local?.lastSenderName, unreadCount = local?.unreadCount ?: 0, mentionCount = local?.mentionCount ?: 0,
            muted = local?.muted ?: false, draft = local?.draft, removed = !d.joined && local?.joined == true,
            archivedAt = local?.archivedAt,
        )
        if (d.joined || local != null) db.conversations().upsert(e)
        return e
    }

    suspend fun openDm(userId: String): ConversationEntity = upsertFromServer(api.post<DmReq, ConversationRes>("/v1/conversations/dm", DmReq(userId)).conversation)

    suspend fun createGroup(name: String, memberIds: List<String>): ConversationEntity =
        upsertFromServer(api.post<GroupReq, ConversationRes>("/v1/conversations/group", GroupReq(name, memberIds)).conversation)

    suspend fun members(id: String) = api.get<UsersRes>("/v1/conversations/$id/members").members.also { it.forEach { u -> users.remember(u) } }

    private val _mentionAll = MutableStateFlow<Map<String, Boolean>>(emptyMap())

    /** Conversations where the server lets this account use @everyone (refreshed with the conversation). */
    val mentionAll: StateFlow<Map<String, Boolean>> = _mentionAll

    /**
     * The people a mention can point at: the conversation's full member list from the server, or (offline) the members
     * stored with it. Ids are the mention targets; usernames are what the text shows.
     */
    suspend fun mentionMembers(id: String): List<com.griipbuddy.orbit.data.model.MentionMember> {
        val remote = runCatching { members(id) }.getOrNull()
        if (remote != null) return remote.map { com.griipbuddy.orbit.data.model.MentionMember(it.id, it.username, it.displayName) }.also { memberCache[id] = it }
        return knownMentionMembers(id)
    }

    private val memberCache = java.util.concurrent.ConcurrentHashMap<String, List<com.griipbuddy.orbit.data.model.MentionMember>>()

    /** The members already known on this phone (last full list fetched, else the ones stored with the conversation): no network. */
    suspend fun knownMentionMembers(id: String): List<com.griipbuddy.orbit.data.model.MentionMember> {
        memberCache[id]?.let { return it }
        val conv = db.conversations().get(id) ?: return emptyList()
        return ConversationRepository.members(conv).map { com.griipbuddy.orbit.data.model.MentionMember(it.id, it.username, it.displayName) }
    }

    suspend fun update(id: String, name: String? = null, topic: String? = null, archived: Boolean? = null) =
        upsertFromServer(api.patch<PatchConversationReq, ConversationRes>("/v1/conversations/$id", PatchConversationReq(name, topic, archived)).conversation)

    suspend fun join(id: String) = upsertFromServer(api.post<ConversationRes>("/v1/conversations/$id/join").conversation)

    suspend fun addMember(id: String, userId: String, role: String = "MEMBER") {
        api.put<RoleReq, OkRes>("/v1/conversations/$id/members/$userId", RoleReq(role))
        refreshOne(id)
    }

    suspend fun removeMember(id: String, userId: String) {
        api.delete<OkRes>("/v1/conversations/$id/members/$userId")
        refreshOne(id)
    }

    suspend fun setMuted(id: String, muted: Boolean) = db.conversations().setMuted(id, muted)
    /** Archive ([archived]) or restore a chat on this phone only: membership, history and notifications don't change. */
    suspend fun setArchived(id: String, archived: Boolean) =
        db.conversations().setArchived(id, if (archived) System.currentTimeMillis() else null)
    suspend fun markUnread(id: String) = db.conversations().markUnread(id)
    suspend fun markRead(id: String) = db.conversations().markRead(id)
    suspend fun setDraft(id: String, draft: String?) = db.conversations().setDraft(id, draft)

    companion object {
        fun members(e: ConversationEntity): List<MemberSummary> =
            runCatching { GriipJson.decodeFromString(ListSerializer(MemberSummary.serializer()), e.membersJson) }.getOrDefault(emptyList())

        /** Display title: channel "#name", group name, or the other person in a DM. */
        fun title(e: ConversationEntity, myUserId: String?): String = when (e.type) {
            "CHANNEL" -> e.name ?: "channel"
            "GROUP" -> e.name ?: "Group"
            else -> members(e).firstOrNull { it.id != myUserId }?.displayName ?: "Direct message"
        }

        /** People in the conversation whose account is on another server (federation). */
        fun remoteMembers(e: ConversationEntity): List<MemberSummary> = members(e).filter { GlobalAddress.isRemote(it.username, it.homeServer) }

        fun peer(e: ConversationEntity, myUserId: String?): MemberSummary? =
            if (e.type == "DM") members(e).firstOrNull { it.id != myUserId } else null
    }
}
