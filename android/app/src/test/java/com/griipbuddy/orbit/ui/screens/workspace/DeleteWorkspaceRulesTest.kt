package com.griipbuddy.orbit.ui.screens.workspace

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Delete workspace (2026-10-17): the owner sees a confirmation that deletes nothing. */
class DeleteWorkspaceRulesTest {
    @Test
    fun onlyTheOwnerSeesIt() {
        assertTrue(DeleteWorkspaceRules.visible("OWNER"))
        listOf("ADMIN", "MEMBER", "GUEST", null, "owner").forEach { assertFalse(it.toString(), DeleteWorkspaceRules.visible(it)) }
    }

    @Test
    fun confirmingNeedsTheExactName() {
        assertTrue(DeleteWorkspaceRules.confirmed("Acme Ltd", "Acme Ltd"))
        assertTrue(DeleteWorkspaceRules.confirmed("  Acme Ltd ", "Acme Ltd"))
        assertFalse(DeleteWorkspaceRules.confirmed("acme ltd", "Acme Ltd"))
        assertFalse(DeleteWorkspaceRules.confirmed("Acme", "Acme Ltd"))
        assertFalse(DeleteWorkspaceRules.confirmed("", ""))
    }

    @Test
    fun theSheetNeverTalksToTheServer() {
        // The confirmation is UI only: no API client, repository or HTTP call in the file, and no "deleted" success text.
        val src = File("src/main/java/com/griipbuddy/orbit/ui/screens/workspace/DeleteWorkspace.kt").readLines()
            .filterNot { it.startsWith("import ") || it.startsWith("package ") }.joinToString("\n")
        listOf("api.", "ApiClient", "container", "Repository", "delete(", "post(", "LocalContainer", "launch").forEach { assertFalse("found $it", src.contains(it)) }
        assertFalse(File("src/main/java/com/griipbuddy/orbit/ui/screens/workspace/DeleteWorkspace.kt").readText().contains("ApiClient"))
        assertTrue(src.contains(DeleteWorkspaceRules::class.simpleName!!))
        assertTrue(DeleteWorkspaceRules.NOT_AVAILABLE.startsWith("Deleting a workspace isn't available yet"))
    }
}
