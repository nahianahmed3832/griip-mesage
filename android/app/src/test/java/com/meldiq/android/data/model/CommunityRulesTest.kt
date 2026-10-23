package com.meldiq.android.data.model

import com.meldiq.android.data.api.CategoryDto
import com.meldiq.android.data.api.LayoutChannel
import com.meldiq.android.data.api.OverwriteDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommunityRulesTest {
    private val cats = listOf(CategoryDto("talk", "Talk", position = 1), CategoryDto("start", "Start here", position = 0), CategoryDto("empty", "Empty", position = 2))
    private fun ch(id: String, category: String?, position: Int, name: String = id) = ChannelRef(id, name, category, position)

    @Test
    fun `channels group by category in the community's order, uncategorised first`() {
        val items = listOf(ch("general", "talk", 0), ch("random", null, 0), ch("welcome", "start", 1), ch("rules", "start", 0), ch("intros", "talk", 1))
        val sections = ChannelTree.sections(items, cats, ref = { it })
        assertEquals(listOf(ChannelTree.UNCATEGORISED, "category:start", "category:talk"), sections.map { it.key })
        assertEquals(listOf("rules", "welcome"), sections[1].channels.map { it.id })
        assertEquals(listOf("general", "intros"), sections[2].channels.map { it.id })
        assertEquals("Start here", sections[1].title)
    }

    @Test
    fun `empty categories show only for the people arranging them`() {
        val items = listOf(ch("general", "talk", 0))
        assertEquals(listOf("category:talk"), ChannelTree.sections(items, cats, ref = { it }).map { it.key })
        assertEquals(listOf("category:start", "category:talk", "category:empty"), ChannelTree.sections(items, cats, keepEmpty = true, ref = { it }).map { it.key })
    }

    @Test
    fun `a category the list doesn't know yet keeps its channels, titled from the channel`() {
        val items = listOf(ch("x", "new", 0))
        val s = ChannelTree.sections(items, emptyList(), ref = { it }, categoryName = { "Fresh" })
        assertEquals(listOf("category:new"), s.map { it.key })
        assertEquals("Fresh", s.single().title)
    }

    @Test
    fun `channel types come from kind, voice and format`() {
        assertEquals(ChannelType.VOICE, ChannelTypes.of("PRIVATE", voice = true, format = "text"))
        assertEquals(ChannelType.FORUM, ChannelTypes.of("PUBLIC", voice = false, format = "forum"))
        assertEquals(ChannelType.ANNOUNCEMENT, ChannelTypes.of("ANNOUNCEMENT", voice = false, format = "text"))
        assertEquals(ChannelType.TEXT, ChannelTypes.of(null, voice = false, format = null))
        assertEquals(Triple("PRIVATE", false, "forum"), ChannelTypes.request(ChannelType.FORUM, private = true))
        // Announcements are never private.
        assertEquals(Triple("ANNOUNCEMENT", false, "text"), ChannelTypes.request(ChannelType.ANNOUNCEMENT, private = true))
        assertEquals(Triple("PUBLIC", true, "text"), ChannelTypes.request(ChannelType.VOICE, private = false))
    }

    @Test
    fun `overwrites change one capability at a time`() {
        val o = OverwriteDto("everyone", "everyone", "@everyone", allow = listOf("messages.post"), deny = listOf("channels.view"))
        assertEquals(OverwriteState.ALLOW, OverwriteRules.state(o, "messages.post"))
        assertEquals(OverwriteState.DENY, OverwriteRules.state(o, "channels.view"))
        assertEquals(OverwriteState.INHERIT, OverwriteRules.state(o, "messages.attach"))
        assertEquals(OverwriteState.INHERIT, OverwriteRules.state(null, "messages.attach"))
        assertEquals(listOf("messages.post") to listOf("channels.view", "messages.attach"), OverwriteRules.with(o, "messages.attach", OverwriteState.DENY))
        assertEquals(listOf("messages.post", "channels.view") to emptyList<String>(), OverwriteRules.with(o, "channels.view", OverwriteState.ALLOW))
        assertEquals(emptyList<String>() to listOf("channels.view"), OverwriteRules.with(o, "messages.post", OverwriteState.INHERIT))
        assertTrue(OverwriteRules.gated(listOf(o)))
        assertFalse(OverwriteRules.gated(listOf(o.copy(deny = emptyList()))))
    }

    @Test
    fun `overwrites list in the order they apply`() {
        val list = listOf(
            OverwriteDto("user:u1", "member", "Zed"), OverwriteDto("r2", "role", "Mods"), OverwriteDto("everyone", "everyone", "@everyone"), OverwriteDto("r1", "role", "Artists"),
        )
        assertEquals(listOf("everyone", "r1", "r2", "user:u1"), OverwriteRules.ordered(list).map { it.subject })
    }

    @Test
    fun `layout moves keep everything else in place`() {
        assertEquals(listOf("b", "a", "c"), LayoutRules.move(listOf("a", "b", "c"), "b", -1))
        assertEquals(listOf("a", "b", "c"), LayoutRules.move(listOf("a", "b", "c"), "a", -1))
        assertEquals(listOf("a", "b", "c"), LayoutRules.move(listOf("a", "b", "c"), "c", 1))
        assertEquals(listOf("a", "b", "c"), LayoutRules.move(listOf("a", "b", "c"), "zzz", 1))
        assertEquals(listOf(LayoutChannel("x", "talk", 0), LayoutChannel("y", "talk", 1)), LayoutRules.positions("talk", listOf("x", "y")))
        // Moving into a category puts the channel last there, once.
        assertEquals(listOf(LayoutChannel("a", null, 0), LayoutChannel("c", null, 1)), LayoutRules.moveTo("c", null, listOf("a", "c")))
    }

    @Test
    fun `a channel without rules of its own is in sync with its category`() {
        val none = com.meldiq.android.data.api.OverwriteDto("everyone", "everyone", "@everyone")
        val own = com.meldiq.android.data.api.OverwriteDto("role_a", "role", "Staff", allow = listOf("messages.post"))
        assertEquals(true, OverwriteRules.synced(emptyList()))
        assertEquals(true, OverwriteRules.synced(listOf(none)))
        assertEquals(false, OverwriteRules.synced(listOf(none, own)))
        assertEquals(listOf("role_a"), OverwriteRules.syncTargets(listOf(none, own)))
    }

    @Test
    fun `basic view reads private and who can see it from the view capability`() {
        val list = listOf(
            OverwriteDto("everyone", "everyone", "@everyone", deny = listOf("channels.view")),
            OverwriteDto("user:u1", "member", "Zed", allow = listOf("channels.view", "messages.post")),
            OverwriteDto("r1", "role", "Mods", allow = listOf("channels.view")),
            OverwriteDto("r2", "role", "Muted", deny = listOf("messages.post")),
        )
        assertTrue(OverwriteRules.isPrivate(list))
        assertEquals(listOf("r1", "user:u1"), OverwriteRules.whoCanView(list).map { it.subject })
        assertFalse(OverwriteRules.isPrivate(list.drop(1)))
    }

    @Test
    fun `making a channel private only changes view on everyone, keeping its other rules`() {
        val everyone = OverwriteDto("everyone", "everyone", "@everyone", allow = listOf("messages.post"))
        assertEquals(listOf("messages.post") to listOf("channels.view"), OverwriteRules.privacy(everyone, private = true))
        val gated = OverwriteDto("everyone", "everyone", "@everyone", deny = listOf("channels.view", "messages.attach"))
        assertEquals(emptyList<String>() to listOf("messages.attach"), OverwriteRules.privacy(gated, private = false))
        assertEquals(emptyList<String>() to listOf("channels.view"), OverwriteRules.privacy(null, private = true))
    }

    @Test
    fun `granting and removing access touches only view`() {
        val o = OverwriteDto("r2", "role", "Muted", deny = listOf("messages.post"))
        assertEquals(listOf("channels.view") to listOf("messages.post"), OverwriteRules.access(o, true))
        val v = OverwriteDto("r1", "role", "Mods", allow = listOf("channels.view", "messages.pin"))
        assertEquals(listOf("messages.pin") to emptyList<String>(), OverwriteRules.access(v, false))
    }
}
