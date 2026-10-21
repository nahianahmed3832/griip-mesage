package com.meldiq.android.data.model

import com.meldiq.android.data.api.NavPrefsDto
import com.meldiq.android.data.api.NavSectionDto
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Where a conversation sits on Home: its default group, Starred, or a custom section. */
sealed interface NavPlace {
    data object Default : NavPlace
    data object Starred : NavPlace
    data class Section(val id: String) : NavPlace
}

/** The notification levels the server stores; "all" is the default and isn't stored. */
object NotifyLevel {
    const val ALL = "all"
    const val MENTIONS = "mentions"
    const val NONE = "none"
}

/**
 * One change to personal navigation preferences. Changes are kept until the server has them and are applied again to
 * the server's latest copy, so edits made on two devices (or offline) merge instead of overwriting each other.
 */
@Serializable
sealed interface NavOp {
    @Serializable @SerialName("place") data class Place(val conversationId: String, val sectionId: String?, val starred: Boolean = false) : NavOp
    @Serializable @SerialName("move") data class Move(val conversationId: String, val delta: Int) : NavOp
    @Serializable @SerialName("notify") data class Notify(val conversationId: String, val level: String) : NavOp
    @Serializable @SerialName("addSection") data class AddSection(val id: String, val name: String) : NavOp
    @Serializable @SerialName("renameSection") data class RenameSection(val id: String, val name: String) : NavOp
    @Serializable @SerialName("deleteSection") data class DeleteSection(val id: String) : NavOp
    /** A built-in Home group ("ORG:<id>", "BRANCH:<id>", "TEAM:<id>") opened or closed. */
    @Serializable @SerialName("collapse") data class Collapse(val key: String, val collapsed: Boolean) : NavOp
    @Serializable @SerialName("collapseSection") data class CollapseSection(val id: String, val collapsed: Boolean) : NavOp
    /** Saved for later (personal document only); saving again moves it to the top and keeps its reminder unless given. */
    @Serializable @SerialName("save") data class Save(val conversationId: String, val messageId: String, val savedAt: Long, val remindAt: Long? = null) : NavOp
    @Serializable @SerialName("unsave") data class Unsave(val messageId: String) : NavOp
    @Serializable @SerialName("savedDone") data class SavedDone(val messageId: String, val done: Boolean) : NavOp
    /** Sets or clears ([remindAt] null) a saved message's reminder. */
    @Serializable @SerialName("remind") data class Remind(val messageId: String, val remindAt: Long?) : NavOp
    /** Batch B2: a community's own level and @everyone setting (community documents). Null fields stay as they were. */
    @Serializable @SerialName("community") data class CommunityNotify(val level: String? = null, val suppressEveryone: Boolean? = null) : NavOp
    /** Batch B2: a channel goes back to following its community's level (no override). */
    @Serializable @SerialName("notifyReset") data class NotifyReset(val conversationId: String) : NavOp
    /** Batch B2: the whole rail order (personal document); the last arrangement wins. */
    @Serializable @SerialName("railOrder") data class RailOrder(val orgIds: List<String>) : NavOp
}

/** Pure rules for personal navigation preferences (docs/WORKSPACE_ADMINISTRATION.md › Personal sections). */
object NavPrefsRules {
    const val SECTION_NAME_MAX = 60
    const val MAX_SECTIONS = 30
    const val MAX_STARRED = 200
    const val MAX_SAVED = 500

    fun placeOf(p: NavPrefsDto, conversationId: String): NavPlace = when {
        conversationId in p.starred -> NavPlace.Starred
        else -> p.sections.firstOrNull { conversationId in it.items }?.let { NavPlace.Section(it.id) } ?: NavPlace.Default
    }

    /** A channel's own level if it has one, else its community's (batch B2), else "all". */
    fun level(p: NavPrefsDto, conversationId: String): String = p.notify[conversationId] ?: communityLevel(p)

    /** The level a community's channels follow unless they have their own. */
    fun communityLevel(p: NavPrefsDto): String = p.community?.level ?: NotifyLevel.ALL

    /** Whether @everyone counts as a mention in this community (off: it notifies like an ordinary message). */
    fun countsEveryone(p: NavPrefsDto): Boolean = p.community?.suppressEveryone != true

    /** Channels with a level of their own, as the community notification settings list them. */
    fun overrides(p: NavPrefsDto): Map<String, String> = p.notify

    /**
     * [orgs] in the person's rail order: arranged ones first in that order, then the rest as they came (new
     * communities at the end). Ids that aren't in [orgs] any more are ignored.
     */
    fun <T> railOrdered(orgs: List<T>, order: List<String>?, id: (T) -> String): List<T> {
        if (order.isNullOrEmpty()) return orgs
        val rank = order.withIndex().associate { it.value to it.index }
        return orgs.withIndex().sortedWith(compareBy({ rank[id(it.value)] ?: Int.MAX_VALUE }, { it.index })).map { it.value }
    }

    /** [ids] with [id] moved by [delta] places (negative: up), or to the top when [toTop]; the ends stay put. */
    fun moved(ids: List<String>, id: String, delta: Int, toTop: Boolean = false): List<String> {
        val i = ids.indexOf(id)
        if (i < 0) return ids
        val j = if (toTop) 0 else (i + delta).coerceIn(0, ids.size - 1)
        if (i == j) return ids
        return ids.toMutableList().apply { removeAt(i); add(j, id) }
    }

    /** Every conversation that sits in Starred or a custom section, so it isn't listed again in its default group. */
    fun placed(p: NavPrefsDto): Set<String> = (p.starred + p.sections.flatMap { it.items }).toSet()

