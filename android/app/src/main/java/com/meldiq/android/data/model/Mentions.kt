package com.meldiq.android.data.model

/**
 * Mentions in group conversations (2026-10-17, docs/MENTIONS.md). Pure rules, unit-tested in `MentionRulesTest`.
 *
 * In the text a mention is `@username` (a remote member's username is `name:server`), chosen from an autocomplete of
 * the conversation's members. The encrypted payload carries the mentioned **user ids** (`Payload.mentions`), never
 * display names, so renamed people and people with the same display name are unambiguous. `@everyone` is the only
 * mention-all form: the payload says `mentionAll`, the send request asks the server, and recipients treat it as a
 * mention only when the server attested it on their copy (`QueuePayload.mentionAll`).
 */
data class MentionMember(val id: String, val username: String, val displayName: String)

/** What a message mentions: user ids (members only, no duplicates) and whether it notifies everyone. */
data class MentionSet(val ids: List<String>, val all: Boolean) {
    companion object {
        val NONE = MentionSet(emptyList(), false)
    }
}

/** The `@query` being typed at the cursor: [start] is the index of the `@`. */
data class MentionQuery(val start: Int, val query: String)

/** One autocomplete row: a member, or [everyone] for `@everyone`. */
data class MentionSuggestion(val member: MentionMember?, val everyone: Boolean = false) {
    val token: String get() = if (everyone) "@${MentionRules.EVERYONE}" else "@${member!!.username}"
}

/** A mention to draw in a message: the character range, and who it points at (null user = everyone). */
data class MentionSpan(val start: Int, val end: Int, val userId: String?)

object MentionRules {
    const val EVERYONE = "everyone"

    /** Stored in a message's local mention list (never sent) when the message notified everyone. */
    const val EVERYONE_MARK = "@everyone"

    /** `@` then a username: letters, digits, `.`, `_`, and for remote people `:server` with `.` and `-`. */
    private val TOKEN = Regex("""(?<![\p{L}\p{N}_.@])@([A-Za-z0-9._]{1,24}(?::[A-Za-z0-9.-]{1,253})?)""")
    private const val MAX_SUGGESTIONS = 8

    /** The `@query` the cursor is in, if any: `@` at a word start, no spaces up to the cursor. */
    fun activeQuery(text: String, cursor: Int): MentionQuery? {
        if (cursor < 1 || cursor > text.length) return null
        var i = cursor - 1
        while (i >= 0) {
            val ch = text[i]
            if (ch == '@') {
                val before = if (i == 0) ' ' else text[i - 1]
                if (!before.isWhitespace() && before !in "([{\"'") return null // an e-mail address, not a mention
                val q = text.substring(i + 1, cursor)
                return if (q.length <= 40) MentionQuery(i, q) else null
            }
            if (ch.isWhitespace() || ch == '\n') return null
            i--
        }
        return null
    }

    /**
     * Members matching [query] by display name or username (prefix matches first, then other matches), never [myId],
     * plus `@everyone` first when [canMentionAll] and it matches. Members with the same display name all appear;
     * the row shows each one's username.
     */
    fun suggest(members: List<MentionMember>, query: String, myId: String?, canMentionAll: Boolean): List<MentionSuggestion> {
        val q = query.lowercase()
        val out = ArrayList<MentionSuggestion>()
        if (canMentionAll && EVERYONE.startsWith(q)) out += MentionSuggestion(null, everyone = true)
        val candidates = members.filter { it.id != myId }.distinctBy { it.id }
        fun words(m: MentionMember) = listOf(m.username.lowercase()) + m.displayName.lowercase().split(' ', '-', '_').filter { it.isNotEmpty() }
        val prefix = candidates.filter { m -> words(m).any { it.startsWith(q) } || m.displayName.lowercase().startsWith(q) }
        val contains = candidates.filter { m -> m !in prefix && (m.username.lowercase().contains(q) || m.displayName.lowercase().contains(q)) }
        (prefix.sortedBy { it.displayName.lowercase() } + contains.sortedBy { it.displayName.lowercase() })
            .take(MAX_SUGGESTIONS).forEach { out += MentionSuggestion(it) }
        return out
    }

    /** Replaces the typed `@query` with the chosen token and a space; returns the new text and cursor position. */
    fun insert(text: String, query: MentionQuery, choice: MentionSuggestion): Pair<String, Int> {
        val end = (query.start + 1 + query.query.length).coerceAtMost(text.length)
        val token = choice.token + " "
        val rest = text.substring(end).let { if (it.startsWith(" ")) it.substring(1) else it }
        return (text.substring(0, query.start) + token + rest) to (query.start + token.length)
    }

