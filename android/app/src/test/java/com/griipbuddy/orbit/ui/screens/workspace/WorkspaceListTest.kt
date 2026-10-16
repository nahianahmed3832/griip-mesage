package com.griipbuddy.orbit.ui.screens.workspace

import com.griipbuddy.orbit.data.api.OrgDto
import com.griipbuddy.orbit.data.api.OrgsRes
import com.griipbuddy.orbit.data.api.GriipJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Physical-device report (2026-10-16): five identical "GriipBuddy" cards in the workspace switcher. */
class WorkspaceListTest {
    private fun org(id: String, name: String, created: Long = 1, slug: String = id) = OrgDto(id, name, slug, myRole = "OWNER", memberCount = 1, createdAt = created)

    @Test
    fun oneEntryPerOrganisationEvenIfAResponseRepeatsIt() {
        val repeated = listOf(org("o_1", "GriipBuddy"), org("o_1", "GriipBuddy"), org("o_2", "Acme"))
        assertEquals(listOf("o_2", "o_1"), WorkspaceList.normalize(repeated).map { it.id })
    }

    @Test
    fun anAccountWithOneWorkspaceShowsOneAcrossRefreshesAndRestarts() {
        val body = """{"orgs":[{"id":"o_1","name":"GriipBuddy","slug":"griipbuddy","myRole":"OWNER","memberCount":1,"createdAt":5}]}"""
        val first = WorkspaceList.normalize(GriipJson.decodeFromString(OrgsRes.serializer(), body).orgs)
        // The cached copy (app restart) and a fresh fetch replace each other; they never accumulate.
        val cached = GriipJson.decodeFromString(OrgsRes.serializer(), GriipJson.encodeToString(OrgsRes.serializer(), OrgsRes(first))).orgs
        val refreshed = WorkspaceList.normalize(cached)
        assertEquals(1, refreshed.size)
        assertEquals(first, refreshed)
    }

    @Test
    fun sameNamedWorkspacesStaySeparateButCanBeToldApart() {
        val dupes = listOf(org("o_b", "GriipBuddy", created = 200, slug = "griipbuddy-k3m9"), org("o_a", "griipbuddy ", created = 100, slug = "griipbuddy"), org("o_c", "Acme"))
        val list = WorkspaceList.normalize(dupes)
        assertEquals(listOf("o_c", "o_a", "o_b"), list.map { it.id }) // stable: by name, then creation time
        assertEquals(setOf("griipbuddy"), WorkspaceList.sharedNames(list))
        assertNotEquals(WorkspaceList.colorSeed(list[1]), WorkspaceList.colorSeed(list[2])) // not the same tile colour
        val sub = WorkspaceList.subtitle(list[2], sameNameAsAnother = true) { "day $it" }
        assertTrue(sub, sub.contains("@griipbuddy-k3m9"))
        assertTrue(sub, sub.startsWith("1 member"))
        assertTrue(sub, sub.endsWith("created day 200"))
    }
}
