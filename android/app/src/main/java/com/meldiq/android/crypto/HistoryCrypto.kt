package com.meldiq.android.crypto

import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Keys for the encrypted message history kept on the account's server (docs/MESSAGE_HISTORY.md), derived from the
 * account identity seed, which every device of the account holds and the recovery key restores, and which never
 * reaches a server. So any of the account's devices can read the history, and nobody else, the server included, can.
 *
 * - Items: AES-256-GCM with a random 96-bit nonce; the associated data binds the item to this account and its tag, so
 *   the server can't swap items between tags or accounts. Format: 0x01 ‖ nonce (12) ‖ ciphertext+tag.
 * - Tags: HMAC-SHA256 of "m|<message id>" or "c|<conversation id>" under a separate derived key, so the server can
 *   group and replace items without learning message or conversation ids.
 *
 * Unit-tested in `HistoryCryptoTest`.
 */
class HistoryKeys(identitySeed: ByteArray, private val userId: String) {
    private val enc = Hkdf.derive(identitySeed, SALT, "enc|$userId".toByteArray(), 32)
    private val tagKey = Hkdf.derive(identitySeed, SALT, "tag|$userId".toByteArray(), 32)

    fun messageTag(messageId: String): String = tag("m|$messageId")
    fun conversationTag(conversationId: String): String = tag("c|$conversationId")

    private fun tag(s: String): String =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(tagKey, "HmacSHA256")) }.doFinal(s.toByteArray()).b64url()

    fun seal(tag: String, plaintext: ByteArray): ByteArray {
        val nonce = randomBytes(12)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(enc, "AES"), GCMParameterSpec(128, nonce))
        c.updateAAD(aad(tag))
        return byteArrayOf(VERSION) + nonce + c.doFinal(plaintext)
    }

    /** The plaintext, or an exception if the item isn't this account's, was changed, or was moved to another tag. */
    fun open(tag: String, sealed: ByteArray): ByteArray {
        require(sealed.size > 1 + 12 + 16 && sealed[0] == VERSION) { "not a history item" }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(enc, "AES"), GCMParameterSpec(128, sealed.copyOfRange(1, 13)))
        c.updateAAD(aad(tag))
        return c.doFinal(sealed, 13, sealed.size - 13)
    }

    private fun aad(tag: String) = "orbit-history-v1|$userId|$tag".toByteArray()

    companion object {
        private const val VERSION: Byte = 1
        private val SALT = "orbit-history-v1".toByteArray()
    }
}

/** RFC 5869 HKDF with SHA-256. */
object Hkdf {
    fun derive(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val prk = hmac(salt, ikm)
        val out = java.io.ByteArrayOutputStream()
        var t = ByteArray(0)
        var i = 1
        while (out.size() < length) {
            t = hmac(prk, t + info + byteArrayOf(i.toByte()))
            out.write(t)
            i++
        }
        return out.toByteArray().copyOf(length)
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(if (key.isEmpty()) ByteArray(32) else key, "HmacSHA256")) }.doFinal(data)
}
