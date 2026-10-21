package com.meldiq.android.data.model

import com.meldiq.android.data.api.CommunityNotifyDto
import com.meldiq.android.data.api.GriipJson
import com.meldiq.android.data.api.NavPrefsDto
import com.meldiq.android.ui.screens.community.CategoryMenuItem
import com.meldiq.android.ui.screens.community.ChannelMenuItem
import com.meldiq.android.ui.screens.community.CommunityMenuRules
import com.meldiq.android.ui.screens.community.CommunityPanelRules
import com.meldiq.android.ui.screens.community.PanelChannel
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Batch B3: timed mutes, category levels, hidden channels and slowmode (pure rules). */
class ChannelControlsRulesTest {
    private val now = 1_000_000_000L
    private fun ch(id: String, mentions: Int = 0, cat: String? = "cat_a") =
        PanelChannel(id, id, "PUBLIC", false, "text", cat, "A", 0, null, joined = true, mentions = mentions)

    @Test
    fun `a timed mute silences until it ends, then the usual level is back`() {
        val p = NavPrefsRules.apply(NavPrefsDto(), NavOp.MuteUntil("c1", now + 60_000))
        assertEquals(NotifyLevel.NONE, NavPrefsRules.level(p, "c1", now = now))
        assertEquals(NotifyLevel.ALL, NavPrefsRules.level(p, "c1", now = now + 60_001))
        assertEquals(now + 60_000, NavPrefsRules.mutedUntil(p, "c1", now))
        assertNull(NavPrefsRules.mutedUntil(p, "c1", now + 60_001))
        // Choosing a level (Unmute) ends it straight away.
        assertNull(NavPrefsRules.apply(p, NavOp.Notify("c1", NotifyLevel.ALL)).mutedUntil?.get("c1"))
        assertNull(NavPrefsRules.apply(p, NavOp.MuteUntil("c1", null)).mutedUntil?.get("c1"))
    }

    @Test
    fun `channels follow their category, then the community`() {
        val p = NavPrefsDto(community = CommunityNotifyDto(NotifyLevel.MENTIONS), categoryNotify = mapOf("cat_a" to NotifyLevel.NONE))
        assertEquals(NotifyLevel.NONE, NavPrefsRules.level(p, "c1", "cat_a", now))
        assertEquals(NotifyLevel.MENTIONS, NavPrefsRules.level(p, "c2", "cat_b", now))
        assertEquals(NotifyLevel.MENTIONS, NavPrefsRules.level(p, "c3", null, now))
        // "All" under a quiet category is a real override and is kept; under an "all" parent it isn't stored.
        assertEquals(NotifyLevel.ALL, NavPrefsRules.apply(p, NavOp.Notify("c1", NotifyLevel.ALL, "cat_a")).notify["c1"])
        assertNull(NavPrefsRules.apply(NavPrefsDto(), NavOp.Notify("c1", NotifyLevel.ALL, "cat_a")).notify["c1"])
        // A category back to following the community.
        assertNull(NavPrefsRules.apply(p, NavOp.CategoryNotify("cat_a", null)).categoryNotify?.get("cat_a"))
    }

    @Test
    fun `hidden channels leave the list but come back when they mention you or are open`() {
        val p = NavPrefsRules.replay(NavPrefsDto(), listOf(NavOp.Hide("c1", true), NavOp.Hide("c2", true), NavOp.Hide("c2", false), NavOp.Hide("c1", true)))
        assertEquals(listOf("c1"), p.hidden)
        assertTrue(NavPrefsRules.isHidden(p, "c1"))
        val shown = CommunityPanelRules.sections(listOf(ch("c1"), ch("c2"), ch("c3", mentions = 1)), emptyList(), emptySet(), hidden = setOf("c1", "c3"))
            .flatMap { it.shown }.map { it.id }
        assertEquals(listOf("c2", "c3"), shown)
        val open = CommunityPanelRules.sections(listOf(ch("c1")), emptyList(), emptySet(), selectedId = "c1", hidden = setOf("c1")).flatMap { it.shown }.map { it.id }
        assertEquals(listOf("c1"), open)
    }

    @Test
    fun `menus offer hide, show and the category level only where the server keeps them`() {
        val c = ch("c1")
        assertTrue(ChannelMenuItem.HIDE in CommunityMenuRules.channel(c, false, false, false, false, false, canHide = true))
        assertTrue(ChannelMenuItem.SHOW in CommunityMenuRules.channel(c, false, false, false, false, false, canHide = true, hidden = true))
        assertFalse(ChannelMenuItem.HIDE in CommunityMenuRules.channel(c, false, false, false, false, false))
        val cat = { set: Boolean, isCat: Boolean -> CommunityMenuRules.category(isCat, false, false, true, false, false, false, false, false, canSetLevel = set) }
        assertTrue(CategoryMenuItem.NOTIFY in cat(true, true))
        assertFalse(CategoryMenuItem.NOTIFY in cat(false, true))
        assertFalse(CategoryMenuItem.NOTIFY in cat(true, false))
    }

    @Test
    fun `timed mutes end on schedule`() {
        val docs = listOf(NavPrefsDto(mutedUntil = mapOf("a" to now + 5_000, "b" to now - 1)), NavPrefsDto(mutedUntil = mapOf("c" to now + 2_000)))
        assertEquals(now + 2_000, MuteRules.nextExpiry(docs, now))
        assertNull(MuteRules.nextExpiry(listOf(NavPrefsDto()), now))
        assertEquals(now + 900_000, MuteRules.until(MuteRules.DURATIONS.first(), now))
        assertNull(MuteRules.until(null, now))
    }

    @Test
    fun `slowmode counts down from the server's next allowed time and leaves exempt people alone`() {
        val s = Slowmode(30, exempt = false, nextPostAt = now + 12_500)
        assertEquals(13, SlowmodeRules.secondsLeft(s, now))
        assertEquals(0, SlowmodeRules.secondsLeft(s, now + 12_500))
        assertEquals(0, SlowmodeRules.secondsLeft(s.copy(exempt = true), now))
        assertEquals(now + 30_000, SlowmodeRules.nextAfterPost(s, now))
        assertNull(SlowmodeRules.nextAfterPost(s.copy(exempt = true), now))
        assertEquals(now + 9, SlowmodeRules.nextFrom(buildJsonObject { put("retryAfter", JsonPrimitive(9)); put("nextPostAt", JsonPrimitive(now + 9)) }))
        assertNull(SlowmodeRules.nextFrom(null))
        assertEquals(5 to SlowmodeRules.Unit.MINUTES, SlowmodeRules.short(300))
        assertEquals(6 to SlowmodeRules.Unit.HOURS, SlowmodeRules.short(21600))
        assertEquals(listOf(0, 5, 10, 30, 60, 300, 900, 3600, 21600), SlowmodeRules.STEPS)
    }

    @Test
    fun `new ops survive storage, and old stored notify ops still decode`() {
        val ops = listOf<NavOp>(NavOp.MuteUntil("c1", now), NavOp.CategoryNotify("cat_a", NotifyLevel.NONE), NavOp.Hide("c1", true), NavOp.Notify("c1", "all", "cat_a"))
        val ser = kotlinx.serialization.builtins.ListSerializer(NavOp.serializer())
        assertEquals(ops, GriipJson.decodeFromString(ser, GriipJson.encodeToString(ser, ops)))
        val old = GriipJson.decodeFromString(ser, """[{"type":"notify","conversationId":"c1","level":"none"}]""")
        assertEquals(listOf<NavOp>(NavOp.Notify("c1", "none")), old)
    }
}
