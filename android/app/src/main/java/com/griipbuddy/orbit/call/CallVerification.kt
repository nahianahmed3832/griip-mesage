package com.griipbuddy.orbit.call

/**
 * A call's verification code (2026-10-17, docs/CALLING_AND_E2EE.md › Verifying a call). Unit-tested in
 * `CallVerificationTest`.
 *
 * Every phone in an end-to-end encrypted call holds the same media key, which the caller generated and sent through the
 * conversation's end-to-end encrypted, signed messages. The code is derived from that key and the call's id with
 * HKDF-SHA256, so phones holding the same key for this call show the same four emoji and six digits, and a key that
 * someone (for example a compromised server) substituted for one person would give that person different ones.
 * Comparing codes therefore checks that everyone's media is encrypted with the same key from the same sender. It does
 * not by itself prove who that sender is: that's what the people's safety numbers are for. The key itself never leaves
 * the phone; 44 bits derived from it are shown.
 */
object CallVerification {
    data class Code(val emoji: List<String>, val digits: String) {
        override fun toString() = emoji.joinToString(" ") + "  " + digits
    }

    /** 64 emoji that are easy to tell apart and to name aloud. */
    val EMOJI = listOf(
        "🐶", "🐱", "🦁", "🐴", "🦄", "🐷", "🐘", "🐰", "🐼", "🐓", "🐧", "🐢", "🐟", "🐙", "🦋", "🌷",
        "🌳", "🌵", "🍄", "🌏", "🌙", "☁️", "🔥", "🍌", "🍎", "🍓", "🌽", "🍕", "🎂", "❤️", "😀", "🤖",
        "🎩", "👓", "🔧", "🎅", "👍", "☂️", "⌛", "⏰", "🎁", "💡", "📕", "✏️", "📎", "✂️", "🔒", "🔑",
        "🔨", "☎️", "🏁", "🚂", "🚲", "✈️", "🚀", "🏆", "⚽", "🎸", "🎺", "🔔", "⚓", "🎧", "📁", "📌",
    )

    fun code(key: String, callId: String): Code {
        val okm = hkdf(key.toByteArray(Charsets.UTF_8), "orbit-call-verify-v1".toByteArray(), callId.toByteArray(), 8)
        var bits = 0L
        for (b in okm) bits = (bits shl 8) or (b.toLong() and 0xFF)
        val emoji = (0 until 4).map { i -> EMOJI[((bits ushr (58 - 6 * i)) and 0x3F).toInt()] }
        val digits = ((bits ushr 4) and 0xFFFFF).toInt() % 1_000_000
        return Code(emoji, "%03d %03d".format(digits / 1000, digits % 1000))
    }

    /** RFC 5869 HKDF with SHA-256 (shared with the message history keys). */
    fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray =
        com.griipbuddy.orbit.crypto.Hkdf.derive(ikm, salt, info, length)
}

/**
 * When a call recovers from a dropped connection instead of ending (2026-10-17, docs/CALLING_AND_E2EE.md › Reconnecting).
 * Unit-tested in `CallVerificationTest`.
 */
object CallRecovery {
    /** How long a 1:1 call waits for the other person to come back before it ends ("lost"). */
    const val PEER_WINDOW_MS = 30_000L

    /** How long this phone keeps trying to rejoin after its own connection to the call was lost. */
    const val REJOIN_WINDOW_MS = 45_000L

    /** Why the media connection ended, and whether rejoining can help. */
    fun retryable(reason: String?): Boolean = when (reason) {
        null, "UNKNOWN_REASON", "SIGNAL_CLOSE", "CONNECTION_TIMEOUT", "MEDIA_FAILURE", "STATE_MISMATCH", "JOIN_FAILURE",
        "SERVER_SHUTDOWN", "MIGRATION" -> true
        else -> false // CLIENT_INITIATED, DUPLICATE_IDENTITY, PARTICIPANT_REMOVED, ROOM_DELETED, ROOM_CLOSED, USER_REJECTED…
    }

    /** Wait before rejoin attempt [n] (0-based): 1 s, 2 s, 4 s, then 6 s. */
    fun backoff(n: Int): Long = when (n) {
        0 -> 1_000L
        1 -> 2_000L
        2 -> 4_000L
        else -> 6_000L
    }
}
