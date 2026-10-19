package com.meldiq.android.crypto

import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The key that seals reminder content kept on the server (docs/REMINDERS.md), derived like the history keys from the
 * account identity seed, which every device of the account holds and servers never have. The server stores and fires
 * reminders by time; what they say (title, note, the message they point at) only the person's devices can read.
 *
 * AES-256-GCM with a random 96-bit nonce; the associated data binds the content to this account and reminder id, so
 * the server can't swap content between reminders or accounts. Format: 0x01 ‖ nonce (12) ‖ ciphertext+tag, base64url.
 * Unit-tested in `ReminderCryptoTest`.
 */
class ReminderKeys(identitySeed: ByteArray, private val userId: String) {
    private val enc = Hkdf.derive(identitySeed, SALT, "enc|$userId".toByteArray(), 32)

    fun seal(reminderId: String, plaintext: ByteArray): String {
        val nonce = randomBytes(12)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(enc, "AES"), GCMParameterSpec(128, nonce))
        c.updateAAD(aad(reminderId))
        return (byteArrayOf(VERSION) + nonce + c.doFinal(plaintext)).b64url()
    }

    /** The plaintext, or an exception if the content isn't this account's, was changed, or belongs to another reminder. */
    fun open(reminderId: String, sealed: String): ByteArray {
        val bytes = java.util.Base64.getUrlDecoder().decode(sealed)
        require(bytes.size > 1 + 12 + 16 && bytes[0] == VERSION) { "not a sealed reminder" }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(enc, "AES"), GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
        c.updateAAD(aad(reminderId))
        return c.doFinal(bytes, 13, bytes.size - 13)
    }

    private fun aad(reminderId: String) = "meldiq-reminder-v1|$userId|$reminderId".toByteArray()

    companion object {
        private const val VERSION: Byte = 1
        private val SALT = "meldiq-reminders-v1".toByteArray()
    }
}