    /**
     * The mentions in [text], resolved only against the conversation's [members] (by username, case-insensitively),
     * so a token can never point at someone outside the conversation or at a same-named person on another server.
     * Unknown tokens stay plain text. `@everyone` counts only when [canMentionAll].
     */
    fun resolve(text: String, members: List<MentionMember>, canMentionAll: Boolean, myId: String? = null): MentionSet {
        val byUsername = members.associateBy { it.username.lowercase() }
        val ids = LinkedHashSet<String>()
        var all = false
        for (m in TOKEN.findAll(text)) {
            var name = m.groupValues[1].lowercase()
            if (name == EVERYONE) {
                all = all || canMentionAll
                continue
            }
            // "@ann." at the end of a sentence: the trailing dots aren't part of the username.
            while (name !in byUsername && (name.endsWith('.') || name.endsWith(':'))) name = name.dropLast(1)
            byUsername[name]?.let { if (it.id != myId) ids += it.id }
        }
        return MentionSet(ids.toList(), all)
    }

    /**
     * Where to draw mentions in a received or sent message. A token is drawn as a mention only if its username belongs to
     * one of the message's mentioned ids ([usernameOf] gives the username for an id, from the local directory); others
     * stay plain. `@everyone` is drawn only when the message really notified everyone ([everyone]).
     */
    fun spans(text: String, mentionIds: List<String>, everyone: Boolean, usernameOf: (String) -> String?): List<MentionSpan> {
        val idByUsername = mentionIds.mapNotNull { id -> usernameOf(id)?.let { it.lowercase() to id } }.toMap()
        val out = ArrayList<MentionSpan>()
        for (m in TOKEN.findAll(text)) {
            var name = m.groupValues[1].lowercase()
            var end = m.range.last + 1
            if (name == EVERYONE) {
                if (everyone) out += MentionSpan(m.range.first, end, null)
                continue
            }
            while (name !in idByUsername && (name.endsWith('.') || name.endsWith(':'))) {
                name = name.dropLast(1)
                end--
            }
            idByUsername[name]?.let { out += MentionSpan(m.range.first, end, it) }
        }
        return out
    }

    /**
     * What a received text message mentions. `@everyone` counts only when the payload asks for it AND the server attested
     * it on this copy ([attested]) AND the conversation is a group or channel: a claim inside the payload alone (a
     * modified client, an old server, another server) stays plain text.
     */
    fun received(payloadIds: List<String>?, payloadAll: Boolean?, attested: Boolean?, conversationType: String?): MentionSet =
        MentionSet(
            payloadIds.orEmpty().filter { it.isNotBlank() && it.length <= 64 }.distinct(),
            payloadAll == true && attested == true && conversationType != null && conversationType != "DM",
        )

    /** The ids to store locally for a message: its mentioned ids plus [EVERYONE_MARK] when it notified everyone. */
    fun stored(set: MentionSet): List<String> = set.ids + if (set.all) listOf(EVERYONE_MARK) else emptyList()

    /** Reads [stored] back. */
    fun fromStored(list: List<String>): MentionSet = MentionSet(list.filter { it != EVERYONE_MARK }, EVERYONE_MARK in list)
}

/** Notification rule for a received message (unit-tested): the app switch first, then mute, then mentions. */
object MentionNotify {
    /**
     * Whether a received message counts as a mention of you: your name ([personal]), or @everyone ([all]) unless the
     * community has @everyone suppressed ([countsEveryone] false, batch B2). Your own messages never do.
     */
    fun isMention(mine: Boolean, personal: Boolean, all: Boolean, countsEveryone: Boolean): Boolean =
        !mine && (personal || (all && countsEveryone))

    /**
     * [notifications]: the app's message notifications setting. A muted conversation stays quiet except for a mention
     * of you by name ([personal]); @everyone ([mention] without [personal]) respects the mute.
     */
    fun shouldNotify(notifications: Boolean, muted: Boolean, mention: Boolean, personal: Boolean): Boolean =
        notifications && (!muted || personal)

    /**
     * Round 3, the rule the web uses too (docs/MENTIONS.md): "all" notifies everything; "mentions" lets mentions and
     * @everyone through; "none" (muted, "Off") only a mention of you by name. A conversation muted on a server without
     * synced levels has [muted] set and level "all": it stays muted.
     */
    fun shouldNotify(notifications: Boolean, level: String, muted: Boolean, mention: Boolean, personal: Boolean): Boolean =
        notifications && when (level) {
            NotifyLevel.NONE -> personal
            NotifyLevel.MENTIONS -> mention
            else -> !muted || personal
        }
}
