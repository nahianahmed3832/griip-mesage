package com.meldiq.android.crypto

import com.meldiq.android.data.account.KeyBackupDto
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** Keys backed up under the password (docs/KEY_BACKUP.md). The vector is shared with web `test/keyBackup.test.ts`. */
class KeyBackupCryptoTest {
    private val seed = ByteArray(32) { (it + 1).toByte() }
    private val password = "correct horse battery"

    /** Made with Node's crypto (PBKDF2-HMAC-SHA256, AES-256-GCM): every client must open it. */
    private val vector = KeyBackupDto(
        version = 1, iterations = 600_000, salt = "AAECAwQFBgcICQoLDA0ODw==", nonce = "ZGVmZ2hpamtsbW5v",
        ciphertext = "GPYZyJjo3d6pvIKo7LqJKcMCF8HSIvb4hSNxe9LLcnZHpITups5EOC0K9IfZbIS5",
        identityKey = "ebVWLo/mVPlAeLES6KmLp5AfhTrmlb7X4OORC60ElmQ=",
    )

    @Test
    fun `derives the same key as the other clients`() {
        val key = KeyBackupCrypto.deriveKey(password, ByteArray(16) { it.toByte() }, 600_000)
        assertEquals("bb06c8c0b1dd5bfd4e40f4e297a2d0e64da7ef94b4b8ec20989021c8b41536ad", key.joinToString("") { "%02x".format(it) })
    }

    @Test
    fun `opens the shared vector`() {
        assertArrayEquals(seed, KeyBackupCrypto.open(password, "acc_vector", vector))
    }

    @Test
    fun `seals and opens with fresh salt and nonce, naming the identity`() {
        val a = KeyBackupCrypto.seal(password, "acc_1", seed, iterations = 1000)
        val b = KeyBackupCrypto.seal(password, "acc_1", seed, iterations = 1000)
        assertEquals(Ed25519.publicFromSeed(seed).b64(), a.identityKey)
        assertNotEquals(a.salt, b.salt)
        assertNotEquals(a.nonce, b.nonce)
        assertEquals(48, a.ciphertext.unb64().size)
        assertArrayEquals(seed, KeyBackupCrypto.open(password, "acc_1", a))
    }

    @Test(expected = KeyBackupCrypto.WrongPassword::class)
    fun `a wrong password fails`() {
        KeyBackupCrypto.open("not the password", "acc_vector", vector)
    }

    @Test(expected = KeyBackupCrypto.WrongPassword::class)
    fun `another account's backup fails`() {
        KeyBackupCrypto.open(password, "acc_other", vector)
    }

    @Test(expected = KeyBackupCrypto.WrongPassword::class)
    fun `a backup claiming another identity fails`() {
        val other = Ed25519.publicFromSeed(ByteArray(32) { 9 }).b64()
        KeyBackupCrypto.open(password, "acc_vector", vector.copy(identityKey = other))
    }

    @Test
    fun `non-ASCII passwords are their UTF-8 bytes`() {
        val b = KeyBackupCrypto.seal("পাসওয়ার্ড 密码", "acc_1", seed, iterations = 1000)
        assertArrayEquals(seed, KeyBackupCrypto.open("পাসওয়ার্ড 密码", "acc_1", b))
    }
}
