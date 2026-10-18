package com.griipbuddy.orbit.data.account

import com.griipbuddy.orbit.data.api.ApiClient
import com.griipbuddy.orbit.data.api.ApiException
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

/**
 * The account API client against the server's real request and response shapes (server/src/accounts/routes.ts and
 * schemas.ts), through the real ApiClient: paths, methods, field names, the bearer token, and the 401 → renew → retry.
 */
class AccountApiContractTest {
    private val server = MockWebServer().apply { start() }
    private val url get() = server.url("/").toString()
    private var token = "access-1"
    private var renewals = 0
    private val client = ApiClient({ url }, { token }, { renewals++; token = "access-2"; true })
    private val api = HttpAccountApi(client)

    @After fun stop() = server.shutdown()

    private fun json(code: Int, body: String) = MockResponse().setResponseCode(code).setHeader("content-type", "application/json").setBody(body)

    private val sessionJson = """{"accessToken":"a","accessTokenExpiresAt":1,"refreshToken":"r","refreshTokenExpiresAt":2,
        "session":{"id":"s_1","deviceId":null,"createdAt":3},
        "account":{"id":"acc_1","email":"ada@example.com","name":"Ada","createdAt":4,"legacyLinkedAt":null,"selectedWorkspaceId":null,"identity":null}}"""

    @Test
    fun `sign-up sends name, email and password to the v2 route without a token, and leaves out absent fields`() = runBlocking {
        server.enqueue(json(202, """{"status":"verification_sent","email":"ada@example.com"}"""))
        val res = api.register(url, RegisterReq("Ada", "ada@example.com", "correct horse battery"))
        assertEquals("verification_sent", res.status)
        val sent = server.takeRequest()
        assertEquals("POST", sent.method)
        assertEquals("/v2/auth/register", sent.path)
        assertNull(sent.getHeader("Authorization"))
        assertEquals("""{"name":"Ada","email":"ada@example.com","password":"correct horse battery"}""", sent.body.readUtf8())
    }

    @Test
    fun `sign-in reads the server's session and account`() = runBlocking {
        server.enqueue(json(200, sessionJson))
        val res = api.login(url, AccountLoginReq("ada@example.com", "pw", "Phone", DeviceProofDto("d_1", "nonce-nonce-nonce-1", "sig")))
        assertEquals("s_1", res.session.id)
        assertNull(res.session.deviceId)
        assertNull(res.account.identity)
        val sent = server.takeRequest()
        assertEquals("/v2/auth/login", sent.path)
        assertEquals(
            """{"email":"ada@example.com","password":"pw","deviceName":"Phone","device":{"deviceId":"d_1","nonce":"nonce-nonce-nonce-1","signature":"sig"}}""",
            sent.body.readUtf8(),
        )
    }

    @Test
    fun `server refusals keep their code and message`() = runBlocking {
        server.enqueue(json(403, """{"error":{"code":"email_unverified","message":"Verify your email first: enter the code we sent you.","details":{"email":"ada@example.com"}}}"""))
        try {
            api.login(url, AccountLoginReq("ada@example.com", "pw"))
            fail("expected a refusal")
        } catch (e: ApiException) {
            assertEquals(403, e.status)
            assertEquals("email_unverified", e.code)
            assertEquals("Verify your email first: enter the code we sent you.", e.message)
        }
    }

    @Test
    fun `account calls carry the access token and renew it once on a 401`() = runBlocking {
        server.enqueue(json(401, """{"error":{"code":"unauthorized","message":"Authentication required"}}"""))
        server.enqueue(json(200, """{"workspaces":[{"id":"personal","kind":"personal","name":"Personal","role":"OWNER","slug":null},{"id":"o_1","kind":"organization","name":"Acme","role":"ADMIN","slug":"acme"}],"selectedWorkspaceId":"o_1"}"""))
        val res = api.workspaces()
        assertEquals(listOf("personal", "o_1"), res.workspaces.map { it.id })
        assertEquals("o_1", res.selectedWorkspaceId)
        assertEquals(1, renewals)
        assertEquals("Bearer access-1", server.takeRequest().getHeader("Authorization"))
        assertEquals("Bearer access-2", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `device setup and workspace choice use the documented routes`() = runBlocking {
        server.enqueue(json(200, """{"userId":"u_1","deviceId":"d_1","username":"ada1234","result":"identity"}"""))
        server.enqueue(json(200, """{"selectedWorkspaceId":"personal"}"""))
        val enrolled = api.enroll(EnrollDeviceReq(identityKey = "id", device = NewDeviceDto("Phone", "sk", "ek", "cert"), nonce = "n", signature = "s"))
        assertEquals("identity", enrolled.result)
        assertEquals("personal", api.selectWorkspace("personal"))
        val enroll = server.takeRequest()
        assertEquals("/v2/account/devices", enroll.path)
        assertEquals(
            """{"identityKey":"id","device":{"name":"Phone","signingKey":"sk","encryptionKey":"ek","certificate":"cert"},"nonce":"n","signature":"s"}""",
            enroll.body.readUtf8(),
        )
        val prefs = server.takeRequest()
        assertEquals("PUT", prefs.method)
        assertEquals("/v2/account/preferences", prefs.path)
        assertEquals("""{"selectedWorkspaceId":"personal"}""", prefs.body.readUtf8())
    }

    @Test
    fun `the legacy move signs nothing secret into the request`() = runBlocking {
        server.enqueue(json(200, """{"ticket":"t","expiresAt":5,"legacyUser":{"username":"oldname","displayName":"Old"}}"""))
        api.legacyVerify(url, LegacyVerifyReq("oldname", "nonce", "signature"))
        val sent = server.takeRequest()
        assertEquals("/v2/migration/legacy/verify", sent.path)
        assertEquals("""{"username":"oldname","nonce":"nonce","signature":"signature"}""", sent.body.readUtf8())
    }
}
