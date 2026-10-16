package com.griipbuddy.orbit.data.model

import kotlinx.serialization.Serializable

/** End-to-end encrypted payload (the plaintext inside griip-envelope-v1). */
@Serializable
data class Payload(
    val type: String,                       // text | edit | delete | reaction | pin | read | call_invite | call_key_request
    val text: String? = null,
    val replyTo: String? = null,
    val threadRoot: String? = null,
    val mentions: List<String>? = null,
    /** @everyone (docs/MENTIONS.md). Omitted unless true, so other messages keep their exact wire format. */
    val mentionAll: Boolean? = null,
    val attachments: List<AttachmentRef>? = null,
    val target: String? = null,
    val emoji: String? = null,
    val add: Boolean? = null,
    val pinned: Boolean? = null,
    val upTo: Long? = null,                 // read receipt: server timestamp cutoff
    val callId: String? = null,
    val video: Boolean? = null,
    val e2eeKey: String? = null,
)

@Serializable
data class AttachmentRef(
    val id: String,
    val name: String,
    val mime: String,
    val size: Long,
    val key: String,
    val nonce: String,
    val sha256: String,
    // Voice messages (2026-10-16). Optional and omitted when absent, so other attachments keep their exact wire
    // format; older clients ignore these and show the clip as an ordinary audio file.
    val voice: Boolean? = null,
    val durationMs: Long? = null,
)

@Serializable
data class MemberSummary(
    val id: String,
    val username: String,
    val displayName: String,
    val avatarColor: Int = 0,
    val role: String? = null,
    val online: Boolean? = null,
    /** Home server id of someone on another server (federation); null for people on this server. */
    val homeServer: String? = null,
)

@Serializable
data class CallInfo(val callId: String, val video: Boolean, val outcome: String? = null, val durationSec: Long? = null)

object MessageStatus {
    const val SENDING = "SENDING"
    const val SENT = "SENT"
    const val DELIVERED = "DELIVERED"
    const val READ = "READ"
    const val FAILED = "FAILED"
    const val RECEIVED = "RECEIVED"

    /** Not sent yet: a member's security key changed and the user hasn't reviewed it (T1.2). */
    const val HELD = "HELD"
}
