package com.meldiq.android.ui.screens.profile

import com.meldiq.android.data.api.ConnectorDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConnectorRulesTest {
    private fun c(id: String, state: String, name: String = id) = ConnectorDto(id = id, name = name, category = "x", state = state)

    @Test
    fun `problems come first and planned last, each sorted by name`() {
        val list = listOf(c("b", "working", "Beta"), c("p", "planned"), c("f", "failing"), c("a", "working", "alpha"), c("n", "needs_configuration"))
        val sections = ConnectorRules.sections(list)
        assertEquals(listOf("failing", "needs_configuration", "working", "planned"), sections.map { it.first })
        assertEquals(listOf("alpha", "Beta"), sections.first { it.first == "working" }.second.map { it.name })
    }

    @Test
    fun `summary counts states and leaves planned out`() {
        val list = listOf(c("a", "working"), c("b", "working"), c("f", "failing"), c("p", "planned"))
        assertEquals("1 failing · 2 working", ConnectorRules.summary(list))
        assertEquals("Nothing to show", ConnectorRules.summary(listOf(c("p", "planned"))))
    }

    @Test
    fun `usage skips zeros and spells keys as words`() {
        assertEquals("2 workspaces · 1 need reconnect", ConnectorRules.usage(mapOf("workspaces" to 2, "connections" to 0, "needReconnect" to 1)))
        assertNull(ConnectorRules.usage(mapOf("devices" to 0)))
    }

    @Test
    fun `configured is never shown as working`() {
        assertEquals("Configured, not verified yet", ConnectorRules.label("configured"))
        assertEquals(ConnectorRules.Tone.Neutral, ConnectorRules.tone("configured"))
        assertEquals(ConnectorRules.Tone.Good, ConnectorRules.tone("working"))
    }
}
