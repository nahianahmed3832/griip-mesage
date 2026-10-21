package com.meldiq.android.data.model

import com.meldiq.android.data.api.RoleRef
import com.meldiq.android.data.api.UserDto
import org.junit.Assert.assertEquals
import org.junit.Test

class MemberGroupsTest {
    private val mods = RoleRef("rl_mod", "Moderators", hoist = true, position = 1)
    private val artists = RoleRef("rl_art", "Artists", hoist = true, position = 2)
    private val fans = RoleRef("rl_fan", "Fans") // not listed separately
    private fun user(id: String, vararg roles: RoleRef) = UserDto(id = id, username = id, displayName = id, customRoles = roles.toList())

    @Test
    fun `listed-separately roles head groups in role order, everyone else follows`() {
        val list = listOf(user("ada", artists), user("bob", artists, mods), user("cy", fans), user("dee"))
        val g = MemberGroups.group(list)
        assertEquals(listOf("Moderators", "Artists", null), g.map { it.role?.name })
        assertEquals(listOf("bob"), g[0].members.map { it.id }) // once, under the higher role
        assertEquals(listOf("ada"), g[1].members.map { it.id })
        assertEquals(listOf("cy", "dee"), g[2].members.map { it.id })
    }

    @Test
    fun `without listed-separately roles it is one group`() {
        val g = MemberGroups.group(listOf(user("cy", fans), user("dee")))
        assertEquals(1, g.size)
        assertEquals(null, g[0].role)
    }
}
