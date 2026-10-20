package com.meldiq.android.ui

import com.meldiq.android.ui.screens.home.SearchRules
import com.meldiq.android.ui.screens.home.SearchRules.Filter
import com.meldiq.android.ui.screens.home.SearchRules.Section
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SearchRulesTest {
    @Test
    fun `device-only filters never ask the server`() {
        assertNull(SearchRules.serverTypes(Filter.MESSAGES))
        assertNull(SearchRules.serverTypes(Filter.FILES))
        assertNull(SearchRules.serverTypes(Filter.PINNED))
        assertEquals("people", SearchRules.serverTypes(Filter.PEOPLE))
        assertEquals("people,channels,workspaces,groups,documents", SearchRules.serverTypes(Filter.ALL))
    }

    @Test
    fun `each filter shows its own section, all shows everything`() {
        assertEquals(listOf(Section.FILES), SearchRules.sections(Filter.FILES))
        assertEquals(Section.entries.toList(), SearchRules.sections(Filter.ALL))
    }

    @Test
    fun `recent searches are newest first, unique and bounded`() {
        var r = emptyList<String>()
        listOf("design", "Budget", "  ", "DESIGN", "a", "b", "c", "d", "e").forEach { r = SearchRules.remember(r, it) }
        assertEquals(listOf("e", "d", "c", "b", "a", "DESIGN"), r)
        assertNull(SearchRules.usable("   "))
        assertEquals(60, SearchRules.usable("x".repeat(80))?.length)
    }
}
