package com.meldiq.android.ui.screens.workspace

import com.meldiq.android.data.api.ApiErrors
import com.meldiq.android.data.api.ApiException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Delete workspace (2026-10-17): owners only, exact name, and honest messages when nothing was deleted. */
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
    fun onlyServersThatCanDeleteOfferIt() {
        assertNull(DeleteWorkspaceRules.available(null)) // not asked yet: the button waits
        assertEquals(false, DeleteWorkspaceRules.available(setOf("profiles")))
        assertEquals(false, DeleteWorkspaceRules.available(emptySet())) // servers before 2026-10-17
        assertEquals(true, DeleteWorkspaceRules.available(setOf("org-delete", "history")))
    }

    @Test
    fun everyFailureSaysNothingWasDeleted() {
        val failures = listOf(
            ApiException(400, "confirm_mismatch", "Type the organization's name exactly"),
            ApiException(403, "forbidden", "Only an owner can delete the organization"),
            ApiException(404, ApiErrors.UNSUPPORTED, ApiErrors.UNSUPPORTED_MESSAGE),
            ApiException(0, "network", "Can't reach the server"),
            ApiException(500, "internal", "Something went wrong on the server"),
            IllegalStateException(),
        )
        for (e in failures) assertTrue(DeleteWorkspaceRules.failure(e), DeleteWorkspaceRules.failure(e).contains("Nothing was deleted"))
        assertEquals("This workspace no longer exists.", DeleteWorkspaceRules.failure(ApiException(404, "not_found", "Organization not found")))
    }
}
