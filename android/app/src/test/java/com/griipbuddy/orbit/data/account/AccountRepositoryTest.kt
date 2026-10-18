package com.griipbuddy.orbit.data.account

import com.griipbuddy.orbit.crypto.Ed25519
import com.griipbuddy.orbit.crypto.LocalKeys
import com.griipbuddy.orbit.crypto.RecoveryCode
import com.griipbuddy.orbit.crypto.b64
import com.griipbuddy.orbit.crypto.unb64
import com.griipbuddy.orbit.data.account.FakeAccountApi.Companion.CODE
import com.griipbuddy.orbit.data.account.FakeAccountApi.Companion.PASSWORD
import com.griipbuddy.orbit.data.account.FakeAccountApi.Companion.URL
import com.griipbuddy.orbit.data.api.ApiException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AccountRepositoryTest {
    private val api = FakeAccountApi()
    private val vault = MemoryVault()
    private val session = FakeMessagingSession()
    private var now = 1_000_000L
    private fun repo() = AccountRepository(api, vault, session, { "Test phone" }, { now })

    private suspend fun expectCode(code: String, block: suspend () -> Unit) {
        try {
            block()
            fail("expected $code")
        } catch (e: ApiException) {
            assertEquals(code, e.code)
        }
    }

    @Test
    fun `a new account signs up, verifies, signs in and gets its identity made on this phone`() = runBlocking {
        val r = repo()
        r.register(URL, "Ada", " Ada@Example.COM ", PASSWORD, null)
        assertEquals(AccountPhase.AwaitingVerification("ada@example.com"), r.phase.value)
        r.verifyEmail("12-34-56", PASSWORD)
        r.login(URL, "ada@example.com", PASSWORD)
        val ready = r.phase.value as AccountPhase.Ready
        assertNotNull(ready.account.identity)
        // The messaging app runs on the account's access token, bound to this phone's new device.
        val s = session.current!!
        assertEquals(vault.record!!.tokens!!.accessToken, s.token)
        assertEquals(vault.record!!.deviceId, s.deviceId)
        assertEquals(ready.account.identity!!.userId, s.userId)
        // The keys made here are this account's identity, and its recovery key is offered once.
        assertEquals(ready.account.identity!!.identityKey, vault.localKeys!!.identityPublic.b64())
        assertEquals(RecoveryCode.encode(vault.localKeys!!.identitySeed.unb64()), r.newRecoveryKey.value)
        assertTrue(r.startChoice.value)
        r.recoveryKeyShown()
        assertNull(r.newRecoveryKey.value)
    }

    @Test
    fun `this phone signs in again with its device key and keeps its keys`() = runBlocking {
        val r = repo()
        api.verifiedAccount("grace@example.com")
        r.login(URL, "grace@example.com", PASSWORD)
        val keys = vault.localKeys
        val device = vault.record!!.deviceId
        r.logout()
        assertEquals(AccountPhase.SignedOut("grace@example.com", null), r.phase.value)
        assertNull(session.current)
        assertEquals(keys, vault.localKeys)
        api.calls.clear()
        r.login(URL, "grace@example.com", PASSWORD)
        assertEquals(listOf("login+device"), api.calls)
        assertEquals(device, session.current!!.deviceId)
        assertTrue(r.phase.value is AccountPhase.Ready)
    }

    @Test
    fun `a removed device signs in without its proof and re-adds itself with new device keys`() = runBlocking {
        val r = repo()
        val acc = api.verifiedAccount("removed@example.com")
        r.login(URL, "removed@example.com", PASSWORD)
        val old = vault.localKeys!!
        acc.revoked += vault.record!!.deviceId!!
        r.logout()
        r.login(URL, "removed@example.com", PASSWORD)
        val now = vault.localKeys!!
        assertEquals("same identity", old.identitySeed, now.identitySeed)
        assertTrue("new device key", old.deviceSignSeed != now.deviceSignSeed)
        assertTrue(r.phase.value is AccountPhase.Ready)
        assertTrue(session.current!!.deviceId !in acc.revoked)
    }

    @Test
    fun `a new phone needs the recovery key of this account, not another`() = runBlocking {
        val first = repo()
        api.verifiedAccount("linus@example.com")
        first.login(URL, "linus@example.com", PASSWORD)
        val seed = vault.localKeys!!.identitySeed.unb64()
        // A fresh install of the same account.
        val phoneVault = MemoryVault()
        val phoneSession = FakeMessagingSession()
        val phone = AccountRepository(api, phoneVault, phoneSession, deviceName = { "Phone 2" })
        phone.login(URL, "linus@example.com", PASSWORD)
        assertTrue(phone.phase.value is AccountPhase.NeedsDeviceSetup)
        assertNull(phoneSession.current)
        expectCode("bad_recovery") { phone.devices.withRecoveryKey("NOT-A-KEY") }
        expectCode("recovery_mismatch") { phone.devices.withRecoveryKey(RecoveryCode.encode(LocalKeys.generate().identitySeed.unb64())) }
        phone.devices.withRecoveryKey(RecoveryCode.encode(seed))
        assertTrue(phone.phase.value is AccountPhase.Ready)
        assertEquals(Ed25519.publicFromSeed(seed).b64(), phoneVault.localKeys!!.identityPublic.b64())
        assertNull("restoring shows no new recovery key", phone.newRecoveryKey.value)
    }

    @Test
    fun `starting fresh replaces the identity with the password and shows the new recovery key`() = runBlocking {
        repo().also { api.verifiedAccount("fresh@example.com"); it.login(URL, "fresh@example.com", PASSWORD) }
        val oldIdentity = vault.localKeys!!.identityPublic.b64()
        val phone = AccountRepository(api, MemoryVault(), FakeMessagingSession(), deviceName = { "Phone 2" })
        phone.login(URL, "fresh@example.com", PASSWORD)
        expectCode("invalid_credentials") { phone.devices.startFresh("wrong password!!") }
        phone.devices.startFresh(PASSWORD)
        val ready = phone.phase.value as AccountPhase.Ready
        assertTrue(ready.account.identity!!.identityKey != oldIdentity)
        assertNotNull(phone.newRecoveryKey.value)
        assertTrue("not a new account", !phone.startChoice.value)
    }

    @Test
    fun `token renewal runs once for callers waiting together and updates the messaging session`() = runBlocking {
        val r = repo()
        api.verifiedAccount("renew@example.com")
        r.login(URL, "renew@example.com", PASSWORD)
        val before = session.current!!.token
        val results = (1..5).map { async { r.refreshTokens() } }.awaitAll()
        assertEquals(List(5) { true }, results)
        assertEquals(1, api.refreshes)
        assertTrue(session.current!!.token != before)
        assertEquals(vault.record!!.tokens!!.accessToken, session.current!!.token)
        // Later, an expired token is renewed again.
        now += 60_000
        assertTrue(r.refreshTokens())
        assertEquals(2, api.refreshes)
    }

    @Test
    fun `a session the server ended signs this phone out but keeps its keys and messages`() = runBlocking {
        val r = repo()
        api.verifiedAccount("ended@example.com")
        r.login(URL, "ended@example.com", PASSWORD)
        val keys = vault.localKeys
        api.refreshFailure = ApiException(401, "session_revoked", "You were signed out on this device.")
        assertTrue(!r.refreshTokens())
        assertEquals(AccountPhase.SignedOut("ended@example.com", "You were signed out on this device."), r.phase.value)
        assertNull(vault.record!!.tokens)
        assertEquals(keys, vault.localKeys)
        assertEquals(1, session.signOuts)
    }

    @Test
    fun `a network failure during renewal changes nothing`() = runBlocking {
        val r = repo()
        api.verifiedAccount("offline@example.com")
        r.login(URL, "offline@example.com", PASSWORD)
        api.refreshFailure = ApiException(0, "network", "Can't reach the server")
        assertTrue(!r.refreshTokens())
        assertTrue(r.phase.value is AccountPhase.Ready)
        assertNotNull(vault.record!!.tokens)
        assertEquals(0, session.signOuts)
    }

    @Test
    fun `another account can't sign in on a phone holding one account's messages`() = runBlocking {
        val r = repo()
        api.verifiedAccount("first@example.com")
        api.verifiedAccount("second@example.com")
        r.login(URL, "first@example.com", PASSWORD)
        r.logout()
        expectCode(OTHER_ACCOUNT_HERE) { r.login(URL, "second@example.com", PASSWORD) }
    }

    @Test
    fun `another account can't sign up or move onto a phone holding one account's keys`() = runBlocking {
        val r = repo()
        api.verifiedAccount("owner@example.com")
        r.login(URL, "owner@example.com", PASSWORD)
        r.logout()
        expectCode(OTHER_ACCOUNT_HERE) { r.register(URL, "B", "b@example.com", PASSWORD, null) }
        expectCode(OTHER_ACCOUNT_HERE) { r.awaitVerification(URL, "c@example.com", null) }
        // Nothing was replaced: the owner signs back in with this phone's device.
        assertEquals("owner@example.com", vault.record!!.email)
        r.login(URL, "owner@example.com", PASSWORD)
        assertTrue(r.phase.value is AccountPhase.Ready)
    }

    @Test
    fun `keys made for an account that never got them are reused only by that account`() = runBlocking {
        api.verifiedAccount("pending@example.com")
        val r = repo()
        // The identity's keys were saved, but the server never confirmed them (the app stopped): a retry reuses them.
        api.failNextEnroll = true
        expectCode("network") { r.login(URL, "pending@example.com", PASSWORD) }
        val firstTry = vault.localKeys!!
        assertEquals(vault.record!!.account!!.id, vault.record!!.pendingIdentityFor)
        r.devices.createIdentity()
        assertEquals(firstTry, vault.localKeys)
        assertTrue(r.phase.value is AccountPhase.Ready)
        assertNull(vault.record!!.pendingIdentityFor)
    }

    @Test
    fun `an unfinished account can be forgotten to start over`() = runBlocking {
        val r = repo()
        r.register(URL, "Typo", "tpyo@example.com", PASSWORD, null)
        r.abandonPending()
        assertNull(vault.record)
        assertEquals(AccountPhase.None, r.phase.value)
    }

    @Test
    fun `an unverified address is refused at sign-in by the server`() = runBlocking {
        val r = repo()
        r.register(URL, "Late", "late@example.com", PASSWORD, null)
        r.cancelVerification()
        assertEquals(AccountPhase.None, r.phase.value)
        expectCode("email_unverified") { r.login(URL, "late@example.com", PASSWORD) }
        r.awaitVerification(URL, "late@example.com", null)
        r.verifyEmail(CODE, PASSWORD)
        r.login(URL, "late@example.com", PASSWORD)
        assertTrue(r.phase.value is AccountPhase.Ready)
    }

    @Test
    fun `the phase is restored from what this phone kept`() = runBlocking {
        val r = repo()
        api.verifiedAccount("restart@example.com")
        r.login(URL, "restart@example.com", PASSWORD)
        assertTrue(AccountRepository(api, vault, session, deviceName = { "x" }).phase.value is AccountPhase.Ready)
        r.logout()
        assertEquals(AccountPhase.SignedOut("restart@example.com", null), AccountRepository(api, vault, session, deviceName = { "x" }).phase.value)
        assertEquals(AccountPhase.None, AccountRepository(api, MemoryVault(), FakeMessagingSession(), deviceName = { "x" }).phase.value)
    }
}
