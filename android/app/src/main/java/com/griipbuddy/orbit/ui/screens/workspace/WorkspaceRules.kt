package com.griipbuddy.orbit.ui.screens.workspace

import com.griipbuddy.orbit.data.api.ConversationDto
import com.griipbuddy.orbit.data.api.InviteDto
import com.griipbuddy.orbit.data.api.OrgDetailRes
import com.griipbuddy.orbit.data.api.TeamDto

/**
 * Who may change an organization role, title or membership: the app's mirror of the server's `roles.ts`
 * (docs/WORKSPACE_ADMINISTRATION.md). The server decides; these rules only choose which controls to offer, so nobody is
 * shown a button the server would refuse. `WorkspaceRolesTest` checks the same table as the server's `roles.test.ts`.
 */
object WorkspaceRoles {
    const val OWNER = "OWNER"
    const val ADMIN = "ADMIN"
    const val MEMBER = "MEMBER"
    const val GUEST = "GUEST"
    val ALL = listOf(OWNER, ADMIN, MEMBER, GUEST)
    private val low = setOf(MEMBER, GUEST)

    /**
     * The roles [actor] may give [target] (excluding the current one). [ownerCount]: owners in the organization, from
     * the directory; null when unknown, which offers an owner nothing that could leave the organization ownerless.
     */
    fun assignable(actor: String?, target: String?, self: Boolean, ownerCount: Int?): List<String> {
        if (actor == null || target == null) return emptyList()
        val others = ALL - target
        return when (actor) {
            OWNER -> if (target == OWNER && (ownerCount ?: 1) <= 1) emptyList() else others
            ADMIN -> when {
                self && target == ADMIN -> listOf(MEMBER, GUEST)
                target in low -> others.filter { it in low }
                else -> emptyList()
            }
            else -> emptyList()
        }
    }

    fun canChangeTitle(actor: String?, target: String?, self: Boolean): Boolean =
        self || actor == OWNER || (actor == ADMIN && target in low)

    /** Removing someone else (leaving is separate). Owners remove anyone; admins remove members and guests. */
    fun canRemove(actor: String?, target: String?, self: Boolean): Boolean =
        !self && (actor == OWNER || (actor == ADMIN && target in low))

    /** Whether to offer Leave: the server's answer when it gives one; otherwise everyone but owners (who may be the last). */
    fun canLeave(detail: OrgDetailRes?): Boolean = detail?.permissions?.canLeave ?: (detail != null && detail.org.myRole != OWNER)

    /** Owners in a loaded directory (null while it's loading). */
    fun ownerCount(roles: List<String?>?): Int? = roles?.count { it == OWNER }
}

/** Branch and team administration the app offers (the server checks each one again). */
object ScopeAdmin {
    /** Rename and describe a team: its leads and managers (the server's `canManageTeam`). */
    fun canEditTeam(team: TeamDto): Boolean = team.canManage

    /** Archive or restore a team: org owners and admins, and the managers of its branch, not its leads. */
    fun canArchiveTeam(detail: OrgDetailRes, team: TeamDto): Boolean =
        detail.permissions.canManageOrg || (team.branchId != null && detail.branches.any { it.id == team.branchId && it.myRole == "MANAGER" })

    /** Archive or restore a branch: org owners and admins only. Its managers may rename it. */
    fun canArchiveBranch(detail: OrgDetailRes): Boolean = detail.permissions.canManageOrg
}

/** An invitation's one state, from the server (`status`) or, for older servers, worked out the same way. */
enum class InviteState { ACTIVE, USED_UP, EXPIRED, REVOKED }

object InviteStates {
    fun of(inv: InviteDto, now: Long): InviteState = when (inv.status) {
        "active" -> InviteState.ACTIVE
        "revoked" -> InviteState.REVOKED
        "expired" -> InviteState.EXPIRED
        "used_up" -> InviteState.USED_UP
        else -> when {
            inv.revokedAt != null -> InviteState.REVOKED
            inv.expiresAt <= now -> InviteState.EXPIRED
            inv.uses >= inv.maxUses -> InviteState.USED_UP
            else -> InviteState.ACTIVE
        }
    }

    /** The Invitations screen's filter: null shows every invite, true only usable ones, false only ended ones. */
    fun filter(list: List<InviteDto>, activeOnly: Boolean?, now: Long): List<InviteDto> =
        if (activeOnly == null) list else list.filter { (of(it, now) == InviteState.ACTIVE) == activeOnly }
}

/** A channel's access mode (public, private, announcement) and who may change it. */
object ChannelAccess {
    const val PUBLIC = "PUBLIC"
    const val PRIVATE = "PRIVATE"
    const val ANNOUNCEMENT = "ANNOUNCEMENT"
    val MODES = listOf(PUBLIC, PRIVATE, ANNOUNCEMENT)

    /**
     * Offer the mode switch only when the server supports it ([featureOn]), says this person may ([ConversationDto.canManageAccess])
     * and the channel isn't archived (it must be restored first).
     */
    fun canChange(dto: ConversationDto?, featureOn: Boolean?): Boolean =
        featureOn == true && dto != null && dto.type == "CHANNEL" && dto.canManageAccess && !dto.archived
}
