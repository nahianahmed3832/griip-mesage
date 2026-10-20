package com.meldiq.android.crypto

import com.meldiq.android.data.account.KeyBackupDto
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.PKCS5S2ParametersGenerator
import org.bouncycastle.crypto.params.KeyParameter
import javax.crypto.AEADBadTagException

/**
 * The account's identity seed sealed under its password (docs/KEY_BACKUP.md), the same as web `crypto/keyBackup.ts`;
 * unit-tested in `KeyBackupCryptoTest` with a vector shared with the web and server tests.
 *
 * Key: PBKDF2-HMAC-SHA256 of the password's UTF-8 bytes with a random 16-byte salt, [ITERATIONS] rounds, 32 bytes.
 * Sealed: AES-256-GCM with a random 12-byte nonce over the 32-byte seed; associated data
 * "meldiq-key-backup-v1\n<account id>\n<identity key>", so a backup can't be passed off as another account's.
 */
object KeyBackupCrypto {
    const val VERSION = 1
    const val ITERATIONS = 600_000
    private const val SALT_BYTES = 16
    private const val NONCE_BYTES = 12
    private const val SEED_BYTES = 32
    private const val KEY_BITS = 256

    /** The password didn't open the backup, or the backup isn't this account's keys. */
    class WrongPassword : Exception("That password doesn't open the key backup.")

    fun deriveKey(password: String, salt: ByteArray, iterations: Int): ByteArray {
        val gen = PKCS5S2ParametersGenerator(SHA256Digest())
        gen.init(password.toByteArray(Charsets.UTF_8), salt, iterations)
        return (gen.generateDerivedParameters(KEY_BITS) as KeyParameter).key
    }

    private fun aad(accountId: String, identityKey: String) = "meldiq-key-backup-v1\n$accountId\n$identityKey".toByteArray(Charsets.UTF_8)

    fun seal(password: String, accountId: String, seed: ByteArray, iterations: Int = ITERATIONS): KeyBackupDto {
        require(seed.size == SEED_BYTES) { "an identity seed is 32 bytes" }
        val identityKey = Ed25519.publicFromSeed(seed).b64()
        val salt = randomBytes(SALT_BYTES)
        val nonce = randomBytes(NONCE_BYTES)
        val sealed = AesGcm.encrypt(deriveKey(password, salt, iterations), nonce, seed, aad(accountId, identityKey))
        return KeyBackupDto(VERSION, iterations, salt.b64(), nonce.b64(), sealed.b64(), identityKey)
    }

    /** The seed in [b], checked against the identity key it names; [WrongPassword] when it doesn't open or match. */
    fun open(password: String, accountId: String, b: KeyBackupDto): ByteArray {
        if (b.version != VERSION) throw IllegalArgumentException("This key backup was made by a newer Meldiq. Update the app.")
        val seed = try {
            AesGcm.decrypt(deriveKey(password, b.salt.unb64(), b.iterations), b.nonce.unb64(), b.ciphertext.unb64(), aad(accountId, b.identityKey))
        } catch (_: AEADBadTagException) {
            throw WrongPassword()
        }
        if (seed.size != SEED_BYTES || Ed25519.publicFromSeed(seed).b64() != b.identityKey) throw WrongPassword()
        return seed
    }
}
