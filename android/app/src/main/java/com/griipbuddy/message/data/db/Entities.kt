package com.griipbuddy.message.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Server-provided conversation metadata merged with local-only state (unread, mute, preview). */
@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val id: String,
    val type: String,                 // DM | GROUP | CHANNEL
    val orgId: String?,
    val scope: String?,
    val scopeId: String?,
    val scopeName: String?,
    val kind: String?,                // PUBLIC | PRIVATE | ANNOUNCEMENT
    val name: String?,
    val topic: String?,
    val archived: Boolean,
    val memberCount: Int,
    val membersJson: String,          // List<MemberSummary> (≤12) for avatars / DM peer
    val joined: Boolean,
    val myRole: String?,
    val canPost: Boolean,
    val canManage: Boolean,
    val activeCallId: String?,
    val activeCallVideo: Boolean,
    val serverUpdatedAt: Long,
    // local
    val lastMessageId: String? = null,
    val lastMessagePreview: String? = null,
    val lastMessageAt: Long = 0,
    val lastSenderName: String? = null,
    val unreadCount: Int = 0,
    val mentionCount: Int = 0,
    val muted: Boolean = false,
    val draft: String? = null,
    val removed: Boolean = false,     // no longer a member (kept so history stays readable)
)

@Entity(
    tableName = "messages",
    indices = [Index("conversationId", "sortTs"), Index("threadRootId"), Index("senderUserId")],
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val senderUserId: String,
    val senderDeviceId: String,
    val outgoing: Boolean,
    val kind: String,                 // text | call | system | undecryptable
    val text: String,
    val replyToId: String? = null,
    val threadRootId: String? = null,
    val mentionsJson: String = "[]",
    val attachmentsJson: String = "[]",
    val sentAt: Long,
    val serverTs: Long? = null,
    val sortTs: Long,
    val status: String,               // SENDING | SENT | DELIVERED | READ | FAILED | RECEIVED
    val errorText: String? = null,
    val editedAt: Long? = null,
    val deleted: Boolean = false,
    val pinned: Boolean = false,
    val reactionsJson: String = "{}", // emoji -> [userId]
    val readByJson: String = "[]",
    val deliveredToJson: String = "[]",
    val callJson: String? = null,
)

/** Messages waiting to be encrypted & submitted. Plaintext lives only in the encrypted local DB. */
@Entity(tableName = "outbox", indices = [Index("nextAttemptAt")])
data class OutboxEntity(
    @PrimaryKey val messageId: String,
    val conversationId: String,
    val cls: String,
    val payloadJson: String,
    val createdAt: Long,
    val attempts: Int = 0,
    val nextAttemptAt: Long = 0,
    val lastError: String? = null,
    val visible: Boolean = true,      // false for control messages (reactions, receipts…)
)

/** Directory cache + trust-on-first-use pin of each user's identity key. */
@Entity(tableName = "users")
data class UserEntity(
    @PrimaryKey val id: String,
    val username: String,
    val displayName: String,
    val avatarColor: Int,
    val statusText: String?,
    val pinnedIdentityKey: String?,
    val identityChanged: Boolean = false,
    val verified: Boolean = false,
    val online: Boolean = false,
    val lastSeenAt: Long? = null,
    val updatedAt: Long = 0,
)

@Entity(tableName = "call_log", indices = [Index("conversationId")])
data class CallLogEntity(
    @PrimaryKey val callId: String,
    val conversationId: String,
    val video: Boolean,
    val outgoing: Boolean,
    val startedAt: Long,
    val answeredAt: Long? = null,
    val endedAt: Long? = null,
    val endReason: String? = null,
    val peerName: String? = null,
)

/** Media E2EE keys received through encrypted messages, kept only until the call ends. */
@Entity(tableName = "call_keys")
data class CallKeyEntity(
    @PrimaryKey val callId: String,
    val conversationId: String,
    val key: String,
    val fromUserId: String,
    val receivedAt: Long,
)

@Entity(tableName = "kv")
data class KvEntity(@PrimaryKey val k: String, val v: String)

/**
 * An incoming envelope from a contact whose identity key changed (T1.2). It was acknowledged to the
 * relay only after being stored here, and is opened if the user accepts the new key.
 */
@Entity(tableName = "quarantine", indices = [Index("senderUserId"), Index("conversationId")])
data class QuarantineEntity(
    @PrimaryKey val queueId: Long,
    val conversationId: String,
    val senderUserId: String,
    val itemJson: String,
    val receivedAt: Long,
)
