package com.griipbuddy.message.data.repo

import com.griipbuddy.message.data.api.ApiClient
import com.griipbuddy.message.data.api.ConversationDto
import com.griipbuddy.message.data.api.ConversationRes
import com.griipbuddy.message.data.api.ConversationsRes
import com.griipbuddy.message.data.api.DmReq
import com.griipbuddy.message.data.api.GriipJson
import com.griipbuddy.message.data.api.GroupReq
import com.griipbuddy.message.data.api.OkRes
import com.griipbuddy.message.data.api.PatchConversationReq
import com.griipbuddy.message.data.api.RoleReq
import com.griipbuddy.message.data.api.UsersRes
import com.griipbuddy.message.data.db.ConversationEntity
import com.griipbuddy.message.data.db.GriipDatabase
import com.griipbuddy.message.data.model.MemberSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.builtins.ListSerializer

class ConversationRepository(
    private val api: ApiClient,
    private val db: GriipDatabase,
    private val users: UserRepository,
) {
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
    } catch (e: com.griipbuddy.message.data.api.ApiException) {
        if (e.status == 404) db.conversations().markRemoved(id)
        db.conversations().get(id)
    }

    suspend fun upsertFromServer(d: ConversationDto): ConversationEntity {
        d.members?.forEach { users.remember(it) }
        val members = d.members.orEmpty().map { MemberSummary(it.id, it.username, it.displayName, it.avatarColor, it.role, it.online) }
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
        )
        if (d.joined || local != null) db.conversations().upsert(e)
        return e
    }

    suspend fun openDm(userId: String): ConversationEntity = upsertFromServer(api.post<DmReq, ConversationRes>("/v1/conversations/dm", DmReq(userId)).conversation)

    suspend fun createGroup(name: String, memberIds: List<String>): ConversationEntity =
        upsertFromServer(api.post<GroupReq, ConversationRes>("/v1/conversations/group", GroupReq(name, memberIds)).conversation)

    suspend fun members(id: String) = api.get<UsersRes>("/v1/conversations/$id/members").members.also { it.forEach { u -> users.remember(u) } }

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

        fun peer(e: ConversationEntity, myUserId: String?): MemberSummary? =
            if (e.type == "DM") members(e).firstOrNull { it.id != myUserId } else null
    }
}
