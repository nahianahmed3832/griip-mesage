package com.griipbuddy.orbit.data.model

import com.griipbuddy.orbit.data.api.NavPrefsDto
import com.griipbuddy.orbit.data.api.NavSectionDto
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
}

/** Pure rules for personal navigation preferences (docs/WORKSPACE_ADMINISTRATION.md › Personal sections). */
object NavPrefsRules {
    const val SECTION_NAME_MAX = 60
    const val MAX_SECTIONS = 30
    const val MAX_STARRED = 200

    fun placeOf(p: NavPrefsDto, conversationId: String): NavPlace = when {
        conversationId in p.starred -> NavPlace.Starred
        else -> p.sections.firstOrNull { conversationId in it.items }?.let { NavPlace.Section(it.id) } ?: NavPlace.Default
    }

    fun level(p: NavPrefsDto, conversationId: String): String = p.notify[conversationId] ?: NotifyLevel.ALL

    /** Every conversation that sits in Starred or a custom section, so it isn't listed again in its default group. */
    fun placed(p: NavPrefsDto): Set<String> = (p.starred + p.sections.flatMap { it.items }).toSet()

    fun apply(p: NavPrefsDto, op: NavOp): NavPrefsDto = when (op) {
        is NavOp.Place -> place(p, op)
        is NavOp.Move -> move(p, op.conversationId, op.delta)
        is NavOp.Notify -> p.copy(
            notify = if (op.level == NotifyLevel.ALL) p.notify - op.conversationId else p.notify + (op.conversationId to op.level),
        )
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
    }

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
