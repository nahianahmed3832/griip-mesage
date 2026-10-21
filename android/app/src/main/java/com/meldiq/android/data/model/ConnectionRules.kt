package com.meldiq.android.data.model

import com.meldiq.android.data.api.ConnectionsRes
import com.meldiq.android.data.db.ConversationEntity

/**
 * Batch B6 (docs/product/MELDIQ_FRIENDS_AND_DMS_SPEC.md §5): what the profile's connection button says for someone,
 * from your own connection lists.
 */
object ConnectionRules {
    enum class State { NONE, SENT, RECEIVED, CONNECTED }

    fun state(lists: ConnectionsRes?, userId: String): State = when {
        lists == null -> State.NONE
        lists.connected.any { it.id == userId } -> State.CONNECTED
        lists.incoming.any { it.user.id == userId } -> State.RECEIVED
        lists.outgoing.any { it.user.id == userId } -> State.SENT
        else -> State.NONE
    }

    /** What "Add by username" sends: the name without a leading @ and spaces; null when nothing is left. */
    fun username(raw: String): String? = raw.trim().removePrefix("@").trim().takeIf { it.isNotEmpty() && it.length <= 64 }
}

/** Batch B6: where a message request is listed and what its conversation allows. */
object RequestRules {
    const val INCOMING = "incoming"
    const val OUTGOING = "outgoing"

    /** Message requests waiting for you, newest first (the Requests row and list). */
    fun incoming(convs: List<ConversationEntity>): List<ConversationEntity> =
        convs.filter { it.request == INCOMING && !it.removed }.sortedByDescending { it.lastMessageAt }

    /** The DMs tab's main list leaves requests out. */
    fun notRequests(convs: List<ConversationEntity>): List<ConversationEntity> = convs.filter { it.request != INCOMING }

    /** Messages you may still send before they answer your request. */
    fun remaining(sent: Int, limit: Int): Int = (limit - sent).coerceAtLeast(0)

    /** The privacy choices, in the order Settings shows them (the server's values). */
    val DM_POLICIES = listOf("everyone", "communities", "connections")
    val REQUEST_POLICIES = listOf("everyone", "communities", "nobody")
}
