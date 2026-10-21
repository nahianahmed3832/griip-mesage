package com.meldiq.android.ui.screens.home

/**
 * Search screen rules (round 7 slice C2, docs/SEARCH.md); pure, unit-tested in `SearchRulesTest`.
 *
 * Where each kind comes from: messages, files and pinned messages from this phone's own history (end-to-end
 * encrypted, so the server can't search them); people, channels, workspaces and groups from the server's search,
 * which applies each one's access rule; conversations from this phone's list.
 */
object SearchRules {
    enum class Filter { ALL, MESSAGES, FILES, PINNED, PEOPLE, CHANNELS, WORKSPACES }

    /** The analytics scope for a filter (the server's closed list: all, messages, people, channels, files, apps). */
    fun analyticsScope(filter: Filter): String = when (filter) {
        Filter.ALL, Filter.WORKSPACES -> "all"
        Filter.MESSAGES, Filter.PINNED -> "messages"
        Filter.PEOPLE -> "people"
        Filter.CHANNELS -> "channels"
        Filter.FILES -> "files"
    }

    /** How many results came back, bucketed as the analytics catalogue requires (never the exact count). */
    fun resultBucket(n: Int): String = when {
        n <= 0 -> "0"
        n == 1 -> "1"
        n <= 5 -> "2-5"
        else -> "6+"
    }

    enum class Section { CONVERSATIONS, CHANNELS, WORKSPACES, GROUPS, PEOPLE, MESSAGES, FILES, PINNED }

    /** Pause after typing before searching. */
    const val DEBOUNCE_MS = 250L

    /** Results per kind shown under "All"; a single-kind filter shows a page and "Show more". */
    const val PREVIEW_COUNT = 5

    const val RECENT_MAX = 6

    fun sections(filter: Filter): List<Section> = when (filter) {
        Filter.ALL -> Section.entries
        Filter.MESSAGES -> listOf(Section.MESSAGES)
        Filter.FILES -> listOf(Section.FILES)
        Filter.PINNED -> listOf(Section.PINNED)
        Filter.PEOPLE -> listOf(Section.PEOPLE)
        Filter.CHANNELS -> listOf(Section.CHANNELS)
        Filter.WORKSPACES -> listOf(Section.WORKSPACES)
    }

    /** The server kinds a filter needs ("people,channels,…"), or null when it needs none. */
    fun serverTypes(filter: Filter): String? = when (filter) {
        Filter.ALL -> "people,channels,workspaces,groups"
        Filter.PEOPLE -> "people"
        Filter.CHANNELS -> "channels"
        Filter.WORKSPACES -> "workspaces"
        Filter.MESSAGES, Filter.FILES, Filter.PINNED -> null
    }

    /** Whether a query is worth sending: at least one non-space character, at most the server's 60. */
    fun usable(q: String): String? = q.trim().takeIf { it.isNotEmpty() }?.take(60)

    /** [q] first, without repeats (ignoring case), at most [RECENT_MAX]. */
    fun remember(recent: List<String>, q: String): List<String> {
        val t = usable(q) ?: return recent
        return (listOf(t) + recent.filterNot { it.equals(t, ignoreCase = true) }).take(RECENT_MAX)
    }
}
