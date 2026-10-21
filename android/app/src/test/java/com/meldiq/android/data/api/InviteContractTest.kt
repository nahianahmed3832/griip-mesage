package com.meldiq.android.data.api

import com.meldiq.android.ui.screens.onboarding.InviteOutcome
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Physical-device defect (2026-10-16): a valid invite code showed "Unexpected server response". The deployed server's
 * preview sends `org` without `slug`; the app's OrgDto required it, so decoding failed. These are the exact response
 * shapes of the deployed (pre-fix) and the fixed server, through the real ApiClient.
 */
class InviteContractTest {
    private val server = MockWebServer().apply { start() }
    private val api = ApiClient({ server.url("/").toString() }, { "token" }, { false })

    @After fun stop() = server.shutdown()

    private fun json(code: Int, body: String) = MockResponse().setResponseCode(code).setHeader("content-type", "application/json").setBody(body)

    @Test
    fun previewFromTheDeployedServerWithoutSlugDecodes() = runBlocking {
        server.enqueue(json(200, """{"org":{"id":"o_1","name":"GriipBuddy","description":"test"},"role":"MEMBER","branch":null,"team":null,"alreadyMember":false}"""))
        val p = api.post<CodeReq, InvitePreviewRes>("/v1/invites/preview", CodeReq("XFG6-3KAJ-EQ3J"))
        assertEquals("GriipBuddy", p.org.name)
        assertEquals("", p.org.slug)
        assertEquals("MEMBER", p.role)
        val sent = server.takeRequest()
        assertEquals("/v1/invites/preview", sent.path)
        assertEquals("""{"code":"XFG6-3KAJ-EQ3J"}""", sent.body.readUtf8())
    }

    @Test
    fun previewFromTheFixedServerKeepsTheSlug() = runBlocking {
        server.enqueue(json(200, """{"org":{"id":"o_1","name":"GriipBuddy","slug":"griipbuddy","description":null},"role":"ADMIN","branch":null,"team":null,"grantRole":{"id":"r_1","name":"Moderator","color":null},"alreadyMember":true}"""))
        val p = api.post<CodeReq, InvitePreviewRes>("/v1/invites/preview", CodeReq("XFG6-3KAJ-EQ3J"))
        assertEquals("griipbuddy", p.org.slug)
        assertEquals("Moderator", p.grantRole?.name)
        assertTrue(p.alreadyMember)
    }

    @Test
    fun acceptFromOldAndNewServersDecodes() = runBlocking {
        server.enqueue(json(200, """{"org":{"id":"o_1","name":"GriipBuddy","slug":"griipbuddy","myRole":"MEMBER","memberCount":2,"createdAt":1}}"""))
        val old = api.post<CodeReq, AcceptInviteRes>("/v1/invites/accept", CodeReq("XFG6-3KAJ-EQ3J"))
        assertEquals("o_1", old.org.id)
        assertFalse(old.alreadyMember)
        server.enqueue(json(200, """{"org":{"id":"o_1","name":"GriipBuddy","slug":"griipbuddy","myRole":"MEMBER","memberCount":2,"createdAt":1},"alreadyMember":true}"""))
        assertTrue(api.post<CodeReq, AcceptInviteRes>("/v1/invites/accept", CodeReq("XFG6-3KAJ-EQ3J")).alreadyMember)
    }

    @Test
    fun eachRefusalKeepsTheServersReason() = runBlocking {
        val cases = mapOf(
            "invite_expired" to "This invite has expired. Ask for a new one.",
            "invite_revoked" to "This invite was cancelled by the person who created it. Ask them for a new one.",
            "invite_used_up" to "This invite has already been used as many times as it allows. Ask for a new one.",
            "invite_unavailable" to "This invite can no longer be used: whoever created it can't invite people there any more. Ask for a new one.",
            "invite_not_found" to "This invite code doesn't exist. Check the code with the person who sent it.",
            // The deployed server's single message for every refusal.
            "not_found" to "This invite is invalid, expired or already used",
        )
        for ((code, message) in cases) {
            server.enqueue(json(404, """{"error":{"code":"$code","message":"$message"}}"""))
            val e = failure { api.post<CodeReq, AcceptInviteRes>("/v1/invites/accept", CodeReq("ABCD-EFGH-JKMN")) }
            assertEquals(code, e.code)
            assertEquals(message, InviteOutcome.message(e))
        }
    }

    @Test
    fun networkRateLimitAndUnreadableRepliesAreToldApart() = runBlocking {
        assertEquals("Can't reach the server. Check your connection and try again.", InviteOutcome.message(ApiException(0, "network", "x")))
        server.enqueue(json(429, """{"error":{"code":"rate_limited","message":"Too many requests"}}"""))
        assertEquals("Too many attempts. Wait a minute, then try the code again.", InviteOutcome.message(failure { api.post<CodeReq, InvitePreviewRes>("/v1/invites/preview", CodeReq("ABCD-EFGH-JKMN")) }))
        server.enqueue(json(200, """{"unexpected":true}"""))
        val e = failure { api.post<CodeReq, InvitePreviewRes>("/v1/invites/preview", CodeReq("ABCD-EFGH-JKMN")) }
        assertEquals("decode", e.code)
        assertTrue(InviteOutcome.message(e).contains("doesn't understand"))
    }

    @Test
    fun aRefusedDuplicateWorkspacePointsAtTheExistingOne() = runBlocking {
        server.enqueue(json(409, """{"error":{"code":"org_name_taken","message":"You already own a workspace called \"GriipBuddy\". Open it, or choose another name.","details":{"orgId":"o_9"}}}"""))
        val e = failure { api.post<CreateOrgReq, OrgRes>("/v1/orgs", CreateOrgReq("GriipBuddy", null, "req-1234567890")) }
        assertEquals("o_9", InviteOutcome.existingOrg(e))
        assertEquals("""{"name":"GriipBuddy","requestId":"req-1234567890"}""", server.takeRequest().body.readUtf8())
        assertNull(InviteOutcome.existingOrg(ApiException(409, "conflict", "x", JsonObject(mapOf("orgId" to JsonPrimitive("o_1"))))))
    }

    private suspend fun failure(block: suspend () -> Unit): ApiException {
        try {
            block()
        } catch (e: ApiException) {
            return e
        }
        fail("expected an ApiException")
        throw IllegalStateException()
    }
}
