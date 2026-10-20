package com.meldiq.android.ui.screens.account

import com.meldiq.android.data.api.ApiErrors
import com.meldiq.android.data.api.ApiException
import com.meldiq.android.data.repo.ServerFeatures
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeleteAccountRulesTest {
    @Test
    fun `the button needs the account's email, in any case and spacing, and a password`() {
        assertTrue(DeleteAccountRules.confirmed(" Ada@Example.com ", "ada@example.com", "pw"))
        assertFalse(DeleteAccountRules.confirmed("ada@example.org", "ada@example.com", "pw"))
        assertFalse(DeleteAccountRules.confirmed("ada@example.com", "ada@example.com", ""))
        assertFalse(DeleteAccountRules.confirmed("", "", "pw"))
    }

    @Test
    fun `it waits for server-info and is off on servers without account deletion`() {
        assertNull(DeleteAccountRules.available(null))
        assertFalse(DeleteAccountRules.available(setOf("accounts-v2"))!!)
        assertTrue(DeleteAccountRules.available(setOf(ServerFeatures.ACCOUNT_DELETE))!!)
    }

    @Test
    fun `server refusals become problems that say nothing was deleted`() {
        val details = Json.parseToJsonElement("""{"workspaces":[{"id":"o1","name":"Acme","members":3},{"id":"o2","name":"Beta","members":1}]}""")
        assertEquals(
            DeleteAccountRules.Problem.OwnsWorkspaces(listOf("Acme", "Beta")),
            DeleteAccountRules.problem(ApiException(409, "owns_workspaces", "x", details)),
        )
        assertEquals(DeleteAccountRules.Problem.Mismatch, DeleteAccountRules.problem(ApiException(400, "confirm_mismatch", "x")))
        assertEquals(DeleteAccountRules.Problem.WrongPassword, DeleteAccountRules.problem(ApiException(401, "invalid_credentials", "x")))
        assertEquals(DeleteAccountRules.Problem.Unavailable, DeleteAccountRules.problem(ApiException(404, "not_found", "x")))
        assertEquals(DeleteAccountRules.Problem.Unavailable, DeleteAccountRules.problem(ApiException(0, ApiErrors.UNSUPPORTED, "x")))
        assertEquals(DeleteAccountRules.Problem.Offline, DeleteAccountRules.problem(ApiException(0, "network", "x")))
        assertEquals(DeleteAccountRules.Problem.Other, DeleteAccountRules.problem(IllegalStateException()))
        assertEquals(emptyList<String>(), DeleteAccountRules.workspaceNames(null))
    }
}
