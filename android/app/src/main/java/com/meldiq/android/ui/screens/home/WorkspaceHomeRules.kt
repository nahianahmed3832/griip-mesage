package com.meldiq.android.ui.screens.home

import androidx.compose.runtime.Immutable
import com.meldiq.android.data.api.NavSectionDto
import com.meldiq.android.data.api.OrgDto
import com.meldiq.android.data.db.CallLogEntity
import com.meldiq.android.data.db.ConversationEntity
import com.meldiq.android.ui.screens.calls.CallLogText
import com.meldiq.android.ui.screens.chats.ChatListRules

/** A custom section on Home (docs/WORKSPACE_ADMINISTRATION.md › Personal sections). */
@Immutable
data class CustomSection(
    /** "personal" or the organisation id whose preferences hold it. */
    val workspaceId: String,
    val id: String,
    val name: String,
    val collapsed: Boolean,
    val items: List<ConversationEntity>,
) {
    val unread: Int get() = items.count { it.unreadCount > 0 }
}

/** A collapsible group of channels on the Home tab: one organisation, branch or team and its channels you've joined. */
@Immutable
data class ChannelGroup(
    /** Stable across refreshes: the scope and its id ("TEAM:t1"); also the key that remembers it collapsed. */
    val key: String,
    val title: String,
    /** ORG, BRANCH or TEAM, as the server scopes the channel (null on old data). */
    val scope: String?,
    val channels: List<ConversationEntity>,
) {
    val unreadChannels: Int get() = channels.count { it.unreadCount > 0 }
}

/**
 * What the Home tab shows (UI-002, unit-tested in `WorkspaceHomeRulesTest`). Everything comes from data already on this
 * phone: conversations with their unread and @mention counts and drafts, the call log, and the organisations the server
 * says you belong to. Nothing is invented; a section with nothing real to show says so instead.
 *
 * Scope: Home belongs to the selected workspace. Direct messages and personal groups have no workspace, so they show
 * whichever one is selected; channels show only for their own workspace.
 */
object WorkspaceHomeRules {
    /** Unread DMs listed on Home before "See all". */
    const val UNREAD_DMS_SHOWN = 5

    /** The Calls card counts calls missed in the last day; the Activity tab lists a week of them. */
    const val MISSED_CALLS_CARD_WINDOW_MS = 24L * 60 * 60 * 1000
    const val MISSED_CALLS_ACTIVITY_WINDOW_MS = 7L * 24 * 60 * 60 * 1000
    const val MISSED_CALLS_SHOWN = 20

    private val scopeOrder = mapOf("ORG" to 0, "BRANCH" to 1, "TEAM" to 2)

    /** Direct messages and personal groups: the DMs tab and the Unread DMs section. */
    fun isPeople(c: ConversationEntity) = ChatListRules.isPeople(c)

    /** In the selected workspace's view: its own channels, and every conversation that has no workspace. */
    fun inWorkspace(c: ConversationEntity, orgId: String?) = c.orgId == null || c.orgId == orgId

    /** Listed on Home: still a member, and not archived on this phone (archived chats are under DMs › Archived). */
    private fun listed(c: ConversationEntity) = !c.removed && !ChatListRules.isArchived(c)

    /** Unread direct messages and groups, in the chat list's order (newest message first). */
    fun unreadDms(convs: List<ConversationEntity>): List<ConversationEntity> =
        convs.filter { isPeople(it) && listed(it) && it.unreadCount > 0 }

    /**
     * Conversations in this workspace's view where someone @mentioned you since you last read them. The count is kept
     * on this phone from the messages it decrypted; opening the chat clears it. Archived chats are included: a mention
     * still matters.
     */
    fun mentions(convs: List<ConversationEntity>, orgId: String?): List<ConversationEntity> =
        allMentions(convs).filter { inWorkspace(it, orgId) }

    /** Unread @mentions in every workspace, for the Activity tab. */
    fun allMentions(convs: List<ConversationEntity>): List<ConversationEntity> = convs.filter { !it.removed && it.mentionCount > 0 }

