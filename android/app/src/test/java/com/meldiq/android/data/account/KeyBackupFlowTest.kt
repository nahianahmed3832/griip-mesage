package com.meldiq.android.data.account

import com.meldiq.android.crypto.KeyBackupCrypto
import com.meldiq.android.crypto.RecoveryCode
import com.meldiq.android.crypto.b64
import com.meldiq.android.crypto.unb64
import com.meldiq.android.data.account.FakeAccountApi.Companion.PASSWORD
import com.meldiq.android.data.account.FakeAccountApi.Companion.URL
import com.meldiq.android.data.account.FakeKeyBackupApi.Companion.UNLOCK_CODE
import com.meldiq.android.data.api.ApiException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** A new phone gets the keys with the password and an emailed code (docs/KEY_BACKUP.md). */
class KeyBackupFlowTest {
    private val api = FakeAccountApi()
    private val backups = FakeKeyBackupApi(api)
    private val email = "ada@example.com"

    private fun phone(vault: MemoryVault = MemoryVault(), session: FakeMessagingSession = FakeMessagingSession()) =
        AccountRepository(api, vault, session, deviceName = { "Phone" }, keyBackups = backups)

    private suspend fun expectCode(code: String, block: suspend () -> Unit) {
        try {
            block()
            fail("expected $code")
        } catch (e: ApiException) {
            assertEquals(code, e.code)
        }
    }

    /** The account's first phone, signed in: its identity is made and backed up under the password. */
    private suspend fun firstPhone(vault: MemoryVault = MemoryVault()): AccountRepository {
        api.verifiedAccount(email)
        return phone(vault).also { it.login(URL, email, PASSWORD) }
    }

    private fun account() = api.accounts.getValue(email)

    @Test
    fun `the first phone backs its keys up under the password`() = runBlocking {
        val vault = MemoryVault()
        firstPhone(vault)
        val backup = checkNotNull(account().keyBackup)
        assertEquals(vault.localKeys!!.identityPublic.b64(), backup.identityKey)
        assertArrayEquals(vault.localKeys!!.identitySeed.unb64(), KeyBackupCrypto.open(PASSWORD, account().id, backup))
    }

    @Test
    fun `a new phone unlocks the keys with the emailed code and the sign-in password`() = runBlocking {
        val firstVault = MemoryVault()
        firstPhone(firstVault)
        val vault = MemoryVault()
        val p = phone(vault)
        p.login(URL, email, PASSWORD)
        assertTrue(p.phase.value is AccountPhase.NeedsDeviceSetup)
        assertEquals(true, p.devices.backupAvailable())
        assertTrue(p.devices.hasPassword)
        assertEquals("a***e@example.com", p.devices.startEmailUnlock().email)
        expectCode("invalid_code") { p.devices.withEmailCode("000000", null) }
        p.devices.startEmailUnlock()
        p.devices.withEmailCode(UNLOCK_CODE, null)
        assertTrue(p.phase.value is AccountPhase.Ready)
        assertEquals(firstVault.localKeys!!.identityPublic.b64(), vault.localKeys!!.identityPublic.b64())
        assertNull("no new recovery key: it's the same identity", p.newRecoveryKey.value)
    }

    @Test
    fun `the unlock asks for the password when the app no longer has it`() = runBlocking {
        firstPhone()
        val p = phone()
        p.login(URL, email, PASSWORD)
        p.devices.rememberPassword(null)
        p.devices.startEmailUnlock()
        expectCode("password_required") { p.devices.withEmailCode(UNLOCK_CODE, null) }
        p.devices.withEmailCode(UNLOCK_CODE, PASSWORD)
        assertTrue(p.phase.value is AccountPhase.Ready)
    }

    @Test
    fun `a reset password leaves no usable backup, and the recovery key sets the phone up and backs up again`() = runBlocking {
        val firstVault = MemoryVault()
        firstPhone(firstVault)
        val seed = firstVault.localKeys!!.identitySeed.unb64()
        account().password = "a reset passphrase"
        val p = phone()
        p.login(URL, email, "a reset passphrase")
        assertEquals(false, p.devices.backupAvailable())
        expectCode("no_key_backup") { p.devices.startEmailUnlock() }
        p.devices.withRecoveryKey(RecoveryCode.encode(seed))
        assertTrue(p.phase.value is AccountPhase.Ready)
        assertArrayEquals(seed, KeyBackupCrypto.open("a reset passphrase", account().id, account().keyBackup!!))
    }

    @Test
    fun `changing the password seals the keys again under the new one`() = runBlocking {
        val first = firstPhone()
        first.changePassword(PASSWORD, "a brand new passphrase")
        assertTrue(backups.status().exists)
        assertNotNull(KeyBackupCrypto.open("a brand new passphrase", account().id, account().keyBackup!!))
    }

    @Test
    fun `settings turns the backup on with the password`() = runBlocking {
        val first = firstPhone()
        backups.remove()
        assertFalse(backups.status().exists)
        expectCode("invalid_credentials") { first.backUpKeys("not my password") }
        first.backUpKeys(PASSWORD)
        assertTrue(backups.status().exists)
    }
}
