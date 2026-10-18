package com.griipbuddy.orbit.data.account

import com.griipbuddy.orbit.crypto.LocalKeys
import com.griipbuddy.orbit.crypto.RecoveryCode
import com.griipbuddy.orbit.crypto.SessionInfo
import com.griipbuddy.orbit.crypto.b64
import com.griipbuddy.orbit.crypto.unb64
import com.griipbuddy.orbit.data.account.FakeAccountApi.Companion.CODE
import com.griipbuddy.orbit.data.account.FakeAccountApi.Companion.PASSWORD
import com.griipbuddy.orbit.data.account.FakeAccountApi.Companion.URL
import com.griipbuddy.orbit.data.api.ApiException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LegacyMigrationTest {
    private val api = FakeAccountApi()
    private val legacyKeys = LocalKeys.generate()
    private val recoveryKey = RecoveryCode.encode(legacyKeys.identitySeed.unb64())

    init {
        api.legacyUsers["oldname"] = legacyKeys.identityPublic.b64() to "u_legacy"
    }

    /** A phone signed in to the legacy account the old way. */
    private fun recognised(): Triple<MemoryVault, FakeMessagingSession, AccountRepository> {
        val vault = MemoryVault(localKeys = legacyKeys)
        val session = FakeMessagingSession(SessionInfo(URL, "u_legacy", "d_legacy", "oldname", "Old Name", "v1-token", 0))
        return Triple(vault, session, AccountRepository(api, vault, session, deviceName = { "Phone" }))
    }

    private suspend fun expectCode(code: String, block: suspend () -> Unit) {
        try {
            block()
            fail("expected $code")
        } catch (e: ApiException) {
            assertEquals(code, e.code)
        }
    }

    @Test
    fun `a recognised installation catches a recovery key of another account before any request`() = runBlocking {
        val (vault, session, accounts) = recognised()
        val migration = LegacyMigration(api, vault, session, accounts)
        assertTrue(migration.recognizedInstall)
        assertEquals("oldname", migration.recognizedUsername)
        expectCode("recovery_mismatch") { migration.verify(URL, "oldname", RecoveryCode.encode(LocalKeys.generate().identitySeed.unb64())) }
        expectCode("bad_recovery") { migration.verify(URL, "oldname", "AAAA-BBBB") }
        assertEquals(emptyList<String>(), api.calls)
    }

    @Test
    fun `a recognised installation moves its account and keeps its own device`() = runBlocking {
        val (vault, session, accounts) = recognised()
        val migration = LegacyMigration(api, vault, session, accounts)
        val ticket = migration.verify(URL, "@OldName", recoveryKey)
        assertEquals("oldname", ticket.username)
        assertFalse("the key never appears in the ticket's text", ticket.toString().contains(recoveryKey))
        migration.complete(URL, ticket, "New Name", "moved@example.com", PASSWORD)
        assertEquals(AccountPhase.AwaitingVerification("moved@example.com"), accounts.phase.value)
        assertEquals("d_legacy", vault.record!!.deviceId)
        // The fake server knows the legacy device's key, as the real one does.
        api.accounts.getValue("moved@example.com").devices["d_legacy"] = legacyKeys.deviceSignPublic.b64()
        accounts.verifyEmail(CODE, PASSWORD)
        migration.finish(URL, ticket, "moved@example.com", PASSWORD)
        assertTrue(accounts.phase.value is AccountPhase.Ready)
        assertEquals("d_legacy", session.current!!.deviceId)
        assertEquals("u_legacy", session.current!!.userId)
        assertEquals(vault.record!!.tokens!!.accessToken, session.current!!.token)
        assertEquals("the legacy keys are kept", legacyKeys, vault.localKeys)
        assertTrue("signed in with the device's own key", "login+device" in api.calls)
    }

    @Test
    fun `a fresh installation moves the account and sets itself up from the recovery key`() = runBlocking {
        val vault = MemoryVault()
        val session = FakeMessagingSession()
        val accounts = AccountRepository(api, vault, session, deviceName = { "New phone" })
        val migration = LegacyMigration(api, vault, session, accounts)
        assertFalse(migration.recognizedInstall)
        val ticket = migration.verify(URL, "oldname", recoveryKey)
        migration.complete(URL, ticket, "New Name", "fresh@example.com", PASSWORD)
        accounts.verifyEmail(CODE, PASSWORD)
        migration.finish(URL, ticket, "fresh@example.com", PASSWORD)
        assertTrue(accounts.phase.value is AccountPhase.Ready)
        assertEquals("u_legacy", session.current!!.userId)
        assertEquals(legacyKeys.identityPublic.b64(), vault.localKeys!!.identityPublic.b64())
        assertTrue("a new device key", legacyKeys.deviceSignSeed != vault.localKeys!!.deviceSignSeed)
    }

    @Test
    fun `a recognised installation can't sign in to someone else's account`() = runBlocking {
        val (vault, session, accounts) = recognised()
        api.verifiedAccount("stranger@example.com")
        expectCode(OTHER_ACCOUNT_HERE) { accounts.login(URL, "stranger@example.com", PASSWORD) }
        assertEquals("the legacy keys are untouched", legacyKeys, vault.localKeys)
        assertEquals(null, vault.record)
        assertEquals("v1-token", session.current!!.token)
    }

    @Test
    fun `a wrong recovery key on a fresh installation is refused by the server and links nothing`() = runBlocking {
        val accounts = AccountRepository(api, MemoryVault(), FakeMessagingSession(), deviceName = { "x" })
        val migration = LegacyMigration(api, MemoryVault(), FakeMessagingSession(), accounts)
        expectCode("legacy_verification_failed") { migration.verify(URL, "oldname", RecoveryCode.encode(LocalKeys.generate().identitySeed.unb64())) }
        expectCode("legacy_verification_failed") { migration.verify(URL, "nobody", recoveryKey) }
        assertTrue(api.accounts.isEmpty())
    }
}