    /**
     * The selected workspace's joined channels, grouped by where they live (the organisation, a branch, a team), groups
     * in that order then by name, channels by name. [unreadOnly]: only channels with unread messages, and only groups
     * that still have one.
     */
    fun channelGroups(
        convs: List<ConversationEntity>,
        orgId: String?,
        orgName: String,
        unreadOnly: Boolean = false,
        placed: Set<String> = emptySet(),
    ): List<ChannelGroup> {
        if (orgId == null) return emptyList()
        return convs.asSequence()
            // A channel that's starred or in a custom section shows there instead of twice.
            .filter { it.type == "CHANNEL" && it.orgId == orgId && it.joined && listed(it) && it.id !in placed && (!unreadOnly || it.unreadCount > 0) }
            .groupBy { "${it.scope ?: "ORG"}:${it.scopeId ?: orgId}" }
            .map { (key, channels) ->
                val first = channels.first()
                val title = first.scopeName?.takeIf { it.isNotBlank() } ?: orgName
                ChannelGroup(key, title, first.scope, channels.sortedWith(compareBy({ it.name.orEmpty().lowercase() }, { it.id })))
            }
            .sortedWith(compareBy({ scopeOrder[it.scope] ?: scopeOrder.size }, { it.title.lowercase() }, { it.key }))
    }

    /**
     * Starred conversations in this workspace's view (its channels, and DMs and groups, which belong to every view), in
     * the person's own order ([order]). Starring is personal and kept for every device of the person
     * ([com.meldiq.android.data.repo.NavPreferences]). [unreadOnly]: only those with unread messages.
     */
    fun starred(convs: List<ConversationEntity>, orgId: String?, order: List<String>, unreadOnly: Boolean = false): List<ConversationEntity> =
        ordered(convs, order).filter { listed(it) && inWorkspace(it, orgId) && (!unreadOnly || it.unreadCount > 0) }

    /**
     * The person's custom sections for this workspace's view, each with its conversations in their order. Conversations
     * no longer on this phone (left, deleted) are skipped. [unreadOnly]: only sections that still have an unread one.
     */
    fun customSections(
        convs: List<ConversationEntity>,
        orgId: String?,
        sections: List<Pair<String, NavSectionDto>>,
        unreadOnly: Boolean = false,
    ): List<CustomSection> = sections.mapNotNull { (workspaceId, s) ->
        val items = ordered(convs, s.items).filter { listed(it) && inWorkspace(it, orgId) && (!unreadOnly || it.unreadCount > 0) }
        if (unreadOnly && items.isEmpty()) null else CustomSection(workspaceId, s.id, s.name, s.collapsed, items)
    }

    private fun ordered(convs: List<ConversationEntity>, ids: List<String>): List<ConversationEntity> {
        if (ids.isEmpty()) return emptyList()
        val byId = convs.associateBy { it.id }
        return ids.mapNotNull { byId[it] }
    }

    /** The Unreads card: unread conversations in this workspace's view. */
    fun unreadChats(convs: List<ConversationEntity>, orgId: String?): Int =
        convs.count { listed(it) && it.unreadCount > 0 && inWorkspace(it, orgId) }

    /** Conversations with an unsent draft saved on this phone (restored when the chat is opened). */
    fun drafts(convs: List<ConversationEntity>): List<ConversationEntity> = convs.filter { !it.removed && !it.draft.isNullOrBlank() }

    /** Incoming calls nobody answered on this phone, started within [windowMs] before [now], newest first. */
    fun missedCalls(log: List<CallLogEntity>, now: Long, windowMs: Long): List<CallLogEntity> =
        log.filter { CallLogText.missed(it) && it.startedAt <= now && now - it.startedAt <= windowMs }.sortedByDescending { it.startedAt }

    /** The workspace to show: the saved choice while you still belong to it, otherwise the first one (null: none). */
    fun resolveOrg(orgs: List<OrgDto>, selected: String?): String? =
        selected?.takeIf { s -> orgs.any { it.id == s } } ?: orgs.firstOrNull()?.id

    /**
     * The selection names a workspace the loaded list doesn't have: one made or joined on another screen, which selects
     * it, after Home loaded its list. Home then reloads the list once. False while the list is still loading.
     */
    fun selectionUnknown(orgs: List<OrgDto>?, selected: String?): Boolean =
        selected != null && orgs != null && orgs.none { it.id == selected }

    /** Badge on the DMs tab: unread messages in direct messages and groups that aren't muted or archived. */
    fun dmBadge(convs: List<ConversationEntity>): Int = convs.filter { isPeople(it) && listed(it) && !it.muted }.sumOf { it.unreadCount }

    /** Badge on the Home tab: channels (any workspace) with unread messages, not muted or archived. */
    fun homeBadge(convs: List<ConversationEntity>): Int = convs.count { it.type == "CHANNEL" && listed(it) && !it.muted && it.unreadCount > 0 }

    /** Badge on the Activity tab: unread @mentions anywhere (mentions notify even in muted chats). */
    fun activityBadge(convs: List<ConversationEntity>): Int = convs.filter { !it.removed }.sumOf { it.mentionCount }
}
