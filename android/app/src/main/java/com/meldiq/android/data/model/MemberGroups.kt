package com.meldiq.android.data.model

import com.meldiq.android.data.api.RoleRef
import com.meldiq.android.data.api.UserDto

/**
 * Batch B12 (matrix COM-09): the member list grouped like a server's member list. Each role marked "show members
 * separately" heads its own group, in role order; a person appears once, under their highest such role; everyone else
 * follows. Order inside a group is the server's (owners and admins first, then by name).
 */
object MemberGroups {
    data class Group(val role: RoleRef?, val members: List<UserDto>)

    fun group(members: List<UserDto>): List<Group> {
        fun topHoisted(u: UserDto) = u.customRoles.filter { it.hoist }.minByOrNull { it.position }
        val byRole = members.groupBy { topHoisted(it)?.id }
        val roles = members.mapNotNull(::topHoisted).distinctBy { it.id }.sortedBy { it.position }
        val groups = roles.map { r -> Group(r, byRole[r.id].orEmpty()) }
        val rest = byRole[null].orEmpty()
        return if (rest.isEmpty()) groups else groups + Group(null, rest)
    }
}
