package com.griipbuddy.orbit.data.model

import com.griipbuddy.orbit.data.api.CallDto

/**
 * Call history lines in conversations (2026-10-17; unit-tested in `CallHistoryTest`).
 *
 * Each call has one entry, `call-<callId>`, written when the call's key arrives and updated from the server's record of
 * the call (live call events, and the conversation's call list when it opens). The outcome comes only from what the
 * server recorded: who started the call, whether and when it was answered, when and why it ended, and who connected.
 * A duration is shown only when both ends of it were recorded; nothing is estimated.
 */
object CallHistory {
    const val COMPLETED = "completed"
    const val MISSED = "missed"
    const val DECLINED = "declined"
    const val BUSY = "busy"
    const val NO_ANSWER = "no_answer"
    const val CANCELLED = "cancelled"
    const val FAILED = "failed"
    const val ONGOING = "ongoing"
    /** A group call on a server that doesn't say who connected: it ended, and that's all that's known. */
    const val ENDED = "ended"

    /**
     * The entry for [call] as seen by [me]. [declinedHere]: this device declined it (the server tells only the caller
     * about a group-call decline).
     */
    fun summarize(call: CallDto, me: String?, declinedHere: Boolean = false): CallInfo {
        val group = call.conversationType != null && call.conversationType != "DM"
        val mine = me != null && call.createdBy == me
        val base = CallInfo(call.id, call.video, startedBy = call.createdBy, group = group)
        if (call.state != "ENDED") return base.copy(outcome = ONGOING)
        val reason = call.endReason
        val failed = reason == "error" || reason == "lost" || reason == "removed"
        if (group) {
            val attended = call.attended ?: return base.copy(outcome = ENDED)
            val people = attended.map { it.userId }.distinct().size
            val myTime = attended.filter { it.userId == me }
            return when {
                myTime.isNotEmpty() && call.answeredAt != null ->
                    base.copy(outcome = COMPLETED, durationSec = myTime.sumOf { it.seconds }.coerceAtLeast(0), joined = people)
                myTime.isNotEmpty() -> base.copy(outcome = NO_ANSWER, joined = people) // nobody else came
                declinedHere -> base.copy(outcome = DECLINED, joined = people)
                mine -> base.copy(outcome = if (failed) FAILED else CANCELLED, joined = people)
                else -> base.copy(outcome = MISSED, joined = people)
            }
        }
        if (call.answeredAt != null) {
            val ended = call.endedAt
            return base.copy(outcome = COMPLETED, durationSec = ended?.let { ((it - call.answeredAt) / 1000).coerceAtLeast(0) })
        }
        val outcome = if (mine) {
            when (reason) {
                "declined" -> DECLINED
                "busy" -> BUSY
                "missed" -> NO_ANSWER
                "error", "lost", "removed" -> FAILED
                else -> CANCELLED // the caller hung up before an answer
            }
        } else {
            if (reason == "declined" || declinedHere) DECLINED else MISSED
        }
        return base.copy(outcome = outcome)
    }

    /** A call attempt that never reached the server (the start request failed): kept only on this phone. */
    fun failedAttempt(callId: String, video: Boolean, me: String?, group: Boolean) =
        CallInfo(callId, video, outcome = FAILED, startedBy = me, group = group)

    data class Line(val title: String, val detail: String?, val missed: Boolean)

    /** What the conversation shows. [outgoing]: I started it; [starter]: their display name (groups). */
    fun line(info: CallInfo, outgoing: Boolean, starter: String?): Line {
        val kind = if (info.video) "video call" else "voice call"
        val groupKind = if (info.group) "group $kind" else kind
        val duration = info.durationSec?.let(::duration)
        val joined = info.joined?.takeIf { info.group && it > 0 }?.let { if (it == 1) "1 person joined" else "$it people joined" }
        val by = if (info.group && !outgoing) starter?.let { "Started by $it" } else null
        return when (info.outcome) {
            COMPLETED -> Line(
                if (info.group) groupKind.cap() else "${if (outgoing) "Outgoing" else "Incoming"} $kind",
                listOfNotNull(duration, joined, by).joinToString(" · ").ifEmpty { null },
                false,
            )
            MISSED -> Line("Missed $groupKind", by, true)
            DECLINED -> Line(if (outgoing) "${kind.cap()} declined" else "You declined a $groupKind", by, false)
            BUSY -> Line("${kind.cap()} not answered", "They were on another call", false)
            NO_ANSWER -> Line(if (info.group) "${groupKind.cap()} · no one joined" else "${kind.cap()} · no answer", by, false)
            CANCELLED -> Line("Cancelled $groupKind", by, false)
            FAILED -> Line("${groupKind.cap()} failed", "The call couldn't be connected", false)
            ONGOING -> Line("${groupKind.cap()} in progress", by, false)
            ENDED -> Line("${groupKind.cap()} ended", by, false)
            else -> Line("${if (outgoing) "Outgoing" else "Incoming"} $kind", null, false)
        }
    }

    /** The chat list preview of a call entry. */
    fun preview(info: CallInfo?, outgoing: Boolean, fallback: String): String =
        "📞 " + (info?.let { line(it, outgoing, null).title } ?: fallback)

    /** "45 s", "2 min 5 s", "1 h 2 min". */
    fun duration(sec: Long): String {
        val s = sec.coerceAtLeast(0)
        return when {
            s < 60 -> "$s s"
            s < 3600 -> if (s % 60 == 0L) "${s / 60} min" else "${s / 60} min ${s % 60} s"
            else -> if ((s % 3600) / 60 == 0L) "${s / 3600} h" else "${s / 3600} h ${(s % 3600) / 60} min"
        }
    }

    private fun String.cap() = replaceFirstChar { it.uppercase() }
}
