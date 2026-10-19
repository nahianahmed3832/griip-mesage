package com.meldiq.android.data.model

import com.meldiq.android.data.api.GriipJson
import com.meldiq.android.data.api.NavPrefsDto
import com.meldiq.android.data.api.NavSectionDto
import com.meldiq.android.data.repo.NavPreferences
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Personal navigation preferences (docs/WORKSPACE_ADMINISTRATION.md › Personal sections): the pure rules. */
class NavPrefsRulesTest {
    private val base = NavPrefsDto(
        starred = listOf("c1", "c2"),
        sections = listOf(NavSectionDto("s_a", "Projects", listOf("c3", "c4")), NavSectionDto("s_b", "Clients", listOf("c5"))),
    )

    @Test
    fun `a conversation sits in one place only`() {
        val starred = NavPrefsRules.apply(base, NavOp.Place("c3", sectionId = null, starred = true))
        assertEquals(listOf("c1", "c2", "c3"), starred.starred)
        assertEquals(listOf("c4"), starred.sections[0].items)
        val moved = NavPrefsRules.apply(base, NavOp.Place("c1", "s_b"))
        assertEquals(listOf("c2"), moved.starred)
        assertEquals(listOf("c5", "c1"), moved.sections[1].items)
        assertEquals(NavPlace.Section("s_b"), NavPrefsRules.placeOf(moved, "c1"))
        val back = NavPrefsRules.apply(moved, NavOp.Place("c1", null))
        assertEquals(NavPlace.Default, NavPrefsRules.placeOf(back, "c1"))
        assertEquals(setOf("c2", "c3", "c4", "c5"), NavPrefsRules.placed(back))
    }

    @Test
    fun `placing into a section that no longer exists changes nothing`() {
        assertEquals(base, NavPrefsRules.apply(base, NavOp.Place("c1", "s_gone")))
    }

    @Test
    fun `moving reorders within starred or a section and stops at the ends`() {
        assertEquals(listOf("c2", "c1"), NavPrefsRules.apply(base, NavOp.Move("c1", 1)).starred)
        assertEquals(listOf("c1", "c2"), NavPrefsRules.apply(base, NavOp.Move("c1", -1)).starred)
        assertEquals(listOf("c4", "c3"), NavPrefsRules.apply(base, NavOp.Move("c4", -5)).sections[0].items)
    }

    @Test
    fun `sections are added, renamed, collapsed and deleted`() {
        var p = NavPrefsRules.apply(base, NavOp.AddSection("s_c", "  Later  "))
        assertEquals("Later", p.sections.last().name)
        assertEquals(p, NavPrefsRules.apply(p, NavOp.AddSection("s_c", "Duplicate id")))
        assertEquals(p, NavPrefsRules.apply(p, NavOp.AddSection("s_d", "   ")))
        p = NavPrefsRules.apply(p, NavOp.RenameSection("s_c", "Soon"))
        assertEquals("Soon", p.sections.last().name)
        p = NavPrefsRules.apply(p, NavOp.CollapseSection("s_c", true))
        assertTrue(p.sections.last().collapsed)
        p = NavPrefsRules.apply(p, NavOp.DeleteSection("s_a"))
        assertEquals(listOf("s_b", "s_c"), p.sections.map { it.id })
        // Its conversations are back in their default groups.
        assertEquals(NavPlace.Default, NavPrefsRules.placeOf(p, "c3"))
    }

    @Test
    fun `notification levels store only what differs from all`() {
        val muted = NavPrefsRules.apply(base, NavOp.Notify("c1", NotifyLevel.MENTIONS))
        assertEquals(mapOf("c1" to "mentions"), muted.notify)
        assertEquals(NotifyLevel.MENTIONS, NavPrefsRules.level(muted, "c1"))
        val unmuted = NavPrefsRules.apply(muted, NavOp.Notify("c1", NotifyLevel.ALL))
        assertTrue(unmuted.notify.isEmpty())
        assertEquals(NotifyLevel.ALL, NavPrefsRules.level(unmuted, "c9"))
    }

    @Test
    fun `collapsed groups toggle without duplicates`() {
        var p = NavPrefsRules.apply(base, NavOp.Collapse("TEAM:t1", true))
        p = NavPrefsRules.apply(p, NavOp.Collapse("TEAM:t1", true))
        assertEquals(listOf("TEAM:t1"), p.collapsed)
        assertTrue(NavPrefsRules.apply(p, NavOp.Collapse("TEAM:t1", false)).collapsed.isEmpty())
    }

    @Test
    fun `waiting changes replay onto another device's newer copy`() {
        // This phone starred c9 and muted c1 offline; meanwhile another device added a section.
        val pending = listOf(NavOp.Place("c9", null, starred = true), NavOp.Notify("c1", NotifyLevel.MENTIONS))
        val server = NavPrefsRules.apply(base, NavOp.AddSection("s_new", "From the tablet"))
        val merged = NavPrefsRules.replay(server, pending)
        assertEquals(listOf("c1", "c2", "c9"), merged.starred)
        assertEquals("From the tablet", merged.sections.last().name)
        assertEquals(mapOf("c1" to "mentions"), merged.notify)
    }

    @Test
    fun `waiting changes survive being stored and read back`() {
        val ops = listOf<NavOp>(
            NavOp.Place("c1", "s_a"), NavOp.Move("c1", -1), NavOp.Notify("c2", "none"), NavOp.AddSection("s_x", "X"),
            NavOp.RenameSection("s_x", "Y"), NavOp.DeleteSection("s_x"), NavOp.Collapse("ORG:o1", true), NavOp.CollapseSection("s_a", true),
        )
        val ser = ListSerializer(NavOp.serializer())
        assertEquals(ops, GriipJson.decodeFromString(ser, GriipJson.encodeToString(ser, ops)))
        val doc = NavPreferences.LocalDoc(rev = 3, server = base, pending = ops)
        val back = GriipJson.decodeFromString(NavPreferences.LocalDoc.serializer(), GriipJson.encodeToString(NavPreferences.LocalDoc.serializer(), doc))
        assertEquals(doc, back)
        assertEquals(NavPrefsRules.replay(base, ops), back.effective)
    }

    @Test
    fun `new section ids match what the server accepts`() {
        repeat(20) { assertTrue(NavPrefsRules.newSectionId().matches(Regex("^s_[a-z0-9]{12}$"))) }
    }
}
