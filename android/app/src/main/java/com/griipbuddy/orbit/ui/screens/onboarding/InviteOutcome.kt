package com.griipbuddy.orbit.ui.screens.onboarding

import com.griipbuddy.orbit.data.api.ApiException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * What the person is told when an invite can't be previewed or accepted. The server's own reason is kept: it says
 * whether the code is unknown, cancelled, expired, used up, or no longer valid because its creator lost the right
 * (codes `invite_*`, servers from 2026-10-16; older servers send one combined 404 message, shown as it is).
 */
object InviteOutcome {
    private val FALLBACK = mapOf(
        "invite_not_found" to "This invite code doesn't exist. Check the code with the person who sent it.",
        "invite_revoked" to "This invite was cancelled. Ask for a new one.",
        "invite_expired" to "This invite has expired. Ask for a new one.",
        "invite_used_up" to "This invite has already been used as many times as it allows. Ask for a new one.",
        "invite_unavailable" to "This invite can no longer be used. Ask for a new one.",
    )

    fun message(e: Throwable): String {
        if (e !is ApiException) return "Something went wrong. Please try again."
        return when {
            e.isNetwork -> "Can't reach the server. Check your connection and try again."
            e.code in FALLBACK -> e.message.ifBlank { FALLBACK.getValue(e.code) }
            e.status == 401 -> "You're signed out on this server. Sign in again, then use the code."
            e.status == 429 -> "Too many attempts. Wait a minute, then try the code again."
            // A reply this app couldn't read: say so plainly rather than blaming the code.
            e.code == "decode" -> "The server answered in a way this version of the app doesn't understand. Update the app, or tell your admin."
            else -> e.message
        }
    }

    /** The workspace a refused "create" points at (409 org_name_taken), so the person can open it instead. */
    fun existingOrg(e: Throwable): String? {
        if (e !is ApiException || e.code != "org_name_taken") return null
        return ((e.details as? JsonObject)?.get("orgId") as? JsonPrimitive)?.content
    }
}
