package com.meldiq.android.data.repo

/**
 * Pacing for late-joiner key requests (T5.3). A call's media key is sent once, when the call starts,
 * to the devices that exist then. A device that joins later, or a newly linked one, asks for it with a
 * `call_key_request` control, and the call's creator re-sends the same key as a `call_invite`. Only
 * the creator answers, so T5.2's rule (keys only from the creator) still holds.
 *
 * A key is usually just in flight when the joiner first looks, so a request goes out only after
 * [graceMs] without it, then at most every [intervalMs]. The creator answers each call at most every
 * [intervalMs], however many devices ask. State is in memory and bounded to [maxCalls] calls.
 */
class CallKeyRequests(
    private val now: () -> Long = System::currentTimeMillis,
    private val graceMs: Long = 1_500,
    private val intervalMs: Long = 5_000,
    private val maxCalls: Int = 64,
) {
    private val firstMiss = bounded()
    private val lastRequest = bounded()
    private val lastAnswer = bounded()

    /** Called each time the joiner finds no key; true when a request should be sent now. */
    @Synchronized
    fun shouldRequest(callId: String): Boolean {
        val t = now()
        val first = firstMiss.getOrPut(callId) { t }
        if (t - first < graceMs) return false
        val last = lastRequest[callId]
        if (last != null && t - last < intervalMs) return false
        lastRequest[callId] = t
        return true
    }

    /** On the creator's device, for each request received; true when the key should be re-sent now. */
    @Synchronized
    fun shouldAnswer(callId: String): Boolean {
        val t = now()
        val last = lastAnswer[callId]
        if (last != null && t - last < intervalMs) return false
        lastAnswer[callId] = t
        return true
    }

    private fun bounded() = object : LinkedHashMap<String, Long>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?) = size > maxCalls
    }
}