    fun apply(p: NavPrefsDto, op: NavOp): NavPrefsDto = when (op) {
        is NavOp.Place -> place(p, op)
        is NavOp.Move -> move(p, op.conversationId, op.delta)
        // "All" is only stored when the community's own level is quieter; otherwise it's the default and isn't kept.
        is NavOp.Notify -> p.copy(
            notify = if (op.level == NotifyLevel.ALL && communityLevel(p) == NotifyLevel.ALL) p.notify - op.conversationId
            else p.notify + (op.conversationId to op.level),
        )
        is NavOp.NotifyReset -> p.copy(notify = p.notify - op.conversationId)
        is NavOp.CommunityNotify -> {
            val current = p.community ?: com.meldiq.android.data.api.CommunityNotifyDto()
            val next = current.copy(level = op.level ?: current.level, suppressEveryone = op.suppressEveryone ?: current.suppressEveryone)
            // Back to "all": an "all" override says nothing any more (the server drops it too).
            p.copy(community = next, notify = if (next.level == NotifyLevel.ALL) p.notify.filterValues { it != NotifyLevel.ALL } else p.notify)
        }
        is NavOp.RailOrder -> p.copy(railOrder = op.orgIds.distinct())
        is NavOp.AddSection -> {
            val name = op.name.trim().take(SECTION_NAME_MAX)
            if (name.isEmpty() || p.sections.size >= MAX_SECTIONS || p.sections.any { it.id == op.id }) p
            else p.copy(sections = p.sections + NavSectionDto(op.id, name))
        }
        is NavOp.RenameSection -> {
            val name = op.name.trim().take(SECTION_NAME_MAX)
            if (name.isEmpty()) p else p.copy(sections = p.sections.map { if (it.id == op.id) it.copy(name = name) else it })
        }
        // Its conversations go back to their default groups.
        is NavOp.DeleteSection -> p.copy(sections = p.sections.filterNot { it.id == op.id })
        is NavOp.Collapse -> p.copy(collapsed = if (op.collapsed) (p.collapsed - op.key) + op.key else p.collapsed - op.key)
        is NavOp.CollapseSection -> p.copy(sections = p.sections.map { if (it.id == op.id) it.copy(collapsed = op.collapsed) else it })
        is NavOp.Save -> {
            val current = p.saved.orEmpty()
            val previous = current.firstOrNull { it.messageId == op.messageId }
            val item = com.meldiq.android.data.api.SavedItemDto(op.conversationId, op.messageId, op.savedAt, op.remindAt ?: previous?.remindAt)
            p.copy(saved = (listOf(item) + current.filterNot { it.messageId == op.messageId }).take(MAX_SAVED))
        }
        is NavOp.Unsave -> p.copy(saved = p.saved.orEmpty().filterNot { it.messageId == op.messageId })
        is NavOp.SavedDone -> p.copy(saved = p.saved.orEmpty().map { if (it.messageId == op.messageId) it.copy(done = op.done, remindAt = if (op.done) null else it.remindAt) else it })
        is NavOp.Remind -> p.copy(saved = p.saved.orEmpty().map { if (it.messageId == op.messageId) it.copy(remindAt = op.remindAt, done = false) else it })
    }

    /** Saved messages still to deal with, newest first; then the finished ones. */
    fun savedOpen(p: NavPrefsDto) = p.saved.orEmpty().filterNot { it.done }
    fun savedDone(p: NavPrefsDto) = p.saved.orEmpty().filter { it.done }

    /** Reminders whose time has passed (Home's Later card says how many). */
    fun overdue(p: NavPrefsDto, now: Long) = savedOpen(p).count { (it.remindAt ?: Long.MAX_VALUE) <= now }

    private fun place(p: NavPrefsDto, op: NavOp.Place): NavPrefsDto {
        val id = op.conversationId
        // Out of wherever it was first: a conversation sits in one place only.
        val starred = p.starred - id
        val sections = p.sections.map { it.copy(items = it.items - id) }
        return when {
            op.starred -> if (starred.size >= MAX_STARRED) p else p.copy(starred = starred + id, sections = sections)
            op.sectionId != null -> {
                if (sections.none { it.id == op.sectionId }) p
                else p.copy(starred = starred, sections = sections.map { if (it.id == op.sectionId) it.copy(items = it.items + id) else it })
            }
            else -> p.copy(starred = starred, sections = sections)
        }
    }

    /** Moves a conversation up ([delta] < 0) or down within Starred or its section; the ends stay put. */
    private fun move(p: NavPrefsDto, id: String, delta: Int): NavPrefsDto {
        fun List<String>.shifted(): List<String> {
            val i = indexOf(id)
            if (i < 0) return this
            val j = (i + delta).coerceIn(0, size - 1)
            if (i == j) return this
            return toMutableList().apply { removeAt(i); add(j, id) }
        }
        return p.copy(starred = p.starred.shifted(), sections = p.sections.map { it.copy(items = it.items.shifted()) })
    }

    /** [ops] applied in order, the way they're replayed onto the server's latest copy. */
    fun replay(p: NavPrefsDto, ops: List<NavOp>): NavPrefsDto = ops.fold(p, ::apply)

    /** A new section's id: "s_" and random letters, unique enough for one person's few sections. */
    fun newSectionId(random: java.util.Random = java.security.SecureRandom()): String =
        "s_" + (1..12).map { ID_CHARS[random.nextInt(ID_CHARS.length)] }.joinToString("")

    private const val ID_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789"
}
