package com.meldiq.android.data.model

import com.meldiq.android.data.api.CommunityNotifyDto
import com.meldiq.android.data.api.CommunityTemplateDto
import com.meldiq.android.data.api.GriipJson
import com.meldiq.android.data.api.NavPrefsDto
import com.meldiq.android.data.api.TemplateCategoryDto
import com.meldiq.android.data.api.TemplateChannelDto
import com.meldiq.android.ui.screens.community.CommunityNotifyRules
import com.meldiq.android.ui.screens.community.PanelChannel
import com.meldiq.android.ui.screens.onboarding.CommunityTemplates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Batch B2: a community's notification settings, the rail order and starter templates (pure rules). */
class CommunityNavigationRulesTest {
    private fun ch(id: String, joined: Boolean = true) =
        PanelChannel(id, id, "PUBLIC", false, "text", null, null, 0, null, joined)

    @Test
    fun `channels follow the community level unless they have their own`() {
        val p = NavPrefsRules.apply(NavPrefsDto(notify = mapOf("c2" to NotifyLevel.NONE)), NavOp.CommunityNotify(level = NotifyLevel.MENTIONS))
        assertEquals(NotifyLevel.MENTIONS, NavPrefsRules.level(p, "c1"))
        assertEquals(NotifyLevel.NONE, NavPrefsRules.level(p, "c2"))
        assertEquals(NotifyLevel.ALL, NavPrefsRules.level(NavPrefsDto(), "c1"))
    }

    @Test
    fun `an explicit all is kept only while the community is quieter`() {
        val quiet = NavPrefsRules.apply(NavPrefsDto(), NavOp.CommunityNotify(level = NotifyLevel.NONE))
        val loud = NavPrefsRules.apply(quiet, NavOp.Notify("c1", NotifyLevel.ALL))
        assertEquals(mapOf("c1" to NotifyLevel.ALL), loud.notify)
        assertEquals(NotifyLevel.ALL, NavPrefsRules.level(loud, "c1"))
        // Back to "all" for the community: the override says nothing any more and goes, as on the server.
        val back = NavPrefsRules.apply(loud, NavOp.CommunityNotify(level = NotifyLevel.ALL))
        assertEquals(emptyMap<String, String>(), back.notify)
        // With the default community level, "all" isn't stored at all.
        assertEquals(emptyMap<String, String>(), NavPrefsRules.apply(NavPrefsDto(), NavOp.Notify("c1", NotifyLevel.ALL)).notify)
    }

    @Test
    fun `reset makes a channel follow the community again, and community fields change one at a time`() {
        val p = NavPrefsRules.replay(
            NavPrefsDto(notify = mapOf("c1" to NotifyLevel.NONE)),
            listOf(NavOp.CommunityNotify(suppressEveryone = true), NavOp.CommunityNotify(level = NotifyLevel.MENTIONS), NavOp.NotifyReset("c1")),
        )
        assertEquals(CommunityNotifyDto(NotifyLevel.MENTIONS, suppressEveryone = true), p.community)
        assertEquals(NotifyLevel.MENTIONS, NavPrefsRules.level(p, "c1"))
        assertFalse(NavPrefsRules.countsEveryone(p))
        assertTrue(NavPrefsRules.countsEveryone(NavPrefsDto()))
    }

    @Test
    fun `ignoring @everyone keeps mentions by name`() {
        assertTrue(MentionNotify.isMention(mine = false, personal = true, all = true, countsEveryone = false))
        assertFalse(MentionNotify.isMention(mine = false, personal = false, all = true, countsEveryone = false))
        assertTrue(MentionNotify.isMention(mine = false, personal = false, all = true, countsEveryone = true))
        assertFalse(MentionNotify.isMention(mine = true, personal = true, all = true, countsEveryone = true))
    }

    @Test
    fun `the overrides list shows joined channels with their own level, and offers the rest`() {
        val prefs = NavPrefsDto(notify = mapOf("b" to NotifyLevel.NONE, "x" to NotifyLevel.MENTIONS))
        val channels = listOf(ch("a"), ch("b"), ch("c", joined = false))
        assertEquals(listOf("b" to NotifyLevel.NONE), CommunityNotifyRules.overrides(channels, prefs).map { it.first.id to it.second })
        assertEquals(listOf("a"), CommunityNotifyRules.addable(channels, prefs).map { it.id })
    }

    @Test
    fun `the rail follows the arranged order, with new communities at the end`() {
        val orgs = listOf("o_a", "o_b", "o_c", "o_new")
        assertEquals(listOf("o_c", "o_a", "o_b", "o_new"), NavPrefsRules.railOrdered(orgs, listOf("o_c", "o_gone", "o_a", "o_b")) { it })
        assertEquals(orgs, NavPrefsRules.railOrdered(orgs, null) { it })
        assertEquals(listOf("o_b", "o_a", "o_c"), NavPrefsRules.moved(listOf("o_a", "o_b", "o_c"), "o_b", -1))
        assertEquals(listOf("o_a", "o_b", "o_c"), NavPrefsRules.moved(listOf("o_a", "o_b", "o_c"), "o_c", 1))
        assertEquals(listOf("o_c", "o_a", "o_b"), NavPrefsRules.moved(listOf("o_a", "o_b", "o_c"), "o_c", 0, toTop = true))
        assertEquals(listOf("o_b", "o_a"), NavPrefsRules.apply(NavPrefsDto(), NavOp.RailOrder(listOf("o_b", "o_a", "o_b"))).railOrder)
    }

    @Test
    fun `new fields stay out of requests until used, and new ops survive storage`() {
        val json = GriipJson.encodeToString(NavPrefsDto.serializer(), NavPrefsDto(starred = listOf("c1")))
        assertFalse(json.contains("community"))
        assertFalse(json.contains("railOrder"))
        val ops = listOf<NavOp>(NavOp.CommunityNotify(level = NotifyLevel.NONE), NavOp.NotifyReset("c1"), NavOp.RailOrder(listOf("o_a")))
        val back = GriipJson.decodeFromString(kotlinx.serialization.builtins.ListSerializer(NavOp.serializer()), GriipJson.encodeToString(kotlinx.serialization.builtins.ListSerializer(NavOp.serializer()), ops))
        assertEquals(ops, back)
    }

    @Test
    fun `template summary marks voice channels`() {
        val t = CommunityTemplateDto("study", listOf(TemplateCategoryDto("Study", listOf(TemplateChannelDto("general"), TemplateChannelDto("study-room", voice = true)))))
        assertEquals("#general, 🔊 study-room", CommunityTemplates.channelSummary(t))
        assertNull(CommunityTemplates.KNOWN.firstOrNull { it == "casino" })
    }
}
